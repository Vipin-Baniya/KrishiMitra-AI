"""
KrishiMitra ML — Training Orchestrator
========================================
Trains the full ARIMA + LSTM + XGBoost ensemble for every
commodity × mandi pair defined in config.

Handles:
  - Data loading and feature engineering per pair
  - Parallel training across commodity-mandi pairs
  - MLflow experiment tracking (params, metrics, artifacts)
  - Model registry with versioning
  - Graceful failure: if one pair fails, others continue
  - Training summary report

Usage:
    python training/train.py                          # train all
    python training/train.py --commodity Wheat        # single commodity
    python training/train.py --commodity Wheat --mandi Indore  # single pair
    python training/train.py --skip-tune              # skip Optuna (fast mode)
"""

import argparse
import logging
import os
import time
import traceback
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import mlflow
import numpy as np
import pandas as pd
import yaml

from data.feature_engineer import FeatureEngineer
from models.arima.arima_model import ARIMAModel
from models.lstm.lstm_model import LSTMModel
from models.xgboost.xgboost_model import XGBoostModel
from models.ensemble.ensemble_forecaster import EnsembleForecaster
from evaluation.evaluator import ModelEvaluator

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  [%(name)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("train_orchestrator")

HORIZONS = [1, 3, 7, 14, 21, 30]


# ─────────────────────────────────────────────────────────────
# RESULT
# ─────────────────────────────────────────────────────────────

@dataclass
class PairTrainResult:
    commodity:    str
    mandi:        str
    success:      bool
    duration_s:   float
    metrics:      dict
    model_path:   str
    error:        Optional[str] = None


# ─────────────────────────────────────────────────────────────
# DATA LOADER
# ─────────────────────────────────────────────────────────────

def load_pair_data(
    cfg:       dict,
    commodity: str,
    mandi:     str,
) -> tuple[pd.DataFrame, pd.Series]:
    """Load and filter price data for one commodity-mandi pair."""
    price_df = pd.read_csv(cfg["data"]["raw_price_csv"])
    price_df["date"] = pd.to_datetime(price_df["date"], dayfirst=True)

    mask = (
        (price_df["commodity"] == commodity) &
        (price_df["mandi"]     == mandi)
    )
    pair_df = price_df[mask].sort_values("date").reset_index(drop=True)

    if len(pair_df) < cfg["data"]["min_history_days"]:
        raise ValueError(
            f"Insufficient data: {len(pair_df)} rows "
            f"(need ≥ {cfg['data']['min_history_days']})"
        )

    price_series = pair_df.set_index("date")["modal_price"]
    return pair_df, price_series


def load_auxiliary_data(cfg: dict) -> tuple[Optional[pd.DataFrame], Optional[pd.DataFrame]]:
    """Load weather and arrivals data (optional — may not exist)."""
    weather_df, arrivals_df = None, None
    w_path = cfg["data"].get("weather_csv")
    a_path = cfg["data"].get("arrivals_csv")
    if w_path and Path(w_path).exists():
        weather_df = pd.read_csv(w_path)
    if a_path and Path(a_path).exists():
        arrivals_df = pd.read_csv(a_path)
    return weather_df, arrivals_df


# ─────────────────────────────────────────────────────────────
# TRAIN ONE PAIR
# ─────────────────────────────────────────────────────────────

def train_pair(
    cfg:       dict,
    commodity: str,
    mandi:     str,
    tune:      bool = True,
    mlflow_run_id: Optional[str] = None,
) -> PairTrainResult:
    """Full training pipeline for one commodity × mandi pair."""
    t0  = time.time()
    key = f"{commodity}@{mandi}"

    log.info("=" * 60)
    log.info("Training: %s", key)
    log.info("=" * 60)

    try:
        # ── Load data ──────────────────────────────────────────
        pair_df, price_series = load_pair_data(cfg, commodity, mandi)
        weather_df, arrivals_df = load_auxiliary_data(cfg)

        # ── Feature engineering ────────────────────────────────
        fe = FeatureEngineer(cfg)
        feat_df = fe.build(pair_df, weather_df, arrivals_df)
        feat_df = feat_df.dropna(subset=["modal_price"])

        n          = len(feat_df)
        n_test     = cfg["data"]["test_days"]
        n_val      = cfg["data"]["validation_days"]
        n_train    = n - n_val - n_test

        if n_train < 60:
            raise ValueError(f"Too few training rows: {n_train}")

        feat_cols  = fe.get_feature_columns(feat_df)

        # Multi-horizon target matrix Y: (n, n_horizons)
        target_cols = [f"target_h{h}" for h in HORIZONS]
        valid_rows  = feat_df.dropna(subset=target_cols + feat_cols[:5])

        X_all = valid_rows[feat_cols].fillna(0).values
        Y_all = valid_rows[target_cols].values

        X_train = X_all[:n_train];  Y_train = Y_all[:n_train]
        X_val   = X_all[n_train:n_train+n_val];  Y_val = Y_all[n_train:n_train+n_val]
        X_test  = X_all[n_train+n_val:];         Y_test = Y_all[n_train+n_val:]

        # Fit scaler on train set
        X_train_sc = fe.fit_transform(X_train)
        X_val_sc   = fe.transform(X_val)
        X_test_sc  = fe.transform(X_test)

        ps_train = price_series.iloc[:n_train]

        log.info("  Data split: train=%d val=%d test=%d | features=%d",
                 n_train, n_val, len(X_test_sc), X_train_sc.shape[1])

        # ── ARIMA ──────────────────────────────────────────────
        log.info("  [1/4] Training ARIMA")
        arima = ARIMAModel(cfg)
        arima.fit(ps_train, commodity=commodity, mandi=mandi)

        # ── LSTM ───────────────────────────────────────────────
        log.info("  [2/4] Training LSTM")
        lstm = LSTMModel(cfg)
        lstm.fit(X_train_sc, Y_train, X_val_sc, Y_val,
                 commodity=commodity, mandi=mandi)

        # ── XGBoost ────────────────────────────────────────────
        log.info("  [3/4] Training XGBoost%s", " (with Optuna)" if tune else " (skip tune)")
        xgb_model = XGBoostModel(cfg)
        xgb_model.fit(
            X_train_sc, Y_train, X_val_sc, Y_val,
            feature_names=feat_cols,
            commodity=commodity, mandi=mandi,
            tune=tune,
        )

        # ── Ensemble ───────────────────────────────────────────
        log.info("  [4/4] Fitting ensemble meta-learner")
        ensemble = EnsembleForecaster(cfg)
        ensemble.fit(arima, lstm, xgb_model, X_train_sc, Y_train,
                     ps_train, commodity=commodity, mandi=mandi)

        # ── Evaluate on test set ────────────────────────────────
        log.info("  Evaluating on test set")
        evaluator = ModelEvaluator(cfg)
        metrics   = evaluator.evaluate_ensemble(
            ensemble, X_test_sc, Y_test, price_series, commodity, mandi
        )

        # ── Save models ────────────────────────────────────────
        model_dir = Path(cfg["paths"]["models_dir"]) / commodity / mandi
        arima.save(str(model_dir / "arima.pkl"))
        lstm.save(str(model_dir / "lstm.pt"))
        xgb_model.save(str(model_dir / "xgboost"))
        ensemble.save(str(model_dir / "ensemble.pkl"))
        fe.scaler.__class__.__module__  # force pickle works
        import pickle
        with open(model_dir / "feature_engineer.pkl", "wb") as f:
            pickle.dump(fe, f)

        # Save feature column list for inference
        with open(model_dir / "feature_cols.txt", "w") as f:
            f.write("\n".join(feat_cols))

        # ── MLflow logging ─────────────────────────────────────
        _log_to_mlflow(cfg, commodity, mandi, metrics, arima, ensemble, str(model_dir))

        duration = time.time() - t0
        log.info("  Done in %.1fs | MAE_7d=₹%.1f | dir_acc_7d=%.2f%%",
                 duration, metrics.get("mae_h7", 0), metrics.get("dir_acc_h7", 0) * 100)

        return PairTrainResult(
            commodity=commodity, mandi=mandi, success=True,
            duration_s=duration, metrics=metrics, model_path=str(model_dir),
        )

    except Exception as e:
        duration = time.time() - t0
        log.error("FAILED %s in %.1fs: %s", key, duration, e)
        log.debug(traceback.format_exc())
        return PairTrainResult(
            commodity=commodity, mandi=mandi, success=False,
            duration_s=duration, metrics={}, model_path="",
            error=str(e),
        )


# ─────────────────────────────────────────────────────────────
# MLFLOW LOGGING
# ─────────────────────────────────────────────────────────────

def _log_to_mlflow(
    cfg:       dict,
    commodity: str,
    mandi:     str,
    metrics:   dict,
    arima:     ARIMAModel,
    ensemble:  EnsembleForecaster,
    model_dir: str,
) -> None:
    try:
        mlflow.set_tracking_uri(cfg["mlflow"]["tracking_uri"])
        mlflow.set_experiment(cfg["mlflow"]["experiment_name"])

        with mlflow.start_run(run_name=f"{commodity}_{mandi}"):
            mlflow.log_param("commodity",     commodity)
            mlflow.log_param("mandi",         mandi)
            mlflow.log_param("arima_order",   str(arima._order))
            mlflow.log_param("arima_seasonal",str(arima._seasonal_order))
            mlflow.log_param("lstm_hidden",   str(cfg["lstm"]["hidden_sizes"]))
            mlflow.log_param("ensemble_method", cfg["ensemble"]["method"])

            mlflow.log_metrics({k: round(v, 4) for k, v in metrics.items()
                                if isinstance(v, (int, float))})

            mlflow.log_artifacts(model_dir, artifact_path=f"{commodity}/{mandi}")
    except Exception as e:
        log.warning("MLflow logging failed: %s", e)


# ─────────────────────────────────────────────────────────────
# TRAINING SUMMARY
# ─────────────────────────────────────────────────────────────

def print_summary(results: list[PairTrainResult]) -> None:
    succeeded = [r for r in results if r.success]
    failed    = [r for r in results if not r.success]

    print("\n" + "═" * 70)
    print("  KRISHIMITRA ML — TRAINING SUMMARY")
    print("═" * 70)
    print(f"  Total pairs trained: {len(results)}")
    print(f"  Succeeded:           {len(succeeded)}")
    print(f"  Failed:              {len(failed)}")
    print(f"  Total time:          {sum(r.duration_s for r in results):.0f}s")
    print()

    if succeeded:
        print(f"  {'Commodity':<12} {'Mandi':<16} {'MAE_7d':>8} {'DirAcc%':>8} {'Time':>6}")
        print("  " + "-" * 54)
        for r in sorted(succeeded, key=lambda x: x.metrics.get("mae_h7", 999)):
            print(f"  {r.commodity:<12} {r.mandi:<16} "
                  f"{r.metrics.get('mae_h7', 0):>7.1f} "
                  f"{r.metrics.get('dir_acc_h7', 0) * 100:>7.1f}% "
                  f"{r.duration_s:>5.0f}s")

    if failed:
        print(f"\n  FAILURES:")
        for r in failed:
            print(f"    {r.commodity}@{r.mandi}: {r.error}")

    print("═" * 70 + "\n")


# ─────────────────────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="KrishiMitra ML Training")
    parser.add_argument("--config",     default="configs/config.yaml")
    parser.add_argument("--commodity",  help="Train single commodity")
    parser.add_argument("--mandi",      help="Train single mandi (requires --commodity)")
    parser.add_argument("--skip-tune",  action="store_true", help="Skip Optuna tuning")
    parser.add_argument("--workers",    type=int, default=1,
                        help="Parallel workers (set >1 for multi-GPU or multi-CPU)")
    args = parser.parse_args()

    with open(args.config) as f:
        cfg = yaml.safe_load(f)

    # Build list of (commodity, mandi) pairs to train
    pairs: list[tuple[str, str]] = []
    primary_mandis = cfg["data"]["primary_mandis"]

    if args.commodity and args.mandi:
        pairs = [(args.commodity, args.mandi)]
    elif args.commodity:
        pairs = [(args.commodity, m) for m in primary_mandis.get(args.commodity, [])]
    else:
        for commodity, mandis in primary_mandis.items():
            for mandi in mandis:
                pairs.append((commodity, mandi))

    log.info("Training %d commodity-mandi pairs (workers=%d)", len(pairs), args.workers)

    results: list[PairTrainResult] = []

    if args.workers > 1:
        with ProcessPoolExecutor(max_workers=args.workers) as pool:
            futures = {
                pool.submit(train_pair, cfg, c, m, not args.skip_tune): (c, m)
                for c, m in pairs
            }
            for future in as_completed(futures):
                results.append(future.result())
    else:
        for commodity, mandi in pairs:
            result = train_pair(cfg, commodity, mandi, tune=not args.skip_tune)
            results.append(result)

    print_summary(results)

    # Exit non-zero if any failures
    if any(not r.success for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
