"""
KrishiMitra — End-to-End Integration Tests
==========================================
Tests the full request path:
  Client → Nginx → Spring Boot → ML Engine → LLM Server
  Kafka events, Redis caching, Agmarknet ingestion

Run:
    pytest integration/tests/test_e2e.py -v
    pytest integration/tests/test_e2e.py -v -k "test_auth"  # filter
"""
import json
import time
import pytest
import requests
from datetime import datetime

BASE_URL    = "http://localhost:80"        # nginx entry point
BACKEND_URL = "http://localhost:8080"
ML_URL      = "http://localhost:8003"
LLM_URL     = "http://localhost:8001"

# Shared state across tests
SESSION = requests.Session()
STATE   = {}


# ─────────────────────────────────────────────────────────────
# FIXTURES
# ─────────────────────────────────────────────────────────────

@pytest.fixture(scope="session", autouse=True)
def wait_for_services():
    """Wait for all services to be healthy before running tests."""
    services = {
        "nginx":   f"{BASE_URL}/health",
        "backend": f"{BACKEND_URL}/actuator/health",
        "ml":      f"{ML_URL}/health",
        "llm":     f"{LLM_URL}/health",
    }
    for name, url in services.items():
        for attempt in range(30):
            try:
                r = requests.get(url, timeout=5)
                if r.status_code == 200:
                    print(f"\n  ✓ {name} is healthy")
                    break
            except requests.ConnectionError:
                pass
            if attempt == 29:
                pytest.fail(f"{name} not healthy after 60s: {url}")
            time.sleep(2)


@pytest.fixture(scope="session")
def auth_token():
    """Register and login a test farmer, return JWT."""
    phone = f"9{int(time.time()) % 900000000 + 100000000}"

    # Register
    r = SESSION.post(f"{BASE_URL}/api/v1/auth/register", json={
        "phone":    phone,
        "name":     "Test Farmer",
        "password": "TestPass123",
        "district": "Indore",
        "state":    "Madhya Pradesh",
        "preferredLang": "hi",
    })
    assert r.status_code == 201, f"Registration failed: {r.text}"
    data = r.json()["data"]

    STATE["phone"]        = phone
    STATE["farmer_id"]    = data["farmerId"]
    STATE["access_token"] = data["accessToken"]

    SESSION.headers["Authorization"] = f"Bearer {data['accessToken']}"
    return data["accessToken"]


# ─────────────────────────────────────────────────────────────
# AUTH TESTS
# ─────────────────────────────────────────────────────────────

class TestAuthentication:

    def test_register_creates_farmer(self, auth_token):
        """Registration returns valid JWT with farmer details."""
        assert auth_token is not None
        assert len(auth_token) > 50
        assert STATE.get("farmer_id") is not None

    def test_duplicate_registration_fails(self, auth_token):
        """Re-registering same phone returns 409 Conflict."""
        r = SESSION.post(f"{BASE_URL}/api/v1/auth/register", json={
            "phone": STATE["phone"], "name": "Dup", "password": "pass1234",
            "district": "Indore", "state": "MP",
        })
        assert r.status_code == 409

    def test_login_returns_tokens(self):
        """Login with correct credentials returns access + refresh token."""
        r = requests.post(f"{BASE_URL}/api/v1/auth/login", json={
            "phone": STATE["phone"], "password": "TestPass123",
        })
        assert r.status_code == 200
        data = r.json()["data"]
        assert "accessToken"  in data
        assert "refreshToken" in data
        assert data["expiresInMs"] > 0

    def test_protected_endpoint_without_token_returns_401(self):
        r = requests.get(f"{BASE_URL}/api/v1/farmer/profile")
        assert r.status_code == 401

    def test_farmer_profile_accessible_with_token(self, auth_token):
        r = SESSION.get(f"{BASE_URL}/api/v1/farmer/profile")
        assert r.status_code == 200
        profile = r.json()["data"]
        assert profile["name"]     == "Test Farmer"
        assert profile["district"] == "Indore"


# ─────────────────────────────────────────────────────────────
# PRICE API TESTS
# ─────────────────────────────────────────────────────────────

class TestPriceAPI:

    def test_live_prices_endpoint_is_public(self):
        """Live prices should be accessible without auth."""
        r = requests.get(f"{BASE_URL}/api/v1/prices/live", params={"commodity": "Wheat"})
        assert r.status_code == 200
        prices = r.json()["data"]
        assert isinstance(prices, list)

    def test_live_prices_have_required_fields(self):
        r = requests.get(f"{BASE_URL}/api/v1/prices/live", params={"commodity": "Wheat"})
        if r.json()["data"]:
            p = r.json()["data"][0]
            required = ["commodity", "mandi", "modalPrice", "priceDate", "trendDirection"]
            for field in required:
                assert field in p, f"Missing field: {field}"

    def test_forecast_requires_auth(self):
        r = requests.get(f"{BASE_URL}/api/v1/prices/forecast",
                         params={"commodity": "Wheat", "mandi": "Indore"})
        assert r.status_code == 401

    def test_forecast_returns_predictions(self, auth_token):
        """Forecast endpoint returns ML predictions or graceful fallback."""
        r = SESSION.get(f"{BASE_URL}/api/v1/prices/forecast",
                        params={"commodity": "Wheat", "mandi": "Indore"})
        assert r.status_code == 200
        fc = r.json()["data"]
        assert "sellDecision" in fc
        assert fc["sellDecision"] in ("SELL_NOW", "WAIT_N_DAYS", "HOLD")
        assert "pointForecast" in fc
        print(f"\n  Forecast: {fc['sellDecision']} (confidence={fc.get('confidence', 'N/A')})")

    def test_forecast_caching(self, auth_token):
        """Second call should be faster (Redis cache hit)."""
        t1 = time.perf_counter()
        SESSION.get(f"{BASE_URL}/api/v1/prices/forecast", params={"commodity": "Wheat", "mandi": "Indore"})
        dur1 = time.perf_counter() - t1

        t2 = time.perf_counter()
        r = SESSION.get(f"{BASE_URL}/api/v1/prices/forecast", params={"commodity": "Wheat", "mandi": "Indore"})
        dur2 = time.perf_counter() - t2

        fc = r.json()["data"]
        print(f"\n  First call: {dur1:.2f}s | Second (cached): {dur2:.2f}s | fromCache={fc.get('fromCache')}")
        # Cache should be significantly faster
        if dur1 > 0.5:    # only assert if first call was meaningfully slow
            assert dur2 < dur1 * 0.5 or fc.get("fromCache"), "Cache should speed up second request"

    def test_mandi_rank_returns_sorted_list(self, auth_token):
        r = SESSION.get(f"{BASE_URL}/api/v1/prices/mandis/rank",
                        params={"commodity": "Wheat", "lat": 22.72, "lng": 75.86, "topN": 3})
        assert r.status_code == 200
        mandis = r.json()["data"]
        assert len(mandis) >= 1
        # Should be sorted by net price descending
        prices = [m["netPrice"] for m in mandis]
        assert prices == sorted(prices, reverse=True), "Mandis should be sorted by net price"


# ─────────────────────────────────────────────────────────────
# SELL ADVISOR TESTS
# ─────────────────────────────────────────────────────────────

class TestSellAdvisor:

    def test_sell_advice_returns_decision(self, auth_token):
        r = SESSION.post(f"{BASE_URL}/api/v1/sell/advice", json={
            "commodity":       "Wheat",
            "mandi":           "Indore",
            "quantityQuintal": 8.0,
            "storageAvailable": True,
        })
        assert r.status_code == 200
        advice = r.json()["data"]
        assert advice["sellDecision"] in ("SELL_NOW", "WAIT_N_DAYS", "HOLD")
        assert "reasoning" in advice
        assert advice["currentPrice"] > 0
        print(f"\n  Sell advice: {advice['sellDecision']} | gain=₹{advice.get('profitGainPerQtl','N/A')}/qtl")

    def test_profit_simulation_returns_scenario_chart(self, auth_token):
        r = SESSION.post(f"{BASE_URL}/api/v1/sell/simulate", json={
            "commodity":       "Wheat",
            "mandi":           "Indore",
            "quantityQuintal": 10.0,
            "waitDays":        7,
        })
        assert r.status_code == 200
        sim = r.json()["data"]
        assert "scenarioChart" in sim
        assert len(sim["scenarioChart"]) >= 1
        assert sim["currentPrice"] > 0


# ─────────────────────────────────────────────────────────────
# AI CHAT TESTS
# ─────────────────────────────────────────────────────────────

class TestAiChat:

    def test_chat_returns_response(self, auth_token):
        r = SESSION.post(f"{BASE_URL}/api/v1/ai/chat", json={
            "message":  "What is the current wheat price in Indore?",
            "language": "en",
        })
        assert r.status_code == 200
        resp = r.json()["data"]
        assert resp["content"] is not None
        assert len(resp["content"]) > 10
        assert resp["modelUsed"] in ("custom", "openai", "claude")
        print(f"\n  Model used: {resp['modelUsed']} | latency={resp['latencyMs']}ms")

    def test_hindi_chat(self, auth_token):
        r = SESSION.post(f"{BASE_URL}/api/v1/ai/chat", json={
            "message":  "गेहूं अभी बेचूं या रुकूं?",
            "language": "hi",
        })
        assert r.status_code == 200
        resp = r.json()["data"]
        assert len(resp["content"]) > 5
        print(f"\n  Hindi response: {resp['content'][:80]}...")

    def test_chat_session_persists(self, auth_token):
        """Second message in same session should have context."""
        r1 = SESSION.post(f"{BASE_URL}/api/v1/ai/chat", json={
            "message": "I have 8 quintals of wheat.",
            "language": "en",
        })
        session_id = r1.json()["data"]["sessionId"]

        r2 = SESSION.post(f"{BASE_URL}/api/v1/ai/chat", json={
            "sessionId": session_id,
            "message":   "Should I sell it now?",
            "language":  "en",
        })
        assert r2.status_code == 200
        assert r2.json()["data"]["sessionId"] == session_id


# ─────────────────────────────────────────────────────────────
# ML ENGINE DIRECT TESTS
# ─────────────────────────────────────────────────────────────

class TestMlEngine:

    def test_ml_health(self):
        r = requests.get(f"{ML_URL}/health")
        assert r.status_code == 200

    def test_ml_forecast_endpoint(self):
        r = requests.post(f"{ML_URL}/forecast", json={
            "commodity":   "Wheat",
            "mandi":       "Indore",
            "horizon":     30,
            "forceFresh":  False,
        })
        assert r.status_code in (200, 404)  # 404 if no model loaded yet
        if r.status_code == 200:
            fc = r.json()
            assert "sellDecision"  in fc
            assert "pointForecast" in fc


# ─────────────────────────────────────────────────────────────
# LLM SERVER DIRECT TESTS
# ─────────────────────────────────────────────────────────────

class TestLlmServer:

    def test_llm_health(self):
        r = requests.get(f"{LLM_URL}/health")
        assert r.status_code == 200
        data = r.json()
        assert "models" in data
        print(f"\n  LLM model status: {data['models']}")

    def test_llm_chat_with_fallback(self):
        """Should return a response even if custom model is down."""
        r = requests.post(f"{LLM_URL}/v1/chat", json={
            "messages":    [{"role": "user", "content": "What is wheat MSP 2025?"}],
            "language":    "en",
            "maxTokens":   100,
            "temperature": 0.3,
        })
        assert r.status_code == 200
        resp = r.json()
        assert resp["content"] is not None
        assert resp["modelUsed"] in ("custom", "openai", "claude")


# ─────────────────────────────────────────────────────────────
# ALERTS TESTS
# ─────────────────────────────────────────────────────────────

class TestAlerts:

    def test_alerts_list_is_paginated(self, auth_token):
        r = SESSION.get(f"{BASE_URL}/api/v1/alerts")
        assert r.status_code == 200
        data = r.json()["data"]
        assert "alerts"       in data
        assert "totalUnread"  in data
        assert "page"         in data

    def test_mark_all_read(self, auth_token):
        r = SESSION.put(f"{BASE_URL}/api/v1/alerts/read-all")
        assert r.status_code == 200


# ─────────────────────────────────────────────────────────────
# PERFORMANCE SMOKE TESTS
# ─────────────────────────────────────────────────────────────

class TestPerformance:

    def test_live_prices_responds_under_500ms(self):
        t = time.perf_counter()
        r = requests.get(f"{BASE_URL}/api/v1/prices/live", params={"commodity": "Wheat"})
        dur = time.perf_counter() - t
        assert r.status_code == 200
        assert dur < 0.5, f"Live prices took {dur:.2f}s — should be <500ms"

    def test_health_check_responds_under_100ms(self):
        t = time.perf_counter()
        r = requests.get(f"{BACKEND_URL}/actuator/health")
        dur = time.perf_counter() - t
        assert r.status_code == 200
        assert dur < 0.1, f"Health check took {dur:.2f}s — should be <100ms"

    def test_concurrent_requests_handled(self, auth_token):
        """10 concurrent requests should all succeed."""
        import concurrent.futures
        def call():
            return SESSION.get(f"{BASE_URL}/api/v1/prices/live", params={"commodity": "Wheat"})

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            futures = [pool.submit(call) for _ in range(10)]
            results = [f.result() for f in concurrent.futures.as_completed(futures)]

        statuses = [r.status_code for r in results]
        success  = sum(1 for s in statuses if s == 200)
        print(f"\n  Concurrent: {success}/10 succeeded")
        assert success >= 8, f"Only {success}/10 concurrent requests succeeded"
