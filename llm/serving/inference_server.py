"""
KrishiMitra LLM — Production Inference Server
==============================================
Architecture:
  - FastAPI HTTP server (port 8001)
  - Primary: custom model served via vLLM (batched, async)
  - Fallback 1: OpenAI GPT-4o (if custom model confidence < threshold)
  - Fallback 2: Anthropic Claude (if OpenAI fails / timeout)
  - Context injection: live prices + farmer profile injected per request
  - Prometheus metrics endpoint at /metrics

Start:
    uvicorn serving.inference_server:app --host 0.0.0.0 --port 8001 --workers 1
"""

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager
from enum import Enum
from typing import Optional

import httpx
import yaml
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response

log = logging.getLogger("inference_server")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")

with open("configs/config.yaml") as f:
    CFG = yaml.safe_load(f)

INF_CFG = CFG["inference"]

# ─────────────────────────────────────────────────────────────
# PROMETHEUS METRICS
# ─────────────────────────────────────────────────────────────

REQUESTS_TOTAL = Counter(
    "krishimitra_requests_total", "Total LLM requests", ["model", "status"]
)
LATENCY_HIST = Histogram(
    "krishimitra_latency_seconds", "End-to-end latency", ["model"],
    buckets=[0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0],
)
FALLBACK_COUNTER = Counter(
    "krishimitra_fallbacks_total", "Times fallback was triggered", ["from_model", "to_model"]
)


# ─────────────────────────────────────────────────────────────
# SCHEMAS
# ─────────────────────────────────────────────────────────────

class Language(str, Enum):
    en = "en"
    hi = "hi"
    mr = "mr"
    gu = "gu"
    pa = "pa"


class FarmerContext(BaseModel):
    """Optional context injected into every request."""
    location:       Optional[str] = None      # "Indore, MP"
    crops:          Optional[list[str]] = None # ["wheat", "soybean"]
    live_prices:    Optional[dict] = None      # {"wheat": {"indore": 2180}}
    weather_brief:  Optional[str] = None       # "Clear, 34°C"
    farmer_name:    Optional[str] = None


class ChatMessage(BaseModel):
    role:    str   # "user" | "assistant" | "system"
    content: str


class InferenceRequest(BaseModel):
    messages:   list[ChatMessage]
    language:   Language = Language.en
    context:    Optional[FarmerContext] = None
    max_tokens: int = Field(default=512, le=1024)
    temperature: float = Field(default=0.3, ge=0.0, le=1.0)
    # Routing hints
    force_model: Optional[str] = None   # "custom" | "openai" | "claude"


class ModelUsed(str, Enum):
    custom  = "custom"
    openai  = "openai"
    claude  = "claude"


class InferenceResponse(BaseModel):
    content:         str
    model_used:      ModelUsed
    latency_ms:      float
    confidence:      Optional[float] = None
    fallback_reason: Optional[str]   = None
    tokens_used:     Optional[int]   = None


# ─────────────────────────────────────────────────────────────
# CONTEXT BUILDER
# ─────────────────────────────────────────────────────────────

SYSTEM_BASE = (
    "You are KrishiMitra AI, an expert agricultural advisor for Indian farmers. "
    "Help with mandi prices, sell timing, crop planning, and storage decisions. "
    "Always use ₹/quintal. Be concise and actionable."
)

def build_system_prompt(context: Optional[FarmerContext], language: Language) -> str:
    parts = [SYSTEM_BASE]

    if context:
        if context.farmer_name:
            parts.append(f"Farmer name: {context.farmer_name}.")
        if context.location:
            parts.append(f"Farmer location: {context.location}.")
        if context.crops:
            parts.append(f"Farmer's crops: {', '.join(context.crops)}.")
        if context.weather_brief:
            parts.append(f"Current weather: {context.weather_brief}.")
        if context.live_prices:
            price_lines = []
            for crop, mandis in context.live_prices.items():
                for mandi, price in mandis.items():
                    price_lines.append(f"{crop} at {mandi}: ₹{price}/qtl")
            if price_lines:
                parts.append("Live prices today:\n" + "\n".join(price_lines))

    if language != Language.en:
        lang_names = {"hi": "Hindi", "mr": "Marathi", "gu": "Gujarati", "pa": "Punjabi"}
        parts.append(f"Respond in {lang_names.get(language, 'the farmer\'s language')}.")

    return "\n".join(parts)


def inject_system(messages: list[ChatMessage], system: str) -> list[dict]:
    """Prepend or replace system message in the messages list."""
    out = [{"role": "system", "content": system}]
    for m in messages:
        if m.role != "system":
            out.append({"role": m.role, "content": m.content})
    return out


# ─────────────────────────────────────────────────────────────
# CUSTOM MODEL CLIENT (vLLM)
# ─────────────────────────────────────────────────────────────

class CustomModelClient:
    """
    Calls the vLLM OpenAI-compatible server running the custom KrishiMitra model.
    Start vLLM with:
        python -m vllm.entrypoints.openai.api_server \
            --model outputs/krishimitra-llm-v1-merged \
            --host 0.0.0.0 --port 8002 \
            --dtype bfloat16 --max-model-len 2048
    """

    VLLM_URL      = os.environ.get("VLLM_URL", "http://localhost:8002")
    TIMEOUT       = INF_CFG.get("latency_timeout_ms", 3000) / 1000
    CONF_THRESHOLD = INF_CFG.get("confidence_threshold", 0.72)

    def __init__(self):
        self.http = httpx.AsyncClient(timeout=self.TIMEOUT)
        self.model_name = INF_CFG.get("merged_model_dir", "krishimitra-llm")

    async def generate(
        self,
        messages: list[dict],
        max_tokens: int = 512,
        temperature: float = 0.3,
    ) -> tuple[str, float]:
        """
        Returns (response_text, confidence_score).
        Confidence is estimated from the logprobs of the first token.
        """
        payload = {
            "model":       self.model_name,
            "messages":    messages,
            "max_tokens":  max_tokens,
            "temperature": temperature,
            "logprobs":    True,
            "top_logprobs": 5,
        }

        resp = await self.http.post(
            f"{self.VLLM_URL}/v1/chat/completions",
            json=payload,
        )
        resp.raise_for_status()
        data = resp.json()

        text = data["choices"][0]["message"]["content"].strip()

        # Estimate confidence: mean prob of top-1 logprob across first 5 tokens
        try:
            lp_list = data["choices"][0].get("logprobs", {}).get("content", [])[:5]
            if lp_list:
                import math
                probs = [math.exp(lp["logprob"]) for lp in lp_list if lp.get("logprob") is not None]
                confidence = float(sum(probs) / len(probs)) if probs else 0.5
            else:
                confidence = 0.5
        except Exception:
            confidence = 0.5

        return text, confidence

    async def health(self) -> bool:
        try:
            r = await self.http.get(f"{self.VLLM_URL}/health", timeout=2)
            return r.status_code == 200
        except Exception:
            return False


# ─────────────────────────────────────────────────────────────
# OPENAI FALLBACK CLIENT
# ─────────────────────────────────────────────────────────────

class OpenAIFallbackClient:
    MODEL = "gpt-4o"

    def __init__(self):
        from openai import AsyncOpenAI
        self.client = AsyncOpenAI(api_key=os.environ.get("OPENAI_API_KEY"))

    async def generate(
        self,
        messages: list[dict],
        max_tokens: int = 512,
        temperature: float = 0.3,
    ) -> tuple[str, int]:
        """Returns (text, tokens_used)."""
        resp = await self.client.chat.completions.create(
            model=self.MODEL,
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
        )
        text   = resp.choices[0].message.content.strip()
        tokens = resp.usage.total_tokens
        return text, tokens

    async def health(self) -> bool:
        try:
            await self.client.models.list()
            return True
        except Exception:
            return False


# ─────────────────────────────────────────────────────────────
# CLAUDE FALLBACK CLIENT
# ─────────────────────────────────────────────────────────────

class ClaudeFallbackClient:
    MODEL = "claude-sonnet-4-6"

    def __init__(self):
        import anthropic
        self.client = anthropic.AsyncAnthropic(api_key=os.environ.get("ANTHROPIC_API_KEY"))

    async def generate(
        self,
        messages: list[dict],
        max_tokens: int = 512,
        temperature: float = 0.3,
    ) -> tuple[str, int]:
        system = next((m["content"] for m in messages if m["role"] == "system"), "")
        user_msgs = [{"role": m["role"], "content": m["content"]}
                     for m in messages if m["role"] != "system"]

        resp = await self.client.messages.create(
            model=self.MODEL,
            max_tokens=max_tokens,
            system=system,
            messages=user_msgs,
        )
        text   = resp.content[0].text.strip()
        tokens = resp.usage.input_tokens + resp.usage.output_tokens
        return text, tokens

    async def health(self) -> bool:
        try:
            await self.client.messages.create(
                model=self.MODEL, max_tokens=10,
                messages=[{"role": "user", "content": "ping"}]
            )
            return True
        except Exception:
            return False


# ─────────────────────────────────────────────────────────────
# LLM ROUTER
# ─────────────────────────────────────────────────────────────

class LLMRouter:
    """
    Routing logic:
      1. Try custom KrishiMitra model (vLLM)
         - if confidence < threshold OR latency timeout → escalate
      2. Try OpenAI GPT-4o
         - if API error / rate-limit → escalate
      3. Claude (guaranteed fallback — should always succeed)
    """

    def __init__(self):
        self.custom  = CustomModelClient()
        self.openai  = OpenAIFallbackClient()
        self.claude  = ClaudeFallbackClient()

    async def route(
        self,
        messages: list[dict],
        max_tokens: int,
        temperature: float,
        force_model: Optional[str] = None,
    ) -> InferenceResponse:

        start = time.perf_counter()

        # Force override
        if force_model == "openai":
            return await self._call_openai(messages, max_tokens, temperature, start)
        if force_model == "claude":
            return await self._call_claude(messages, max_tokens, temperature, start, reason="forced")

        # Stage 1: custom model
        custom_healthy = await self.custom.health()
        if custom_healthy:
            try:
                text, conf = await self.custom.generate(messages, max_tokens, temperature)
                latency    = (time.perf_counter() - start) * 1000

                REQUESTS_TOTAL.labels(model="custom", status="success").inc()
                LATENCY_HIST.labels(model="custom").observe(latency / 1000)

                if conf >= INF_CFG.get("confidence_threshold", 0.72):
                    return InferenceResponse(
                        content=text,
                        model_used=ModelUsed.custom,
                        latency_ms=round(latency, 1),
                        confidence=round(conf, 3),
                    )
                else:
                    FALLBACK_COUNTER.labels(from_model="custom", to_model="openai").inc()
                    log.info("Custom model confidence %.2f below threshold — escalating", conf)

            except (httpx.TimeoutException, httpx.HTTPStatusError) as e:
                REQUESTS_TOTAL.labels(model="custom", status="error").inc()
                FALLBACK_COUNTER.labels(from_model="custom", to_model="openai").inc()
                log.warning("Custom model error: %s — falling back to OpenAI", e)
        else:
            log.warning("Custom model unhealthy — routing to OpenAI")
            FALLBACK_COUNTER.labels(from_model="custom", to_model="openai").inc()

        # Stage 2: OpenAI
        return await self._call_openai(messages, max_tokens, temperature, start)

    async def _call_openai(self, messages, max_tokens, temperature, start):
        try:
            text, tokens = await self.openai.generate(messages, max_tokens, temperature)
            latency = (time.perf_counter() - start) * 1000
            REQUESTS_TOTAL.labels(model="openai", status="success").inc()
            LATENCY_HIST.labels(model="openai").observe(latency / 1000)
            return InferenceResponse(
                content=text,
                model_used=ModelUsed.openai,
                latency_ms=round(latency, 1),
                tokens_used=tokens,
            )
        except Exception as e:
            REQUESTS_TOTAL.labels(model="openai", status="error").inc()
            FALLBACK_COUNTER.labels(from_model="openai", to_model="claude").inc()
            log.warning("OpenAI error: %s — falling back to Claude", e)
            return await self._call_claude(messages, max_tokens, temperature, start, reason=str(e))

    async def _call_claude(self, messages, max_tokens, temperature, start, reason=""):
        try:
            text, tokens = await self.claude.generate(messages, max_tokens, temperature)
            latency = (time.perf_counter() - start) * 1000
            REQUESTS_TOTAL.labels(model="claude", status="success").inc()
            LATENCY_HIST.labels(model="claude").observe(latency / 1000)
            return InferenceResponse(
                content=text,
                model_used=ModelUsed.claude,
                latency_ms=round(latency, 1),
                tokens_used=tokens,
                fallback_reason=reason,
            )
        except Exception as e:
            REQUESTS_TOTAL.labels(model="claude", status="error").inc()
            log.error("All models failed: %s", e)
            raise HTTPException(status_code=503, detail="All LLM providers unavailable")


# ─────────────────────────────────────────────────────────────
# FASTAPI APP
# ─────────────────────────────────────────────────────────────

router: Optional[LLMRouter] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global router
    log.info("Starting KrishiMitra inference server")
    router = LLMRouter()
    log.info("LLM router ready (custom → OpenAI → Claude)")
    yield
    log.info("Shutting down")

app = FastAPI(
    title="KrishiMitra LLM Inference API",
    version="1.0.0",
    description="Custom agri LLM with OpenAI/Claude fallback",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.post("/v1/chat", response_model=InferenceResponse, tags=["Inference"])
async def chat(req: InferenceRequest) -> InferenceResponse:
    """
    Main inference endpoint.
    Automatically routes custom → OpenAI → Claude based on availability + confidence.
    """
    system  = build_system_prompt(req.context, req.language)
    messages = inject_system(req.messages, system)

    return await router.route(
        messages=messages,
        max_tokens=req.max_tokens,
        temperature=req.temperature,
        force_model=req.force_model,
    )


@app.get("/health", tags=["Ops"])
async def health():
    custom_ok = await router.custom.health() if router else False
    return {
        "status": "ok",
        "models": {
            "custom":  "up" if custom_ok else "down",
            "openai":  "available",
            "claude":  "available",
        },
    }


@app.get("/metrics", tags=["Ops"])
async def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/", tags=["Ops"])
async def root():
    return {"service": "KrishiMitra LLM", "version": "1.0.0"}
