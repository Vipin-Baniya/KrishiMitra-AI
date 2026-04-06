# KrishiMitra AI — Complete Platform

Farmer Decision Intelligence Platform for India.
Answers: **What crop to grow? When to sell? At what price?**

---

## Deployment Guide

For a full step-by-step free deployment guide (local, Oracle Cloud, or Render), see **[DEPLOYMENT.md](DEPLOYMENT.md)**.

---

## Quick Start (everything in one command)

```bash
# 1. Clone and set env vars
cp .env.example .env
# Edit .env: JWT_SECRET, OPENAI_API_KEY, ANTHROPIC_API_KEY, DATAGOV_API_KEY

# 2. Start the full stack
cd integration
docker compose -f docker-compose.full.yml up -d

# 3. Run integration tests
pip install requests pytest
pytest integration/tests/test_e2e.py -v

# 4. Open the app
open http://localhost        # Web frontend
open http://localhost:3001   # Grafana (admin / krishimitra)
open http://localhost:9090   # Prometheus
```



---

## Architecture

```
Farmer (Web / Mobile)
        ↓
   Nginx :80/:443
   ├── /api/*   → Spring Boot :8080
   │              ├── PostgreSQL :5432
   │              ├── Redis :6379
   │              ├── Kafka :9092
   │              ├── ML Engine :8003  (ARIMA + LSTM + XGBoost)
   │              └── LLM Server :8001 (Custom → OpenAI → Claude)
   └── /        → React Frontend :3000

Monitoring:
   Prometheus :9090 → Grafana :3001
   Loki :3100 (logs) + Tempo :3200 (traces)
```

---

## Components Built

| Component | Tech | Lines |
|-----------|------|-------|
| System Architecture | SVG interactive diagrams | — |
| Custom LLM Pipeline | Mistral-7B QLoRA, vLLM, FastAPI | 2,728 |
| ML Price Engine | ARIMA + LSTM + XGBoost ensemble | 3,692 |
| Spring Boot Backend | Spring Boot 3.3, Kafka, Redis, JWT | 2,531 |
| React Frontend | React 18, Recharts, Zustand, Vite | 2,264 |
| Admin + Monitoring | Prometheus, Grafana, Loki, Tempo | 2,219 |
| Integration + CI/CD | Docker, Helm, GitHub Actions | ~1,800 |
| React Native Mobile | iOS + Android, full feature parity | ~1,800 |
| **Total** | **57+ files** | **~17,000+ lines** |

---

## Environment Variables

```bash
# Required
JWT_SECRET=your-256-bit-secret-key-here

# LLM fallbacks (custom model runs locally)
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# Data
DATAGOV_API_KEY=your-datagov-key

# Infrastructure
POSTGRES_PASSWORD=strong-password
REDIS_PASSWORD=strong-password

# Monitoring
GRAFANA_PASSWORD=admin-password
SLACK_WEBHOOK_URL=https://hooks.slack.com/...
PAGERDUTY_SERVICE_KEY=...
```

---

## CI/CD Pipeline

Push to `main` → tests → build Docker images → deploy staging
Tag `v1.2.3`   → staging → manual gate → deploy production

```bash
# Trigger production deploy
git tag v1.0.0 && git push --tags
```

---

## Mobile App (React Native)

```bash
cd mobile
npm install
npx pod-install ios          # iOS only

npm run android              # Android emulator
npm run ios                  # iOS simulator

# Production build
npm run build:android        # → android/app/release/app-release.apk
npm run build:ios            # → Xcode archive
```

---

## Monitoring

```bash
# Start monitoring stack
cd krishimitra-monitoring
./scripts/setup.sh start

# Check all services
./scripts/setup.sh status

# Import Grafana dashboards
./scripts/setup.sh import

# Fire a test alert
./scripts/setup.sh test-alert
```

Dashboards:
- **System Overview** — request rate, p95 latency, error rate, service health
- **Business KPIs** — DAU, new registrations, sell advisories, AI chat volume
- **ML Engine** — LLM routing (custom %, OpenAI %, Claude %), forecast latency
- **Infrastructure** — CPU, memory, disk, Redis, Kafka lag

---

## API Reference

Base URL: `https://krishimitra.in/api/v1`

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/auth/register` | No | Register farmer |
| POST | `/auth/login` | No | Login |
| GET | `/prices/live?commodity=Wheat` | No | Live mandi prices |
| GET | `/prices/forecast?commodity=Wheat&mandi=Indore` | Yes | 30-day ML forecast |
| GET | `/prices/mandis/rank?commodity=Wheat&lat=22.72&lng=75.86` | Yes | Best mandis by net profit |
| POST | `/sell/advice` | Yes | SELL/WAIT/HOLD decision |
| POST | `/sell/simulate` | Yes | Profit what-if simulator |
| POST | `/ai/chat` | Yes | Conversational AI (Hindi + English) |
| GET | `/alerts` | Yes | Smart alerts |
| POST | `/crops/recommend` | Yes | AI crop recommendations |

Full Swagger UI: `https://krishimitra.in/swagger-ui.html`

---

## Running Tests

```bash
# Backend unit + integration tests
cd krishimitra-backend
./mvnw test -Dspring.profiles.active=test

# ML engine tests
cd krishimitra-ml
pytest tests/ -v

# End-to-end integration (requires full stack running)
pytest integration/tests/test_e2e.py -v

# Frontend lint
cd krishimitra-frontend
npm run lint && npm run build
```
