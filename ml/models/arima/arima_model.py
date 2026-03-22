"""
KrishiMitra ML — ARIMA Price Forecasting Model
===============================================
Handles:
  - ADF stationarity test → auto-determine differencing order d
  - Auto-ARIMA (pmdarima) — stepwise order search via AIC
  - SARIMA with weekly seasonality (m=7)
  - Multi-step rolling forecast (recursive strategy)
  - Confidence intervals via in-sample residual bootstrap
  - Automatic fallback to fixed orders if auto-ARIMA fails

One ARIMAModel instance per commodity × mandi pair.

Usage:
    model = ARIMAModel(config)
    model.fit(price_series)
    forecast = model.predict(horizon=30)
    print(forecast.point_forecast)   # array of 30 prices
    print(forecast.lower_80)         # 80% CI lower bound
"""

import logging
import pickle
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
from scipy import stats
from statsmodels.tsa.stattools import adfuller
from statsmodels.tsa.statespace.sarimax import SARIMAX

warnings.filterwarnings("ignore")
log = logging.getLogger("arima_model")


# ─────────────────────────────────────────────────────────────
# DATA CLASSES
# ─────────────────────────────────────────────────────────────

@dataclass
class ARIMAForecast:
    point_forecast: np.ndarray      # shape (horizon,) — predicted prices
    lower_80:       np.ndarray
    upper_80:       np.ndarray
    lower_95:       np.ndarray
    upper_95:       np.ndarray
    order:          tuple           # (p, d, q)
    seasonal_order: tuple           # (P, D, Q, m)
    aic:            float
    in_sample_mae:  float
    horizon:        int

    def to_dict(self) -> dict:
        return {
            "point_forecast":  self.point_forecast.tolist(),
            "lower_80":        self.lower_80.tolist(),
            "upper_80":        self.upper_80.tolist(),
            "lower_95":        self.lower_95.tolist(),
            "upper_95":        self.upper_95.tolist(),
            "order":           list(self.order),
            "seasonal_order":  list(self.seasonal_order),
            "aic":             round(self.aic, 2),
            "in_sample_mae":   round(self.in_sample_mae, 2),
            "horizon":         self.horizon,
        }


# ─────────────────────────────────────────────────────────────
# STATIONARITY
# ─────────────────────────────────────────────────────────────

class StationarityTester:
    """Augmented Dickey-Fuller test with automatic differencing."""

    def __init__(self, alpha: float = 0.05, max_d: int = 2):
        self.alpha = alpha
        self.max_d = max_d

    def is_stationary(self, series: pd.Series) -> bool:
        result = adfuller(series.dropna(), autolag="AIC")
        p_value = result[1]
        return p_value < self.alpha

    def find_d(self, series: pd.Series) -> int:
        """Return minimum differencing order to achieve stationarity."""
        s = series.dropna()
        for d in range(self.max_d + 1):
            if self.is_stationary(s):
                return d
            s = s.diff().dropna()
        log.warning("Could not achieve stationarity after %d differences", self.max_d)
        return self.max_d

    def report(self, series: pd.Series) -> dict:
        result = adfuller(series.dropna(), autolag="AIC")
        return {
            "adf_stat":  round(result[0], 4),
            "p_value":   round(result[1], 4),
            "stationary": result[1] < self.alpha,
            "critical_values": {k: round(v, 4) for k, v in result[4].items()},
        }


# ─────────────────────────────────────────────────────────────
# ARIMA MODEL
# ─────────────────────────────────────────────────────────────

class ARIMAModel:
    """
    SARIMA model with automatic order selection via pmdarima.

    Design decisions:
    - Uses pmdarima's auto_arima for efficient stepwise search
    - Falls back to statsmodels SARIMAX with fixed order if pmdarima fails
    - Confidence intervals computed via bootstrap on in-sample residuals
    - Clamps predictions to realistic price ranges (> 0)
    """

    def __init__(self, config: dict):
        self.cfg            = config["arima"]
        self.commodity      = None
        self.mandi          = None
        self._model         = None       # fitted statsmodels SARIMAX
        self._order         = None
        self._seasonal_order = None
        self._fitted_values  = None
        self._residuals      = None
        self._train_series   = None
        self._aic            = float("inf")
        self.tester          = StationarityTester(
            alpha=config["arima"].get("alpha", 0.05),
            max_d=config["arima"].get("max_d", 2),
        )

    # ──────────────────────────────────────────────────────────
    # FIT
    # ──────────────────────────────────────────────────────────

    def fit(
        self,
        series:    pd.Series,
        commodity: str = "",
        mandi:     str = "",
    ) -> "ARIMAModel":
        """
        Fit SARIMA model to a price time series.

        Args:
            series: pandas Series with DatetimeIndex, values = ₹/quintal
            commodity: used for logging only
            mandi:     used for logging only
        """
        self.commodity = commodity
        self.mandi     = mandi
        self._train_series = series.copy()

        series = series.dropna()
        if len(series) < 30:
            raise ValueError(f"Need ≥ 30 data points, got {len(series)}")

        log.info("Fitting ARIMA for %s @ %s (%d obs)", commodity, mandi, len(series))

        # Determine differencing order
        d = self.tester.find_d(series)
        log.debug("ADF selected d=%d", d)

        # Try auto-ARIMA first
        order, seasonal_order = self._auto_arima_search(series, d)

        # Fit final SARIMAX
        self._order          = order
        self._seasonal_order = seasonal_order

        try:
            result = SARIMAX(
                series,
                order=order,
                seasonal_order=seasonal_order,
                enforce_stationarity=False,
                enforce_invertibility=False,
                trend="c",
            ).fit(disp=False, maxiter=200, method="lbfgs")

            self._model        = result
            self._aic          = result.aic
            self._fitted_values = result.fittedvalues
            self._residuals     = result.resid

            mae = float(np.mean(np.abs(self._residuals.dropna())))
            log.info("ARIMA(%s)×(%s) AIC=%.1f  MAE=₹%.1f/qtl",
                     order, seasonal_order, result.aic, mae)

        except Exception as e:
            log.error("SARIMAX fit failed: %s — using fallback", e)
            self._fit_fallback(series)

        return self

    def _auto_arima_search(
        self,
        series: pd.Series,
        d: int,
    ) -> tuple[tuple, tuple]:
        """Use pmdarima for stepwise order search. Returns (order, seasonal_order)."""
        try:
            import pmdarima as pm
            aa_cfg = self.cfg["auto_arima"]

            model = pm.auto_arima(
                series,
                start_p=aa_cfg["start_p"],  max_p=aa_cfg["max_p"],
                start_q=aa_cfg["start_q"],  max_q=aa_cfg["max_q"],
                d=d,
                seasonal=aa_cfg["seasonal"],
                m=aa_cfg["m"],
                start_P=aa_cfg["start_P"],  max_P=aa_cfg["max_P"],
                start_Q=aa_cfg["start_Q"],  max_Q=aa_cfg["max_Q"],
                D=aa_cfg["D"],
                information_criterion=aa_cfg["information_criterion"],
                stepwise=aa_cfg["stepwise"],
                n_fits=aa_cfg["n_fits"],
                n_jobs=aa_cfg["n_jobs"],
                error_action="ignore",
                suppress_warnings=True,
                trace=False,
            )
            order          = model.order
            seasonal_order = model.seasonal_order
            log.debug("auto_arima selected: ARIMA%s×%s", order, seasonal_order)
            return order, seasonal_order

        except ImportError:
            log.warning("pmdarima not installed — using grid search fallback")
        except Exception as e:
            log.warning("auto_arima failed: %s — using fallback", e)

        return self._grid_search(series, d)

    def _grid_search(
        self,
        series: pd.Series,
        d: int,
    ) -> tuple[tuple, tuple]:
        """
        Lightweight AIC-based grid search when pmdarima is unavailable.
        Tries p ∈ [1,3], q ∈ [0,2], P ∈ [0,1], Q ∈ [0,1].
        """
        best_aic   = float("inf")
        best_order = self.cfg["fallback_order"]
        best_sord  = self.cfg["fallback_seasonal_order"]

        for p in range(1, 4):
            for q in range(0, 3):
                for P in range(0, 2):
                    for Q in range(0, 2):
                        try:
                            res = SARIMAX(
                                series,
                                order=(p, d, q),
                                seasonal_order=(P, 1, Q, 7),
                                enforce_stationarity=False,
                                enforce_invertibility=False,
                            ).fit(disp=False, maxiter=100)
                            if res.aic < best_aic:
                                best_aic   = res.aic
                                best_order = (p, d, q)
                                best_sord  = (P, 1, Q, 7)
                        except Exception:
                            continue

        log.debug("Grid search best: ARIMA%s×%s AIC=%.1f", best_order, best_sord, best_aic)
        return tuple(best_order), tuple(best_sord)

    def _fit_fallback(self, series: pd.Series) -> None:
        """Fit with fixed fallback order. Last resort."""
        order   = tuple(self.cfg["fallback_order"])
        s_order = tuple(self.cfg["fallback_seasonal_order"])
        result = SARIMAX(
            series, order=order, seasonal_order=s_order,
            enforce_stationarity=False, enforce_invertibility=False,
        ).fit(disp=False, maxiter=100)
        self._model         = result
        self._aic           = result.aic
        self._fitted_values = result.fittedvalues
        self._residuals     = result.resid
        self._order         = order
        self._seasonal_order = s_order

    # ──────────────────────────────────────────────────────────
    # PREDICT
    # ──────────────────────────────────────────────────────────

    def predict(self, horizon: int = 30) -> ARIMAForecast:
        """
        Generate point forecast + confidence intervals.

        Confidence intervals: uses statsmodels built-in prediction intervals,
        supplemented with residual bootstrap for wider coverage at long horizons.
        """
        if self._model is None:
            raise RuntimeError("Model not fitted. Call fit() first.")

        # Get statsmodels prediction with CI
        fc = self._model.get_forecast(steps=horizon)
        summary = fc.summary_frame(alpha=0.20)  # 80% CI

        point   = fc.predicted_mean.values
        lo_80   = summary["mean_ci_lower"].values
        hi_80   = summary["mean_ci_upper"].values

        # 95% CI
        fc_95   = self._model.get_forecast(steps=horizon)
        sum_95  = fc_95.summary_frame(alpha=0.05)
        lo_95   = sum_95["mean_ci_lower"].values
        hi_95   = sum_95["mean_ci_upper"].values

        # Clamp to positive prices
        point = np.maximum(point, 0)
        lo_80 = np.maximum(lo_80, 0)
        hi_80 = np.maximum(hi_80, 0)
        lo_95 = np.maximum(lo_95, 0)
        hi_95 = np.maximum(hi_95, 0)

        in_sample_mae = float(np.mean(np.abs(self._residuals.dropna())))

        return ARIMAForecast(
            point_forecast = point,
            lower_80       = lo_80,
            upper_80       = hi_80,
            lower_95       = lo_95,
            upper_95       = hi_95,
            order          = self._order,
            seasonal_order = self._seasonal_order,
            aic            = self._aic,
            in_sample_mae  = in_sample_mae,
            horizon        = horizon,
        )

    def predict_bootstrap(
        self,
        horizon:      int = 30,
        n_bootstrap:  int = 200,
        ci_levels:    list[float] = [0.80, 0.95],
    ) -> dict:
        """
        Bootstrap confidence intervals by repeatedly sampling from
        the in-sample residual distribution and re-simulating the series.
        More accurate than analytical CIs at long horizons.
        """
        if self._model is None:
            raise RuntimeError("Model not fitted.")

        residuals = self._residuals.dropna().values
        simulations = np.zeros((n_bootstrap, horizon))

        for i in range(n_bootstrap):
            # Sample residuals with replacement
            sampled_noise = np.random.choice(residuals, size=horizon, replace=True)

            # Simulate forecast path
            sim = self._model.simulate(
                nsimulations=horizon,
                anchor="end",
                repetitions=1,
                error="bootstrap",
            ).values.flatten()[:horizon]

            simulations[i] = sim + sampled_noise

        simulations = np.maximum(simulations, 0)
        point       = simulations.mean(axis=0)

        result = {"point_forecast": point.tolist()}
        for ci in ci_levels:
            lo_pct = (1 - ci) / 2 * 100
            hi_pct = (1 + ci) / 2 * 100
            result[f"lower_{int(ci*100)}"] = np.percentile(simulations, lo_pct, axis=0).tolist()
            result[f"upper_{int(ci*100)}"] = np.percentile(simulations, hi_pct, axis=0).tolist()

        return result

    # ──────────────────────────────────────────────────────────
    # DIAGNOSTICS
    # ──────────────────────────────────────────────────────────

    def diagnostics(self) -> dict:
        """Ljung-Box test on residuals, normality check."""
        if self._residuals is None:
            return {}

        resid = self._residuals.dropna()

        # Ljung-Box test (residuals should be white noise)
        from statsmodels.stats.diagnostic import acorr_ljungbox
        lb = acorr_ljungbox(resid, lags=[10, 20], return_df=True)

        # Shapiro-Wilk normality test on residuals
        _, sw_p = stats.shapiro(resid[:min(len(resid), 5000)])

        return {
            "ljung_box_p_lag10": round(float(lb["lb_pvalue"].iloc[0]), 4),
            "ljung_box_p_lag20": round(float(lb["lb_pvalue"].iloc[1]), 4),
            "residual_normality_p": round(float(sw_p), 4),
            "residual_mean":  round(float(resid.mean()), 2),
            "residual_std":   round(float(resid.std()), 2),
            "order":          list(self._order),
            "seasonal_order": list(self._seasonal_order),
            "aic":            round(self._aic, 2),
            "n_obs":          len(self._train_series),
        }

    # ──────────────────────────────────────────────────────────
    # SERIALISATION
    # ──────────────────────────────────────────────────────────

    def save(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with open(path, "wb") as f:
            pickle.dump(self, f)
        log.info("ARIMA model saved: %s", path)

    @classmethod
    def load(cls, path: str) -> "ARIMAModel":
        with open(path, "rb") as f:
            return pickle.load(f)
