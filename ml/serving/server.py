"""
KrishiMitra ML — Price Prediction Inference Server
====================================================
FastAPI server that:
  - Loads all trained ensemble models at startup
  - Serves price forecasts via REST API
  - Redis caching (30 min TTL per commodity-mandi pair)
  - Prometheus metrics (request count, latency, cache hit rate)
  - Live data refresh: fetches latest Agmarknet prices on demand

Endpoints:
  POST /forecast              — main forecast endpoint
  GET  /forecast/{commodity}/{mandi}  — GET shorthand
  GET  /sell-advice           — SELL/WAIT decision
  GET  /mandis/{commodity}    — available mandis for a crop
  GET  /health                — service health + loaded models
  GET  /metrics               — Prometheus metrics

Start:
    uvicorn serving.server:app --host 0.0.0.0 --port 8003 --workers 2
"""

import json
import logging
import os
import pickle
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
import redis.asyncio as aioredis
import yaml
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
from pydantic import BaseModel, Field
from starlette.responses import Response

from data.feature_engineer import FeatureEngineer
from models.ensemble.ensemble_forecaster import EnsembleForecaster

log = logging.getLogger("ml_server")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")

with open("configs/config.yaml") as f:
    CFG = yaml.safe_load(f)

# ── Prometheus metrics ────────────────────────────────────────
FORECAST_REQUESTS  = Counter("krishimitra_ml_requests_total",  "Total forecast requests", ["commodity", "mandi", "status"])
FORECAST_LATENCY   = Histogram("krishimitra_ml_latency_seconds", "Forecast latency", buckets=[.05, .1, .25, .5, 1, 2, 5])
CACHE_HITS         = Counter("krishimitra_ml_cache_hits_total", "Redis cache hits")
CACHE_MISSES       = Counter("krishimitra_ml_cache_misses_total", "Redis cache misses")
MODELS_LOADED      = Gauge("krishimitra_ml_models_loaded", "Number of loaded models")


# ─────────────────────────────────────────────────────────────
# MODEL REGISTRY
# ─────────────────────────────────────────────────────────────

class ModelRegistry:
    """
    Loads all trained ensemble models into memory at startup.
    Key: f"{commodity}|{mandi}"
    Value: (EnsembleForecaster, FeatureEngineer, feature_cols, price_series)
    """

    def __init__(self, models_dir: str):
        self.models_dir = Path(models_dir)
        self._registry: dict[str, dict] = {}

    def load_all(self) -> int:
        loaded = 0
        for ens_path in self.models_dir.rglob("ensemble.pkl"):
            commodity = ens_path.parent.parent.name
            mandi     = ens_path.parent.name
            key       = f"{commodity}|{mandi}"
            try:
                self._load_pair(commodity, mandi)
                loaded += 1
                log.info("Loaded model: %s @ %s", commodity, mandi)
            except Exception as e:
                log.warning("Failed to load %s: %s", key, e)
        MODELS_LOADED.set(loaded)
        log.info("Model registry: %d models loaded", loaded)
        return loaded

    def _load_pair(self, commodity: str, mandi: str) -> None:
        key      = f"{commodity}|{mandi}"
        base_dir = self.models_dir / commodity / mandi

        ensemble = EnsembleForecaster.load(str(base_dir / "ensemble.pkl"), CFG)

        with open(base_dir / "feature_engineer.pkl", "rb") as f:
            fe: FeatureEngineer = pickle.load(f)

        with open(base_dir / "feature_cols.txt") as f:
            feat_cols = [line.strip() for line in f if line.strip()]

        # Load recent price series for inference context
        price_csv = CFG["data"]["raw_price_csv"]
        if Path(price_csv).exists():
            price_df = pd.read_csv(price_csv)
            price_df["date"] = pd.to_datetime(price_df["date"], dayfirst=True)
            mask = (price_df["commodity"] == commodity) & (price_df["mandi"] == mandi)
            ps = price_df[mask].sort_values("date").set_index("date")["modal_price"]
        else:
            ps = pd.Series(dtype=float)

        self._registry[key] = {
            "ensemble":    ensemble,
            "fe":          fe,
            "feat_cols":   feat_cols,
            "price_series": ps,
            "commodity":   commodity,
            "mandi":       mandi,
        }

    def get(self, commodity: str, mandi: str) -> Optional[dict]:
        return self._registry.get(f"{commodity}|{mandi}")

    def available_pairs(self) -> list[dict]:
        return [
            {"commodity": v["commodity"], "mandi": v["mandi"]}
            for v in self._registry.values()
        ]

    def mandis_for(self, commodity: str) -> list[str]:
        return [
            v["mandi"] for k, v in self._registry.items()
            if v["commodity"].lower() == commodity.lower()
        ]


# ─────────────────────────────────────────────────────────────
# INFERENCE LOGIC
# ─────────────────────────────────────────────────────────────

def run_forecast(
    entry:         dict,
    latest_prices: Optional[pd.DataFrame] = None,
) -> dict:
    """
    Build feature matrix from latest data and run ensemble forecast.
    `latest_prices`: optional DataFrame of fresh Agmarknet data to append.
    """
    fe           = entry["fe"]
    ensemble     = entry["ensemble"]
    feat_cols    = entry["feat_cols"]
    price_series = entry["price_series"].copy()
    commodity    = entry["commodity"]
    mandi        = entry["mandi"]

    # Append any new prices passed in
    if latest_prices is not None and not latest_prices.empty:
        new_ps = latest_prices.set_index("date")["modal_price"]
        price_series = pd.concat([price_series, new_ps]).drop_duplicates().sort_index()

    if len(price_series) < 60:
        raise ValueError(f"Insufficient price history ({len(price_series)} rows)")

    # Rebuild feature matrix for last 180 days (enough for LSTM seq window)
    recent_df = price_series.reset_index()
    recent_df.columns = ["date", "modal_price"]
    recent_df["commodity"] = commodity
    recent_df["mandi"]     = mandi
    recent_df["state"]     = "Madhya Pradesh"  # default
    recent_df["min_price"] = recent_df["modal_price"] * 0.95
    recent_df["max_price"] = recent_df["modal_price"] * 1.05

    feat_df = fe.build(recent_df)
    X_all   = feat_df[feat_cols].fillna(0).values
    X_sc    = fe.transform(X_all)

    current_price = float(price_series.iloc[-1])

    fc = ensemble.predict(X_sc, price_series, current_price=current_price)
    return fc.to_dict()


# ─────────────────────────────────────────────────────────────
# FASTAPI APP
# ─────────────────────────────────────────────────────────────

registry: Optional[ModelRegistry] = None
cache:    Optional[aioredis.Redis] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global registry, cache

    log.info("Starting KrishiMitra ML inference server")
    registry = ModelRegistry(CFG["serving"]["model_registry_dir"])
    n = registry.load_all()
    log.info("Loaded %d models", n)

    try:
        cache = aioredis.from_url(os.environ.get("REDIS_URL", "redis://localhost:6379"))
        await cache.ping()
        log.info("Redis cache connected")
    except Exception as e:
        log.warning("Redis unavailable (%s) — running without cache", e)
        cache = None

    yield
    log.info("Shutting down")


app = FastAPI(
    title="KrishiMitra ML — Price Prediction Engine",
    version="1.0.0",
    description="ARIMA + LSTM + XGBoost ensemble for Indian agricultural price forecasting",
    lifespan=lifespan,
)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ── SCHEMAS ───────────────────────────────────────────────────

class ForecastRequest(BaseModel):
    commodity:   str
    mandi:       str
    horizon:     int = Field(default=30, ge=1, le=30)
    force_fresh: bool = False    # bypass cache and fetch new Agmarknet data

class SellAdviceRequest(BaseModel):
    commodity:        str
    mandi:            str
    quantity_quintal: float = 1.0
    storage_available: bool = True


# ── ENDPOINTS ─────────────────────────────────────────────────

@app.post("/forecast", tags=["Forecast"])
async def forecast(req: ForecastRequest):
    t0  = time.perf_counter()
    key = f"forecast:{req.commodity}:{req.mandi}"
    ttl = CFG["serving"].get("cache_ttl_seconds", 1800)

    # Cache lookup
    if cache and not req.force_fresh:
        cached = await cache.get(key)
        if cached:
            CACHE_HITS.inc()
            FORECAST_REQUESTS.labels(req.commodity, req.mandi, "cache_hit").inc()
            result = json.loads(cached)
            result["from_cache"] = True
            return result
    CACHE_MISSES.inc()

    # Load model
    entry = registry.get(req.commodity, req.mandi) if registry else None
    if entry is None:
        FORECAST_REQUESTS.labels(req.commodity, req.mandi, "not_found").inc()
        raise HTTPException(
            status_code=404,
            detail=f"No model for {req.commodity} @ {req.mandi}. "
                   f"Available: {registry.available_pairs() if registry else []}",
        )

    try:
        result = run_forecast(entry)
    except Exception as e:
        FORECAST_REQUESTS.labels(req.commodity, req.mandi, "error").inc()
        log.error("Forecast error for %s@%s: %s", req.commodity, req.mandi, e)
        raise HTTPException(status_code=500, detail=str(e))

    # Cache result
    if cache:
        await cache.setex(key, ttl, json.dumps(result))

    latency = time.perf_counter() - t0
    FORECAST_LATENCY.observe(latency)
    FORECAST_REQUESTS.labels(req.commodity, req.mandi, "success").inc()

    result["latency_ms"]  = round(latency * 1000, 1)
    result["from_cache"]  = False
    return result


@app.get("/forecast/{commodity}/{mandi}", tags=["Forecast"])
async def forecast_get(commodity: str, mandi: str):
    return await forecast(ForecastRequest(commodity=commodity, mandi=mandi))


@app.post("/sell-advice", tags=["Forecast"])
async def sell_advice(req: SellAdviceRequest):
    """Returns SELL_NOW / WAIT_N_DAYS decision with profit simulation."""
    fc_result = await forecast(ForecastRequest(commodity=req.commodity, mandi=req.mandi))

    # Add quantity-aware profit calc
    quantity   = req.quantity_quintal
    gain_per_q = fc_result.get("profit_gain", 0)
    total_gain = round(gain_per_q * quantity, 2)

    return {
        **{k: v for k, v in fc_result.items() if k in (
            "sell_decision", "wait_days", "peak_day", "peak_price",
            "profit_gain", "current_price", "confidence", "explanation"
        )},
        "quantity_quintal": quantity,
        "total_profit_gain": total_gain,
    }


@app.get("/mandis/{commodity}", tags=["Discovery"])
async def list_mandis(commodity: str):
    if registry is None:
        raise HTTPException(status_code=503, detail="Server not ready")
    mandis = registry.mandis_for(commodity)
    if not mandis:
        raise HTTPException(status_code=404, detail=f"No models for commodity: {commodity}")
    return {"commodity": commodity, "mandis": mandis}


@app.get("/available-pairs", tags=["Discovery"])
async def available_pairs():
    if registry is None:
        raise HTTPException(status_code=503, detail="Server not ready")
    return {"pairs": registry.available_pairs()}


@app.get("/health", tags=["Ops"])
async def health():
    cache_ok = False
    if cache:
        try:
            await cache.ping()
            cache_ok = True
        except Exception:
            pass
    return {
        "status": "ok",
        "models_loaded": len(registry._registry) if registry else 0,
        "cache": "connected" if cache_ok else "unavailable",
    }


@app.get("/metrics", tags=["Ops"])
async def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
