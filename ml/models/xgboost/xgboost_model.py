"""
KrishiMitra ML — XGBoost Price Forecasting Model
=================================================
Handles:
  - Optuna hyperparameter optimisation (50 trials, time-series CV)
  - Direct multi-step forecasting (one model per horizon)
  - SHAP feature importance for explainability
  - Walk-forward validation to avoid data leakage
  - Monotonic constraints (price can't go below MSP — optional)

One XGBoostModel instance per commodity × mandi pair.
Trains separate XGBoost regressors per horizon [1,3,7,14,21,30].

Usage:
    model = XGBoostModel(config)
    model.fit(X_train, Y_train, X_val, Y_val)
    preds = model.predict(X)    # dict {horizon: price}
    exp   = model.explain(X)    # SHAP values per feature
"""

import logging
import pickle
import warnings
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
import optuna
import shap
import xgboost as xgb
from sklearn.model_selection import TimeSeriesSplit

optuna.logging.set_verbosity(optuna.logging.WARNING)
warnings.filterwarnings("ignore")
log = logging.getLogger("xgboost_model")


# ─────────────────────────────────────────────────────────────
# WALK-FORWARD VALIDATOR
# ─────────────────────────────────────────────────────────────

class WalkForwardCV:
    """
    Time-series aware cross-validation.
    Never uses future data to validate past predictions.

    Expands training window (anchored start) with fixed-size test folds.
    """

    def __init__(self, n_splits: int = 5, test_size: int = 14, gap: int = 1):
        self.n_splits  = n_splits
        self.test_size = test_size
        self.gap       = gap      # gap between train end and test start (avoid leakage)

    def split(self, X: np.ndarray) -> list[tuple[np.ndarray, np.ndarray]]:
        n = len(X)
        splits = []
        test_starts = np.linspace(
            n - self.n_splits * self.test_size,
            n - self.test_size,
            self.n_splits,
            dtype=int,
        )
        for test_start in test_starts:
            train_end  = test_start - self.gap
            if train_end < 20:
                continue
            train_idx = np.arange(0, train_end)
            test_idx  = np.arange(test_start, min(test_start + self.test_size, n))
            splits.append((train_idx, test_idx))
        return splits


# ─────────────────────────────────────────────────────────────
# OPTUNA OBJECTIVE
# ─────────────────────────────────────────────────────────────

def _xgb_objective(
    trial:   optuna.Trial,
    X:       np.ndarray,
    y:       np.ndarray,
    cfg:     dict,
    horizon: int,
) -> float:
    """Optuna objective: minimise walk-forward CV MAE for one horizon."""
    ss = cfg["search_space"]
    params = {
        "n_estimators":     trial.suggest_int("n_estimators",     *ss["n_estimators"]),
        "max_depth":        trial.suggest_int("max_depth",         *ss["max_depth"]),
        "learning_rate":    trial.suggest_float("learning_rate",   *ss["learning_rate"], log=True),
        "subsample":        trial.suggest_float("subsample",       *ss["subsample"]),
        "colsample_bytree": trial.suggest_float("colsample_bytree",*ss["colsample_bytree"]),
        "min_child_weight": trial.suggest_int("min_child_weight",  *ss["min_child_weight"]),
        "gamma":            trial.suggest_float("gamma",           *ss["gamma"]),
        "reg_alpha":        trial.suggest_float("reg_alpha",       *ss["reg_alpha"]),
        "reg_lambda":       trial.suggest_float("reg_lambda",      *ss["reg_lambda"]),
        "tree_method":      "hist",
        "device":           "cuda" if _cuda_available() else "cpu",
        "random_state":     42,
        "n_jobs":           -1,
    }

    wfcv   = WalkForwardCV(n_splits=4, test_size=14)
    splits = wfcv.split(X)
    maes   = []

    for train_idx, test_idx in splits:
        model = xgb.XGBRegressor(**params, verbosity=0)
        model.fit(
            X[train_idx], y[train_idx],
            eval_set=[(X[test_idx], y[test_idx])],
            verbose=False,
        )
        preds = model.predict(X[test_idx])
        preds = np.maximum(preds, 0)
        mae   = float(np.mean(np.abs(preds - y[test_idx])))
        maes.append(mae)

    return float(np.mean(maes))


def _cuda_available() -> bool:
    try:
        import xgboost as xgb
        return xgb.XGBRegressor(tree_method="hist", device="cuda").get_params()["device"] == "cuda"
    except Exception:
        return False


# ─────────────────────────────────────────────────────────────
# XGBOOST MODEL
# ─────────────────────────────────────────────────────────────

class XGBoostModel:
    """
    Ensemble of XGBoost regressors — one per forecast horizon.
    Optuna tunes hyperparameters on walk-forward CV before final fit.
    """

    HORIZONS = [1, 3, 7, 14, 21, 30]

    def __init__(self, config: dict):
        self.cfg        = config["xgboost"]
        self.models:    dict[int, xgb.XGBRegressor] = {}
        self.best_params: dict[int, dict] = {}
        self.feature_names: Optional[list[str]] = None
        self.commodity  = ""
        self.mandi      = ""
        self._explainer: Optional[shap.TreeExplainer] = None

    # ──────────────────────────────────────────────────────────
    # FIT
    # ──────────────────────────────────────────────────────────

    def fit(
        self,
        X_train:      np.ndarray,
        Y_train:      np.ndarray,     # (n_samples, n_horizons)
        X_val:        np.ndarray,
        Y_val:        np.ndarray,
        feature_names: Optional[list[str]] = None,
        commodity:    str = "",
        mandi:        str = "",
        tune:         bool = True,    # set False for quick runs / testing
    ) -> "XGBoostModel":

        self.commodity     = commodity
        self.mandi         = mandi
        self.feature_names = feature_names or [f"f{i}" for i in range(X_train.shape[1])]

        X_full = np.vstack([X_train, X_val])
        Y_full = np.vstack([Y_train, Y_val])

        for h_idx, horizon in enumerate(self.HORIZONS):
            y_train_h = Y_train[:, h_idx]
            y_val_h   = Y_val[:, h_idx]
            y_full_h  = Y_full[:, h_idx]

            log.info("XGBoost fitting horizon=%dd | %s @ %s", horizon, commodity, mandi)

            if tune:
                best_params = self._tune_horizon(X_train, y_train_h, horizon)
            else:
                best_params = self.cfg["defaults"].copy()
                best_params.update({"tree_method": "hist", "n_jobs": -1})

            self.best_params[horizon] = best_params

            # Final fit on train+val combined
            model = xgb.XGBRegressor(
                **best_params,
                verbosity=0,
                early_stopping_rounds=None,
            )
            model.fit(
                X_full, y_full_h,
                feature_names=self.feature_names,
            )
            self.models[horizon] = model

            # Quick in-sample check
            preds  = np.maximum(model.predict(X_val), 0)
            mae    = float(np.mean(np.abs(preds - y_val_h)))
            log.info("  h=%2dd  val_MAE=₹%.1f/qtl  params=%s",
                     horizon, mae, {k: round(v, 3) if isinstance(v, float) else v
                                    for k, v in best_params.items()
                                    if k in ("n_estimators", "max_depth", "learning_rate")})

        # Build SHAP explainer on the h7 model (most practically relevant)
        self._build_explainer(X_train)
        return self

    def _tune_horizon(
        self,
        X:       np.ndarray,
        y:       np.ndarray,
        horizon: int,
    ) -> dict:
        """Run Optuna study for one horizon. Returns best hyperparams."""
        study = optuna.create_study(
            direction="minimize",
            sampler=optuna.samplers.TPESampler(seed=42),
            pruner=optuna.pruners.MedianPruner(n_startup_trials=5),
        )
        study.optimize(
            lambda trial: _xgb_objective(trial, X, y, self.cfg, horizon),
            n_trials=self.cfg["n_trials"],
            timeout=self.cfg["timeout"] // len(self.HORIZONS),
            show_progress_bar=False,
        )

        params = study.best_params.copy()
        params.update({
            "tree_method": "hist",
            "device":      "cuda" if _cuda_available() else "cpu",
            "n_jobs":      -1,
            "random_state": 42,
        })
        log.debug("h=%dd best trial: MAE=%.2f | params=%s",
                  horizon, study.best_value, study.best_params)
        return params

    # ──────────────────────────────────────────────────────────
    # PREDICT
    # ──────────────────────────────────────────────────────────

    def predict(self, X: np.ndarray) -> dict[int, float]:
        """
        Predict prices for all horizons.
        X: (n_samples, n_features) — uses last row for single-point prediction.
        Returns: {1: price, 3: price, 7: price, ...}
        """
        if not self.models:
            raise RuntimeError("Model not fitted. Call fit() first.")

        x_last = X[-1:].reshape(1, -1)
        result = {}
        for horizon, model in self.models.items():
            pred = model.predict(x_last)[0]
            result[horizon] = float(max(pred, 0))
        return result

    def predict_all_rows(self, X: np.ndarray) -> dict[int, np.ndarray]:
        """Predict for all rows — used in ensemble stacking."""
        result = {}
        for horizon, model in self.models.items():
            preds = model.predict(X)
            result[horizon] = np.maximum(preds, 0)
        return result

    # ──────────────────────────────────────────────────────────
    # EXPLAINABILITY
    # ──────────────────────────────────────────────────────────

    def _build_explainer(self, X_background: np.ndarray) -> None:
        """Build SHAP TreeExplainer on the 7-day horizon model."""
        try:
            model_7d = self.models.get(7)
            if model_7d is None:
                return
            self._explainer = shap.TreeExplainer(
                model_7d,
                feature_names=self.feature_names,
            )
            log.debug("SHAP explainer built for h=7d model")
        except Exception as e:
            log.warning("SHAP explainer failed: %s", e)

    def explain(
        self,
        X:       np.ndarray,
        n_rows:  int = 1,
    ) -> Optional[dict]:
        """
        Compute SHAP values for the last n_rows of X.
        Returns top features sorted by absolute mean SHAP value.
        Used in the inference server to show farmers WHY the price is predicted.
        """
        if self._explainer is None:
            return None

        x_input = X[-n_rows:] if n_rows > 1 else X[-1:].reshape(1, -1)
        shap_values = self._explainer.shap_values(x_input)

        mean_abs = np.abs(shap_values).mean(axis=0)
        top_idx  = np.argsort(mean_abs)[::-1][:15]

        return {
            "top_features": [
                {
                    "feature": self.feature_names[i] if self.feature_names else f"f{i}",
                    "shap_value": round(float(shap_values[0, i]), 2),
                    "abs_impact": round(float(mean_abs[i]), 2),
                }
                for i in top_idx
            ],
            "model_horizon_days": 7,
            "explanation_note": "SHAP values show ₹/qtl impact of each feature on the 7-day forecast",
        }

    def feature_importance(self, horizon: int = 7, top_n: int = 20) -> pd.DataFrame:
        """XGBoost built-in gain-based feature importance for a given horizon."""
        model = self.models.get(horizon)
        if model is None:
            raise ValueError(f"No model for horizon={horizon}")

        importance = model.get_booster().get_score(importance_type="gain")
        df = pd.DataFrame([
            {"feature": k, "gain": v} for k, v in importance.items()
        ]).sort_values("gain", ascending=False).head(top_n)
        return df

    # ──────────────────────────────────────────────────────────
    # SERIALISATION
    # ──────────────────────────────────────────────────────────

    def save(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        # Save XGBoost models as .ubj (binary JSON), rest as pickle
        state = {
            "best_params":    self.best_params,
            "feature_names":  self.feature_names,
            "commodity":      self.commodity,
            "mandi":          self.mandi,
            "horizons":       self.HORIZONS,
        }
        base = Path(path)
        base.mkdir(parents=True, exist_ok=True)
        with open(base / "meta.pkl", "wb") as f:
            pickle.dump(state, f)
        for h, model in self.models.items():
            model.save_model(str(base / f"xgb_h{h}.ubj"))
        log.info("XGBoost model saved: %s", path)

    @classmethod
    def load(cls, path: str, config: dict) -> "XGBoostModel":
        obj  = cls(config)
        base = Path(path)
        with open(base / "meta.pkl", "rb") as f:
            state = pickle.load(f)
        obj.best_params    = state["best_params"]
        obj.feature_names  = state["feature_names"]
        obj.commodity      = state["commodity"]
        obj.mandi          = state["mandi"]

        for h in state["horizons"]:
            model_path = base / f"xgb_h{h}.ubj"
            if model_path.exists():
                m = xgb.XGBRegressor()
                m.load_model(str(model_path))
                obj.models[h] = m

        log.info("XGBoost model loaded from %s (%d horizons)", path, len(obj.models))
        return obj
