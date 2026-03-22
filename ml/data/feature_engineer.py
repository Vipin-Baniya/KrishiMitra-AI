"""
KrishiMitra ML — Feature Engineering Pipeline
==============================================
Transforms raw price + weather + arrivals data into a
machine-learning ready feature matrix.

Features built:
  - Price lags and rolling statistics
  - Seasonal decomposition residuals
  - Calendar / festival features (Indian holidays)
  - Mandi arrival volume signals
  - Weather covariates
  - Cross-mandi spread and momentum
  - Target encoding for categorical mandi/variety columns

Usage:
    fe = FeatureEngineer(config)
    df = fe.build(price_df, weather_df, arrivals_df)
    X, y = fe.to_xy(df, target_col="modal_price", horizon=7)
"""

import logging
import warnings
from datetime import date
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
import yaml
from sklearn.preprocessing import RobustScaler
from statsmodels.tsa.seasonal import STL

warnings.filterwarnings("ignore", category=FutureWarning)
log = logging.getLogger("feature_engineer")


# ─────────────────────────────────────────────────────────────
# INDIAN FESTIVAL CALENDAR  (approximate — updated annually)
# ─────────────────────────────────────────────────────────────

FESTIVAL_DATES: dict[str, list[date]] = {
    "diwali": [
        date(2022, 10, 24), date(2023, 11, 12), date(2024, 11,  1),
        date(2025, 10, 20), date(2026, 11,  8),
    ],
    "eid": [
        date(2022,  5,  3), date(2023,  4, 21), date(2024,  4, 10),
        date(2025,  3, 30), date(2026,  3, 20),
    ],
    "holi": [
        date(2022,  3, 18), date(2023,  3,  8), date(2024, 3, 25),
        date(2025,  3, 14), date(2026,  3,  4),
    ],
    "navratri": [
        date(2022, 10,  2), date(2023, 10, 15), date(2024, 10,  3),
        date(2025,  9, 22), date(2026, 10, 11),
    ],
}

# Harvest seasons per commodity: (start_month, end_month)
HARVEST_SEASONS: dict[str, list[tuple[int, int]]] = {
    "Wheat":   [(3, 5)],              # March–May rabi harvest
    "Soybean": [(10, 11)],            # October–November kharif
    "Cotton":  [(10, 1)],             # October–January
    "Maize":   [(9, 10), (3, 4)],     # kharif + rabi
    "Onion":   [(2, 4), (10, 12)],
    "Tomato":  [(11, 2), (5, 7)],
    "Potato":  [(1, 3)],
    "Gram":    [(3, 5)],
}


def days_to_nearest_festival(dt: pd.Timestamp, festival: str) -> int:
    """Return days to the nearest occurrence of a festival."""
    dates = FESTIVAL_DATES.get(festival, [])
    if not dates:
        return 365
    d = dt.date()
    diffs = [abs((fd - d).days) for fd in dates]
    return min(diffs)


def is_harvest_season(dt: pd.Timestamp, commodity: str) -> int:
    seasons = HARVEST_SEASONS.get(commodity, [])
    m = dt.month
    for start, end in seasons:
        if start <= end:
            if start <= m <= end:
                return 1
        else:   # wraps year-end e.g. Oct–Jan
            if m >= start or m <= end:
                return 1
    return 0


# ─────────────────────────────────────────────────────────────
# FEATURE ENGINEER
# ─────────────────────────────────────────────────────────────

class FeatureEngineer:
    """
    Builds the full feature matrix for one commodity × mandi pair.

    Input dataframe expected columns:
        date, commodity, mandi, state,
        modal_price, min_price, max_price,
        arrivals_tonnes  (optional)

    Output: enriched DataFrame with ~80 features + target columns.
    """

    def __init__(self, config: dict):
        self.cfg      = config
        self.feat_cfg = config["features"]
        self.scaler   = RobustScaler()
        self._fitted  = False

    # ──────────────────────────────────────────────────────────
    # MAIN BUILD METHOD
    # ──────────────────────────────────────────────────────────

    def build(
        self,
        price_df:    pd.DataFrame,
        weather_df:  Optional[pd.DataFrame] = None,
        arrivals_df: Optional[pd.DataFrame] = None,
    ) -> pd.DataFrame:
        """
        Full feature pipeline.  Returns a single DataFrame per commodity-mandi.
        Call this separately for each commodity-mandi pair.
        """
        df = price_df.copy()
        df["date"] = pd.to_datetime(df["date"], dayfirst=True)
        df = df.sort_values("date").reset_index(drop=True)

        log.debug("Building features for %d rows", len(df))

        df = self._price_features(df)
        df = self._calendar_features(df, commodity=df["commodity"].iloc[0])
        df = self._volatility_features(df)
        df = self._stl_decomposition(df)

        if arrivals_df is not None and not arrivals_df.empty:
            df = self._arrivals_features(df, arrivals_df)

        if weather_df is not None and not weather_df.empty:
            df = self._weather_features(df, weather_df)

        df = self._market_signal_features(df)
        df = self._target_columns(df)

        # Drop rows with NaN from lag features (initial window)
        max_lag = max(self.feat_cfg["price_lags"])
        df = df.iloc[max_lag:].reset_index(drop=True)

        log.debug("Feature matrix: %d rows × %d cols", df.shape[0], df.shape[1])
        return df

    # ──────────────────────────────────────────────────────────
    # PRICE FEATURES
    # ──────────────────────────────────────────────────────────

    def _price_features(self, df: pd.DataFrame) -> pd.DataFrame:
        p = df["modal_price"]

        # Raw lags
        for lag in self.feat_cfg["price_lags"]:
            df[f"price_lag_{lag}d"] = p.shift(lag)

        # Rolling statistics
        for w in self.feat_cfg["rolling_windows"]:
            df[f"price_roll_mean_{w}d"]  = p.shift(1).rolling(w).mean()
            df[f"price_roll_std_{w}d"]   = p.shift(1).rolling(w).std()
            df[f"price_roll_min_{w}d"]   = p.shift(1).rolling(w).min()
            df[f"price_roll_max_{w}d"]   = p.shift(1).rolling(w).max()
            df[f"price_roll_skew_{w}d"]  = p.shift(1).rolling(w).skew()
            # Range ratio
            roll_min = df[f"price_roll_min_{w}d"]
            roll_max = df[f"price_roll_max_{w}d"]
            df[f"price_roll_range_ratio_{w}d"] = (
                (p.shift(1) - roll_min) / (roll_max - roll_min + 1e-8)
            )

        # Momentum / rate-of-change
        for w in [3, 7, 14]:
            df[f"price_roc_{w}d"] = p.pct_change(w).shift(1)

        # Spread (max - min)
        df["price_spread"] = df["max_price"] - df["min_price"]
        df["price_spread_pct"] = df["price_spread"] / (df["modal_price"] + 1e-8)

        # Log price (stabilises variance)
        df["log_price"] = np.log1p(p)

        return df

    # ──────────────────────────────────────────────────────────
    # CALENDAR FEATURES
    # ──────────────────────────────────────────────────────────

    def _calendar_features(self, df: pd.DataFrame, commodity: str) -> pd.DataFrame:
        d = df["date"]

        df["day_of_week"]   = d.dt.dayofweek
        df["day_of_month"]  = d.dt.day
        df["week_of_year"]  = d.dt.isocalendar().week.astype(int)
        df["month"]         = d.dt.month
        df["quarter"]       = d.dt.quarter
        df["is_weekend"]    = (d.dt.dayofweek >= 5).astype(int)
        df["is_month_end"]  = d.dt.is_month_end.astype(int)
        df["is_month_start"] = d.dt.is_month_start.astype(int)

        # Cyclical encoding (sin/cos) — avoids discontinuity between Dec/Jan etc.
        df["month_sin"]        = np.sin(2 * np.pi * df["month"] / 12)
        df["month_cos"]        = np.cos(2 * np.pi * df["month"] / 12)
        df["day_of_week_sin"]  = np.sin(2 * np.pi * df["day_of_week"] / 7)
        df["day_of_week_cos"]  = np.cos(2 * np.pi * df["day_of_week"] / 7)
        df["week_sin"]         = np.sin(2 * np.pi * df["week_of_year"] / 52)
        df["week_cos"]         = np.cos(2 * np.pi * df["week_of_year"] / 52)

        # Festival proximity
        for festival in ["diwali", "eid", "holi", "navratri"]:
            df[f"days_to_{festival}"] = d.apply(
                lambda x: days_to_nearest_festival(x, festival)
            )
            # Binary: within 14 days of festival
            df[f"near_{festival}"] = (df[f"days_to_{festival}"] <= 14).astype(int)

        # Harvest season
        df["is_harvest_season"] = d.apply(
            lambda x: is_harvest_season(x, commodity)
        )

        return df

    # ──────────────────────────────────────────────────────────
    # VOLATILITY FEATURES
    # ──────────────────────────────────────────────────────────

    def _volatility_features(self, df: pd.DataFrame) -> pd.DataFrame:
        p = df["modal_price"]

        # Realised volatility (annualised)
        for w in [7, 14, 30]:
            log_ret = np.log(p / p.shift(1))
            df[f"realised_vol_{w}d"] = (
                log_ret.shift(1).rolling(w).std() * np.sqrt(252)
            )

        # Parkinson volatility (uses high-low range)
        hl_ratio = np.log(df["max_price"] / (df["min_price"] + 1e-8))
        for w in [7, 14]:
            df[f"parkinson_vol_{w}d"] = (
                (1 / (4 * np.log(2))) * (hl_ratio ** 2)
            ).shift(1).rolling(w).mean().apply(np.sqrt)

        # Consecutive up/down day streaks
        price_diff = p.diff()
        df["up_streak"]   = (price_diff > 0).astype(int)
        df["down_streak"] = (price_diff < 0).astype(int)
        # Running streak count
        df["consec_up"]   = df["up_streak"].groupby(
            (df["up_streak"] != df["up_streak"].shift()).cumsum()
        ).cumsum()
        df["consec_down"] = df["down_streak"].groupby(
            (df["down_streak"] != df["down_streak"].shift()).cumsum()
        ).cumsum()

        return df

    # ──────────────────────────────────────────────────────────
    # STL DECOMPOSITION
    # ──────────────────────────────────────────────────────────

    def _stl_decomposition(self, df: pd.DataFrame) -> pd.DataFrame:
        """
        Seasonal-Trend decomposition via LOESS.
        Adds: trend, seasonal, and residual components as features.
        These give the LSTM a clean de-trended signal to learn from.
        """
        if len(df) < 60:
            log.warning("Too few rows for STL — skipping decomposition")
            return df

        try:
            stl = STL(df["modal_price"], period=7, robust=True)
            res = stl.fit()
            df["stl_trend"]    = res.trend
            df["stl_seasonal"] = res.seasonal
            df["stl_residual"] = res.resid

            # Lagged decomposition components
            for lag in [1, 7]:
                df[f"stl_trend_lag_{lag}"]    = df["stl_trend"].shift(lag)
                df[f"stl_seasonal_lag_{lag}"] = df["stl_seasonal"].shift(lag)
                df[f"stl_residual_lag_{lag}"] = df["stl_residual"].shift(lag)
        except Exception as e:
            log.warning("STL decomposition failed: %s", e)

        return df

    # ──────────────────────────────────────────────────────────
    # ARRIVALS FEATURES
    # ──────────────────────────────────────────────────────────

    def _arrivals_features(
        self, df: pd.DataFrame, arrivals_df: pd.DataFrame
    ) -> pd.DataFrame:
        """
        Merge arrival volume (supply-side signal) into price dataframe.
        High arrivals → downward price pressure.
        Low arrivals → supply squeeze → price rise.
        """
        arr = arrivals_df.copy()
        arr["date"] = pd.to_datetime(arr["date"], dayfirst=True)

        # Aggregate to daily if granular
        arr = (
            arr.groupby(["date", "mandi", "commodity"])["arrivals_tonnes"]
            .sum().reset_index()
        )

        df = df.merge(arr, on=["date", "mandi", "commodity"], how="left")
        df["arrivals_tonnes"] = df["arrivals_tonnes"].fillna(method="ffill").fillna(0)

        a = df["arrivals_tonnes"]
        for lag in [1, 3, 7]:
            df[f"arrivals_lag_{lag}d"] = a.shift(lag)
        for w in [7, 14]:
            df[f"arrivals_roll_mean_{w}d"] = a.shift(1).rolling(w).mean()
            df[f"arrivals_roll_std_{w}d"]  = a.shift(1).rolling(w).std()

        # Arrivals deviation from rolling mean (supply shock signal)
        mean_14 = df["arrivals_roll_mean_14d"]
        df["arrivals_shock"] = (a.shift(1) - mean_14) / (mean_14 + 1e-8)

        return df

    # ──────────────────────────────────────────────────────────
    # WEATHER FEATURES
    # ──────────────────────────────────────────────────────────

    def _weather_features(
        self, df: pd.DataFrame, weather_df: pd.DataFrame
    ) -> pd.DataFrame:
        """
        Merge weather covariates. Weather affects both supply (rainfall
        during harvest → damage) and demand (cold waves → higher vegetable demand).
        """
        w = weather_df.copy()
        w["date"] = pd.to_datetime(w["date"], dayfirst=True)

        # Aggregate to state level if district-level available
        if "district" in w.columns:
            w = w.groupby(["date", "state"])[
                ["temp_max", "temp_min", "rainfall_mm", "humidity"]
            ].mean().reset_index()

        df = df.merge(w, on=["date", "state"], how="left")

        weather_vars = self.feat_cfg["weather"]["vars"]
        for var in weather_vars:
            if var not in df.columns:
                continue
            s = df[var]
            for lag in self.feat_cfg["weather"]["lags"]:
                df[f"{var}_lag_{lag}d"] = s.shift(lag)
            for rw in self.feat_cfg["weather"]["rolling"]:
                df[f"{var}_roll_mean_{rw}d"] = s.shift(1).rolling(rw).mean()

        # Extreme weather flags
        if "rainfall_mm" in df.columns:
            df["heavy_rain_flag"] = (df["rainfall_mm"] > 50).astype(int).shift(1)
        if "temp_max" in df.columns:
            df["heat_wave_flag"]  = (df["temp_max"] > 42).astype(int).shift(1)

        return df

    # ──────────────────────────────────────────────────────────
    # MARKET SIGNAL FEATURES
    # ──────────────────────────────────────────────────────────

    def _market_signal_features(self, df: pd.DataFrame) -> pd.DataFrame:
        p = df["modal_price"]

        # Price-to-MSP ratio (only for supported commodities)
        MSP_2025 = {
            "Wheat": 2275, "Gram": 5440, "Soybean": 4600,
            "Cotton": 7121, "Maize": 2090, "Paddy": 2300,
        }
        commodity = df["commodity"].iloc[0] if len(df) > 0 else ""
        msp = MSP_2025.get(commodity, None)
        if msp:
            df["msp_premium"] = (p - msp) / msp
            df["below_msp"]   = (p < msp).astype(int)

        # Price momentum
        df["price_mom_7d"]  = p.shift(1).pct_change(7)
        df["price_mom_14d"] = p.shift(1).pct_change(14)
        df["price_mom_30d"] = p.shift(1).pct_change(30)

        # Z-score relative to 90-day window
        roll_90_mean = p.shift(1).rolling(90).mean()
        roll_90_std  = p.shift(1).rolling(90).std()
        df["price_zscore_90d"] = (p.shift(1) - roll_90_mean) / (roll_90_std + 1e-8)

        # Relative price level (0=90d low, 1=90d high)
        roll_90_min = p.shift(1).rolling(90).min()
        roll_90_max = p.shift(1).rolling(90).max()
        df["price_relative_90d"] = (
            (p.shift(1) - roll_90_min) / (roll_90_max - roll_90_min + 1e-8)
        )

        return df

    # ──────────────────────────────────────────────────────────
    # TARGET COLUMNS
    # ──────────────────────────────────────────────────────────

    def _target_columns(self, df: pd.DataFrame) -> pd.DataFrame:
        """
        Create forward-looking targets for direct multi-step forecasting.
        target_h1  = price in 1 day
        target_h3  = price in 3 days
        ...
        target_h30 = price in 30 days
        """
        p = df["modal_price"]
        for h in [1, 3, 7, 14, 21, 30]:
            df[f"target_h{h}"]      = p.shift(-h)          # absolute price
            df[f"target_ret_h{h}"]  = p.pct_change(h).shift(-h)  # return

        # Direction label (1=up, 0=down) for classification head
        for h in [1, 7]:
            df[f"target_dir_h{h}"] = (p.shift(-h) > p).astype(int)

        return df

    # ──────────────────────────────────────────────────────────
    # TO X/Y MATRICES
    # ──────────────────────────────────────────────────────────

    def get_feature_columns(self, df: pd.DataFrame) -> list[str]:
        """Return all feature columns (excludes raw price, targets, metadata)."""
        exclude_prefixes = ("target_", "date", "commodity", "mandi",
                            "state", "variety", "unit")
        exclude_exact    = {"modal_price", "min_price", "max_price",
                            "arrivals_tonnes"}
        return [
            c for c in df.columns
            if not any(c.startswith(p) for p in exclude_prefixes)
            and c not in exclude_exact
        ]

    def to_xy(
        self,
        df: pd.DataFrame,
        horizon: int = 7,
    ) -> tuple[pd.DataFrame, pd.Series]:
        """Return (X, y) for a given forecast horizon."""
        target_col = f"target_h{horizon}"
        if target_col not in df.columns:
            raise ValueError(f"Target column '{target_col}' not found. "
                             f"Available: {[c for c in df.columns if c.startswith('target_')]}")

        feat_cols = self.get_feature_columns(df)
        # Drop rows where target is NaN (end of series)
        valid = df.dropna(subset=[target_col] + feat_cols[:5])

        X = valid[feat_cols].fillna(method="ffill").fillna(0)
        y = valid[target_col]
        return X, y

    def fit_scaler(self, X: pd.DataFrame) -> "FeatureEngineer":
        self.scaler.fit(X)
        self._fitted = True
        return self

    def transform(self, X: pd.DataFrame) -> np.ndarray:
        if not self._fitted:
            raise RuntimeError("Call fit_scaler() first")
        return self.scaler.transform(X)

    def fit_transform(self, X: pd.DataFrame) -> np.ndarray:
        return self.fit_scaler(X).transform(X)
