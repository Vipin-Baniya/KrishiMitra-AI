"""
KrishiMitra ML — Stacking Ensemble
====================================
Combines ARIMA + LSTM + XGBoost using a Ridge regression meta-learner
trained on out-of-fold predictions (stacking CV).

Pipeline:
  1. For each base model — generate OOF predictions on train set
  2. Stack OOF predictions as meta-features
  3. Train Ridge on meta-features → final prediction
  4. At inference: base model predictions → Ridge → ensemble output

Outputs per request:
  - point_forecast:   ₹/quintal for days [1,3,7,14,21,30]
  - lower_80/upper_80: confidence band
  - lower_95/upper_95: wider confidence band
  - sell_decision:    SELL_NOW | WAIT_N_DAYS | HOLD
  - peak_day:         day index of expected price peak
  - profit_gain:      ₹/quintal extra if wait vs sell today
  - explanation:      SHAP-based feature attribution (top 5 factors)

Usage:
    ensemble = EnsembleForecaster(config)
    ensemble.fit(arima, lstm, xgb, X_train, Y_train, price_series)
    result = ensemble.predict(X_latest, price_series)
"""

import logging
import pickle
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.preprocessing import StandardScaler

from models.arima.arima_model import ARIMAModel, ARIMAForecast
from models.lstm.lstm_model import LSTMModel
from models.xgboost.xgboost_model import XGBoostModel

log = logging.getLogger("ensemble")


# ─────────────────────────────────────────────────────────────
# RESULT DATACLASS
# ─────────────────────────────────────────────────────────────

@dataclass
class EnsembleForecast:
    commodity:      str
    mandi:          str
    current_price:  float               # today's ₹/quintal
    forecast_date:  str                 # ISO date string

    point_forecast: list[float]         # [day1, day3, day7, day14, day21, day30]
    lower_80:       list[float]
    upper_80:       list[float]
    lower_95:       list[float]
    upper_95:       list[float]

    horizons:       list[int] = field(default_factory=lambda: [1, 3, 7, 14, 21, 30])

    # Decision outputs
    sell_decision:  str   = "HOLD"      # SELL_NOW | WAIT_N_DAYS | HOLD
    wait_days:      int   = 0           # populated when decision = WAIT_N_DAYS
    peak_day:       int   = 0           # day of predicted price peak
    peak_price:     float = 0.0
    profit_gain:    float = 0.0         # ₹/quintal gain vs selling today
    confidence:     float = 0.0         # 0–1 model confidence

    # Attribution
    explanation:    Optional[dict] = None    # SHAP attribution
    model_weights:  Optional[dict] = None   # {"arima": w, "lstm": w, "xgboost": w}

    def to_dict(self) -> dict:
        return {
            "commodity":     self.commodity,
            "mandi":         self.mandi,
            "current_price": self.current_price,
            "forecast_date": self.forecast_date,
            "horizons":      self.horizons,
            "point_forecast": [round(p, 2) for p in self.point_forecast],
            "lower_80":       [round(p, 2) for p in self.lower_80],
            "upper_80":       [round(p, 2) for p in self.upper_80],
            "lower_95":       [round(p, 2) for p in self.lower_95],
            "upper_95":       [round(p, 2) for p in self.upper_95],
            "sell_decision":  self.sell_decision,
            "wait_days":      self.wait_days,
            "peak_day":       self.peak_day,
            "peak_price":     round(self.peak_price, 2),
            "profit_gain":    round(self.profit_gain, 2),
            "confidence":     round(self.confidence, 3),
            "explanation":    self.explanation,
            "model_weights":  self.model_weights,
        }


# ─────────────────────────────────────────────────────────────
# STORAGE COST TABLE
# ─────────────────────────────────────────────────────────────

STORAGE_COST_PER_DAY: dict[str, float] = {
    "Wheat":   2.5,
    "Soybean": 4.0,
    "Gram":    2.0,
    "Cotton":  3.0,
    "Maize":   1.5,
    "Onion":   6.0,
    "Tomato":  8.0,
    "Potato":  5.0,
}

def get_storage_cost(commodity: str, days: int) -> float:
    rate = STORAGE_COST_PER_DAY.get(commodity, 3.0)
    return rate * days


# ─────────────────────────────────────────────────────────────
# ENSEMBLE FORECASTER
# ─────────────────────────────────────────────────────────────

class EnsembleForecaster:
    """
    Stacking ensemble combining ARIMA + LSTM + XGBoost.

    Meta-learner: Ridge regression trained on OOF base model predictions.
    Weights adapt per commodity — e.g. ARIMA dominates for stable wheat,
    LSTM dominates for volatile tomato.
    """

    HORIZONS = [1, 3, 7, 14, 21, 30]

    def __init__(self, config: dict):
        self.cfg         = config["ensemble"]
        self.meta_models: dict[int, Ridge]  = {}
        self.meta_scaler: dict[int, StandardScaler] = {}
        self.static_weights = self.cfg.get("weights", {
            "arima": 0.30, "lstm": 0.45, "xgboost": 0.25
        })
        self.commodity    = ""
        self.mandi        = ""
        self._fitted      = False

    # ──────────────────────────────────────────────────────────
    # FIT (STACKING)
    # ──────────────────────────────────────────────────────────

    def fit(
        self,
        arima:        ARIMAModel,
        lstm:         LSTMModel,
        xgb_model:    XGBoostModel,
        X_train:      np.ndarray,
        Y_train:      np.ndarray,         # (n, n_horizons)
        price_series: pd.Series,
        commodity:    str = "",
        mandi:        str = "",
    ) -> "EnsembleForecaster":
        """
        Fit meta-learner on out-of-fold base model predictions.
        Uses simple 3-fold walk-forward CV to generate OOF preds.
        """
        self.commodity = commodity
        self.mandi     = mandi
        self._arima    = arima
        self._lstm     = lstm
        self._xgb      = xgb_model

        method = self.cfg.get("method", "stacking")

        if method == "stacking":
            self._fit_stacking(X_train, Y_train, price_series)
        else:
            # Weighted average — no meta-learner needed
            log.info("Using fixed weighted average ensemble (%s)", self.static_weights)

        self._fitted = True
        return self

    def _fit_stacking(
        self,
        X:           np.ndarray,
        Y:           np.ndarray,
        price_series: pd.Series,
    ) -> None:
        """
        Generate OOF predictions from each base model and train
        a Ridge meta-learner to combine them.
        """
        n = len(X)
        n_folds = 3
        fold_size = n // (n_folds + 1)

        for h_idx, horizon in enumerate(self.HORIZONS):
            y_h       = Y[:, h_idx]
            meta_X    = np.zeros((n, 3))  # 3 base model predictions
            meta_y    = y_h.copy()

            for fold in range(n_folds):
                test_start = (fold + 1) * fold_size
                test_end   = min(test_start + fold_size, n)
                train_slice = slice(0, test_start)
                test_slice  = slice(test_start, test_end)

                X_tr, y_tr = X[train_slice], y_h[train_slice]
                X_te       = X[test_slice]

                # XGBoost OOF predictions (always available)
                xgb_preds = self._xgb.predict_all_rows(X_te).get(horizon, np.zeros(len(X_te)))
                meta_X[test_start:test_end, 2] = xgb_preds

                # LSTM OOF — needs enough context window
                seq_len = self._lstm.cfg["sequence_length"]
                if test_start >= seq_len:
                    for i in range(test_start, test_end):
                        try:
                            lstm_pred = self._lstm.predict(X[:i])
                            meta_X[i, 1] = lstm_pred.get(horizon, 0)
                        except Exception:
                            meta_X[i, 1] = meta_X[i, 2]  # fall back to XGB
                else:
                    meta_X[test_start:test_end, 1] = xgb_preds  # fallback

                # ARIMA OOF — refit on training slice
                try:
                    arima_series = price_series.iloc[: test_start + 1] if hasattr(price_series, "iloc") else price_series
                    temp_arima = ARIMAModel({"arima": self._arima.cfg})
                    temp_arima.fit(arima_series)
                    arima_fc = temp_arima.predict(horizon=max(self.HORIZONS))
                    arima_pred = arima_fc.point_forecast[h_idx] if h_idx < len(arima_fc.point_forecast) else 0
                    meta_X[test_start:test_end, 0] = arima_pred
                except Exception:
                    meta_X[test_start:test_end, 0] = xgb_preds  # fallback

            # Train Ridge on OOF predictions
            valid_mask = ~np.isnan(meta_y) & ~np.isnan(meta_X).any(axis=1)
            meta_X_v   = meta_X[valid_mask]
            meta_y_v   = meta_y[valid_mask]

            scaler = StandardScaler()
            meta_X_scaled = scaler.fit_transform(meta_X_v)

            alpha = self.cfg.get("meta_alpha", 1.0)
            ridge = Ridge(alpha=alpha, fit_intercept=True)
            ridge.fit(meta_X_scaled, meta_y_v)

            self.meta_models[horizon] = ridge
            self.meta_scaler[horizon] = scaler

            coef     = ridge.coef_
            coef_sum = np.abs(coef).sum() + 1e-8
            weights  = np.abs(coef) / coef_sum
            log.debug("h=%2dd meta weights: arima=%.2f lstm=%.2f xgb=%.2f",
                      horizon, weights[0], weights[1], weights[2])

    # ──────────────────────────────────────────────────────────
    # PREDICT
    # ──────────────────────────────────────────────────────────

    def predict(
        self,
        X:            np.ndarray,
        price_series: pd.Series,
        current_price: Optional[float] = None,
    ) -> EnsembleForecast:
        """
        Full forecast with sell decision and confidence intervals.
        """
        from datetime import date as dt_date
        if current_price is None:
            current_price = float(price_series.iloc[-1]) if len(price_series) else 0.0

        # ── 1. Base model predictions ──────────────────────────
        arima_fc    = self._arima.predict(horizon=max(self.HORIZONS))
        lstm_preds  = self._lstm.predict(X)
        xgb_preds   = self._xgb.predict(X)

        # Also get LSTM uncertainty
        lstm_uncertain = self._lstm.predict_with_uncertainty(X, n_samples=50)

        point_preds = []
        for h_idx, horizon in enumerate(self.HORIZONS):
            arima_p = float(arima_fc.point_forecast[h_idx])
            lstm_p  = lstm_preds.get(horizon, arima_p)
            xgb_p   = xgb_preds.get(horizon, arima_p)

            base_preds = np.array([arima_p, lstm_p, xgb_p]).reshape(1, -1)

            if self.meta_models.get(horizon) and self.cfg.get("method") == "stacking":
                scaler     = self.meta_scaler[horizon]
                meta_model = self.meta_models[horizon]
                scaled     = scaler.transform(base_preds)
                ensemble_p = float(meta_model.predict(scaled)[0])
            else:
                w = self.static_weights
                ensemble_p = w["arima"] * arima_p + w["lstm"] * lstm_p + w["xgboost"] * xgb_p

            point_preds.append(max(ensemble_p, 0))

        # ── 2. Confidence intervals ────────────────────────────
        ci_cfg      = self.cfg.get("ci_method", "quantile")
        lower_80, upper_80, lower_95, upper_95 = self._compute_ci(
            point_preds, arima_fc, lstm_uncertain, xgb_preds
        )

        # ── 3. Sell decision ───────────────────────────────────
        decision = self._sell_decision(
            point_preds, current_price, self.commodity
        )

        # ── 4. Model weights for transparency ─────────────────
        model_weights = self._extract_weights()

        # ── 5. SHAP explanation ────────────────────────────────
        explanation = self._xgb.explain(X)

        # ── 6. Confidence score (based on CI width) ────────────
        ci_widths  = [u - l for u, l in zip(upper_80, lower_80)]
        rel_widths = [w / max(p, 1) for w, p in zip(ci_widths, point_preds)]
        confidence = float(np.clip(1 - np.mean(rel_widths), 0, 1))

        return EnsembleForecast(
            commodity      = self.commodity,
            mandi          = self.mandi,
            current_price  = round(current_price, 2),
            forecast_date  = dt_date.today().isoformat(),
            point_forecast = point_preds,
            lower_80       = lower_80,
            upper_80       = upper_80,
            lower_95       = lower_95,
            upper_95       = upper_95,
            sell_decision  = decision["decision"],
            wait_days      = decision["wait_days"],
            peak_day       = decision["peak_day"],
            peak_price     = decision["peak_price"],
            profit_gain    = decision["profit_gain"],
            confidence     = confidence,
            explanation    = explanation,
            model_weights  = model_weights,
        )

    def _compute_ci(
        self,
        point_preds:     list[float],
        arima_fc:        ARIMAForecast,
        lstm_uncertain:  dict,
        xgb_preds:       dict,
    ) -> tuple[list, list, list, list]:
        """Combine ARIMA analytical CIs with LSTM MC-dropout uncertainty."""
        lower_80, upper_80 = [], []
        lower_95, upper_95 = [], []

        for h_idx, horizon in enumerate(self.HORIZONS):
            p = point_preds[h_idx]

            # ARIMA CI
            a_lo80 = arima_fc.lower_80[h_idx]
            a_hi80 = arima_fc.upper_80[h_idx]
            a_lo95 = arima_fc.lower_95[h_idx]
            a_hi95 = arima_fc.upper_95[h_idx]

            # LSTM std → convert to approx 80% CI (±1.28σ) and 95% CI (±1.96σ)
            lstm_u = lstm_uncertain.get(horizon, {})
            lstm_std = lstm_u.get("std", (a_hi80 - a_lo80) / 2.56)
            l_lo80  = p - 1.28 * lstm_std
            l_hi80  = p + 1.28 * lstm_std
            l_lo95  = p - 1.96 * lstm_std
            l_hi95  = p + 1.96 * lstm_std

            # Weighted CI combination
            wa, wl = self.static_weights["arima"], self.static_weights["lstm"]
            w_sum  = wa + wl
            lo80 = max((wa * a_lo80 + wl * l_lo80) / w_sum, 0)
            hi80 = max((wa * a_hi80 + wl * l_hi80) / w_sum, 0)
            lo95 = max((wa * a_lo95 + wl * l_lo95) / w_sum, 0)
            hi95 = max((wa * a_hi95 + wl * l_hi95) / w_sum, 0)

            lower_80.append(round(lo80, 2))
            upper_80.append(round(hi80, 2))
            lower_95.append(round(lo95, 2))
            upper_95.append(round(hi95, 2))

        return lower_80, upper_80, lower_95, upper_95

    def _sell_decision(
        self,
        forecasts:      list[float],
        current_price:  float,
        commodity:      str,
    ) -> dict:
        """
        Core sell/wait/hold decision logic.

        Logic:
          1. Find peak price day across forecast horizon
          2. Compute net profit of waiting (peak price - storage cost - current price)
          3. If net gain > threshold → WAIT, else → SELL_NOW
          4. Perishable crops (tomato, onion) use shorter threshold
        """
        PERISHABLE      = {"Tomato", "Onion", "Potato"}
        MIN_GAIN_THRESH = 150    # ₹/quintal minimum gain to justify waiting
        if commodity in PERISHABLE:
            MIN_GAIN_THRESH = 80

        # Find peak
        peak_idx   = int(np.argmax(forecasts))
        peak_day   = self.HORIZONS[peak_idx]
        peak_price = forecasts[peak_idx]

        # Net gain = price gain - storage cost
        storage    = get_storage_cost(commodity, peak_day)
        net_gain   = peak_price - current_price - storage

        if net_gain >= MIN_GAIN_THRESH and peak_day > 0:
            decision   = "WAIT_N_DAYS"
            wait_days  = peak_day
        elif forecasts[0] < current_price * 0.97:
            # Price dropping immediately → sell now
            decision   = "SELL_NOW"
            wait_days  = 0
            net_gain   = 0
        else:
            decision   = "SELL_NOW"
            wait_days  = 0

        return {
            "decision":   decision,
            "wait_days":  wait_days,
            "peak_day":   peak_day,
            "peak_price": round(peak_price, 2),
            "profit_gain": round(max(net_gain, 0), 2),
        }

    def _extract_weights(self) -> dict:
        """Extract learned meta-weights for the h=7 horizon."""
        model = self.meta_models.get(7)
        if model is None:
            return self.static_weights

        coef = np.abs(model.coef_)
        s    = coef.sum() + 1e-8
        return {
            "arima":   round(float(coef[0] / s), 3),
            "lstm":    round(float(coef[1] / s), 3),
            "xgboost": round(float(coef[2] / s), 3),
        }

    # ──────────────────────────────────────────────────────────
    # SERIALISATION
    # ──────────────────────────────────────────────────────────

    def save(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        state = {
            "meta_models":    self.meta_models,
            "meta_scaler":    self.meta_scaler,
            "static_weights": self.static_weights,
            "commodity":      self.commodity,
            "mandi":          self.mandi,
        }
        with open(path, "wb") as f:
            pickle.dump(state, f)
        log.info("Ensemble saved: %s", path)

    @classmethod
    def load(cls, path: str, config: dict) -> "EnsembleForecaster":
        obj = cls(config)
        with open(path, "rb") as f:
            state = pickle.load(f)
        obj.meta_models    = state["meta_models"]
        obj.meta_scaler    = state["meta_scaler"]
        obj.static_weights = state["static_weights"]
        obj.commodity      = state["commodity"]
        obj.mandi          = state["mandi"]
        obj._fitted        = True
        log.info("Ensemble loaded from %s", path)
        return obj
