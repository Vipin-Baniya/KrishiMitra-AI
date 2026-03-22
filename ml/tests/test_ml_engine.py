"""
KrishiMitra ML — Test Suite
============================
Tests: feature engineering, model smoke tests, ensemble, API endpoints.

Run:
    pytest tests/ -v
    pytest tests/ -v -k "arima"          # filter
    pytest tests/ -v --tb=short          # short tracebacks
"""

import json
import numpy as np
import pandas as pd
import pytest
import yaml
from pathlib import Path

# ── Fixtures ─────────────────────────────────────────────────

@pytest.fixture(scope="session")
def config():
    with open("configs/config.yaml") as f:
        return yaml.safe_load(f)

@pytest.fixture(scope="session")
def synthetic_price_series():
    """150 days of synthetic wheat price data with trend + seasonality."""
    np.random.seed(42)
    n       = 150
    dates   = pd.date_range("2024-01-01", periods=n, freq="D")
    trend   = np.linspace(2000, 2400, n)
    season  = 80 * np.sin(2 * np.pi * np.arange(n) / 7)
    noise   = np.random.normal(0, 30, n)
    prices  = np.maximum(trend + season + noise, 500)
    return pd.Series(prices, index=dates, name="modal_price")

@pytest.fixture(scope="session")
def synthetic_price_df(synthetic_price_series):
    """Price DataFrame with all required columns."""
    s = synthetic_price_series
    return pd.DataFrame({
        "date":        s.index.strftime("%d/%m/%Y"),
        "commodity":   "Wheat",
        "mandi":       "Indore",
        "state":       "Madhya Pradesh",
        "modal_price": s.values,
        "min_price":   s.values * 0.95,
        "max_price":   s.values * 1.05,
    })

@pytest.fixture(scope="session")
def feature_df(config, synthetic_price_df):
    from data.feature_engineer import FeatureEngineer
    fe = FeatureEngineer(config)
    return fe.build(synthetic_price_df), fe

@pytest.fixture(scope="session")
def xy_data(feature_df, config):
    df, fe = feature_df
    feat_cols = fe.get_feature_columns(df)
    X, y = fe.to_xy(df, horizon=7)
    X_np = X.fillna(0).values
    X_sc = fe.fit_transform(X_np)
    y_np = y.values
    n_train = int(len(X_sc) * 0.8)
    return {
        "X_train": X_sc[:n_train], "y_train": y_np[:n_train],
        "X_val":   X_sc[n_train:], "y_val":   y_np[n_train:],
        "feat_cols": feat_cols,
        "fe":        fe,
    }

# ── Feature Engineering Tests ────────────────────────────────

class TestFeatureEngineer:

    def test_build_returns_dataframe(self, feature_df):
        df, fe = feature_df
        assert isinstance(df, pd.DataFrame)
        assert len(df) > 0

    def test_lag_features_created(self, feature_df):
        df, fe = feature_df
        assert "price_lag_1d" in df.columns
        assert "price_lag_7d" in df.columns
        assert "price_lag_28d" in df.columns

    def test_rolling_features_created(self, feature_df):
        df, fe = feature_df
        assert "price_roll_mean_7d" in df.columns
        assert "price_roll_std_14d" in df.columns

    def test_calendar_features_created(self, feature_df):
        df, fe = feature_df
        assert "month_sin" in df.columns
        assert "day_of_week_cos" in df.columns
        assert "is_harvest_season" in df.columns

    def test_stl_features_created(self, feature_df):
        df, fe = feature_df
        assert "stl_trend" in df.columns
        assert "stl_seasonal" in df.columns
        assert "stl_residual" in df.columns

    def test_target_columns_created(self, feature_df):
        df, fe = feature_df
        for h in [1, 3, 7, 14, 21, 30]:
            assert f"target_h{h}" in df.columns

    def test_no_future_leakage(self, feature_df):
        """All feature lags must use shift(≥1) — no same-day leakage."""
        df, fe = feature_df
        # price_lag_1d should equal yesterday's price
        lag_col  = df["price_lag_1d"].dropna()
        modal    = df["modal_price"].shift(1).dropna()
        shared   = lag_col.index.intersection(modal.index)
        np.testing.assert_array_almost_equal(
            lag_col.loc[shared].values, modal.loc[shared].values, decimal=2
        )

    def test_to_xy_shape(self, xy_data):
        X, y = xy_data["X_train"], xy_data["y_train"]
        assert X.ndim == 2
        assert len(X) == len(y)
        assert X.shape[1] > 10   # should have many features

    def test_scaler_fitted(self, xy_data):
        fe = xy_data["fe"]
        assert fe._fitted is True


# ── ARIMA Tests ───────────────────────────────────────────────

class TestARIMA:

    def test_fit_runs(self, config, synthetic_price_series):
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series, commodity="Wheat", mandi="Indore")
        assert model._model is not None

    def test_predict_shape(self, config, synthetic_price_series):
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series)
        fc = model.predict(horizon=30)
        assert len(fc.point_forecast) == 30
        assert len(fc.lower_80)       == 30
        assert len(fc.upper_80)       == 30

    def test_predict_positive(self, config, synthetic_price_series):
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series)
        fc = model.predict(horizon=7)
        assert all(p >= 0 for p in fc.point_forecast)

    def test_ci_ordering(self, config, synthetic_price_series):
        """lower_80 ≤ point ≤ upper_80 for most days."""
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series)
        fc = model.predict(horizon=14)
        # At least 70% of days should have correct CI ordering
        ordered = sum(
            lo <= pt <= hi
            for lo, pt, hi in zip(fc.lower_80, fc.point_forecast, fc.upper_80)
        )
        assert ordered / 14 >= 0.70

    def test_stationarity_tester(self, synthetic_price_series):
        from models.arima.arima_model import StationarityTester
        tester = StationarityTester()
        d = tester.find_d(synthetic_price_series)
        assert 0 <= d <= 2

    def test_diagnostics_keys(self, config, synthetic_price_series):
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series)
        diag = model.diagnostics()
        assert "aic" in diag
        assert "ljung_box_p_lag10" in diag

    def test_save_load(self, config, synthetic_price_series, tmp_path):
        from models.arima.arima_model import ARIMAModel
        model = ARIMAModel(config)
        model.fit(synthetic_price_series)
        save_path = str(tmp_path / "arima.pkl")
        model.save(save_path)
        loaded = ARIMAModel.load(save_path)
        fc1 = model.predict(7)
        fc2 = loaded.predict(7)
        np.testing.assert_array_almost_equal(fc1.point_forecast, fc2.point_forecast, decimal=1)


# ── LSTM Tests ────────────────────────────────────────────────

class TestLSTM:

    def test_fit_runs(self, config, xy_data):
        from models.lstm.lstm_model import LSTMModel
        cfg = dict(config)
        cfg["lstm"] = {**config["lstm"], "epochs": 3, "patience": 2}

        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = LSTMModel(cfg)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val)
        assert model.net is not None

    def test_predict_all_horizons(self, config, xy_data):
        from models.lstm.lstm_model import LSTMModel
        cfg = dict(config)
        cfg["lstm"] = {**config["lstm"], "epochs": 2, "patience": 1}

        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = LSTMModel(cfg)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val)

        preds = model.predict(xy_data["X_train"])
        assert set(preds.keys()) == {1, 3, 7, 14, 21, 30}
        assert all(v >= 0 for v in preds.values())

    def test_predict_with_uncertainty(self, config, xy_data):
        from models.lstm.lstm_model import LSTMModel
        cfg = dict(config)
        cfg["lstm"] = {**config["lstm"], "epochs": 2, "patience": 1}

        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = LSTMModel(cfg)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val)

        unc = model.predict_with_uncertainty(xy_data["X_train"], n_samples=10)
        assert 7 in unc
        assert "mean" in unc[7]
        assert "std"  in unc[7]
        assert unc[7]["std"] >= 0

    def test_sequence_builder(self):
        from models.lstm.lstm_model import SequenceBuilder
        sb = SequenceBuilder(seq_len=10)
        X  = np.random.randn(100, 5)
        Y  = np.random.randn(100, 3)
        Xs, Ys = sb.build(X, Y)
        assert Xs.shape == (90, 10, 5)
        assert Ys.shape == (90, 3)


# ── XGBoost Tests ─────────────────────────────────────────────

class TestXGBoost:

    def test_fit_runs_no_tune(self, config, xy_data):
        from models.xgboost.xgboost_model import XGBoostModel
        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = XGBoostModel(config)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val,
                  feature_names=xy_data["feat_cols"], tune=False)
        assert len(model.models) == 6

    def test_predict_all_horizons(self, config, xy_data):
        from models.xgboost.xgboost_model import XGBoostModel
        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = XGBoostModel(config)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val,
                  feature_names=xy_data["feat_cols"], tune=False)

        preds = model.predict(xy_data["X_train"])
        assert set(preds.keys()) == {1, 3, 7, 14, 21, 30}

    def test_feature_importance(self, config, xy_data):
        from models.xgboost.xgboost_model import XGBoostModel
        Y_train = np.column_stack([xy_data["y_train"]] * 6)
        Y_val   = np.column_stack([xy_data["y_val"]]   * 6)

        model = XGBoostModel(config)
        model.fit(xy_data["X_train"], Y_train, xy_data["X_val"], Y_val,
                  feature_names=xy_data["feat_cols"], tune=False)

        fi = model.feature_importance(horizon=7, top_n=10)
        assert len(fi) <= 10
        assert "feature" in fi.columns
        assert "gain" in fi.columns

    def test_walk_forward_cv(self):
        from models.xgboost.xgboost_model import WalkForwardCV
        X      = np.random.randn(100, 5)
        wfcv   = WalkForwardCV(n_splits=3, test_size=10)
        splits = wfcv.split(X)
        assert len(splits) == 3
        for train_idx, test_idx in splits:
            assert max(train_idx) < min(test_idx)   # no overlap


# ── Metrics Tests ─────────────────────────────────────────────

class TestMetrics:

    def test_mae(self):
        from evaluation.evaluator import mae
        y_true = np.array([100, 200, 300])
        y_pred = np.array([110, 190, 310])
        assert mae(y_true, y_pred) == pytest.approx(10.0)

    def test_directional_accuracy_perfect(self):
        from evaluation.evaluator import directional_accuracy
        y_true = np.array([110, 90, 130])
        y_pred = np.array([115, 85, 125])
        y_prev = np.array([100, 100, 100])
        assert directional_accuracy(y_true, y_pred, y_prev) == 1.0

    def test_coverage_rate(self):
        from evaluation.evaluator import coverage_rate
        y  = np.array([100, 200, 300, 400])
        lo = np.array([90,  180, 280, 380])
        hi = np.array([110, 220, 320, 420])
        assert coverage_rate(y, lo, hi) == 1.0


# ── Sell Decision Tests ───────────────────────────────────────

class TestSellDecision:

    def test_sell_now_when_price_falling(self, config):
        from models.ensemble.ensemble_forecaster import EnsembleForecaster
        ens = EnsembleForecaster(config)
        # All forecast prices below current
        forecasts     = [1900, 1850, 1800, 1750, 1700, 1650]
        current_price = 2000
        decision = ens._sell_decision(forecasts, current_price, "Wheat")
        assert decision["decision"] == "SELL_NOW"

    def test_wait_when_price_rising(self, config):
        from models.ensemble.ensemble_forecaster import EnsembleForecaster
        ens = EnsembleForecaster(config)
        # Price peaks at day 14 with big gain
        forecasts     = [2050, 2100, 2200, 2400, 2350, 2300]
        current_price = 2000
        decision = ens._sell_decision(forecasts, current_price, "Wheat")
        assert decision["decision"] == "WAIT_N_DAYS"
        assert decision["wait_days"] > 0

    def test_storage_cost_applied(self, config):
        from models.ensemble.ensemble_forecaster import EnsembleForecaster, get_storage_cost
        # Tomato: 8 ₹/qtl/day × 7 days = 56 ₹/qtl storage
        cost = get_storage_cost("Tomato", 7)
        assert cost == pytest.approx(56.0)


# ── API Tests (async) ─────────────────────────────────────────

@pytest.mark.asyncio
class TestAPI:

    @pytest.fixture
    async def test_client(self):
        from httpx import AsyncClient
        from serving.server import app
        async with AsyncClient(app=app, base_url="http://test") as client:
            yield client

    async def test_health_endpoint(self, test_client):
        resp = await test_client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert "status" in data

    async def test_forecast_unknown_pair(self, test_client):
        resp = await test_client.post("/forecast", json={
            "commodity": "Unobtanium",
            "mandi": "Nowhere",
        })
        assert resp.status_code == 404
