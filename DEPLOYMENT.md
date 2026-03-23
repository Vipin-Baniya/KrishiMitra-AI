# KrishiMitra AI — Free Deployment Guide

A complete, step-by-step guide to deploy the full KrishiMitra AI platform **at zero cost**.

---

## Table of Contents

1. [Overview & Deployment Options](#1-overview--deployment-options)
2. [Prerequisites](#2-prerequisites)
3. [Option A — Local Deployment (Docker Compose)](#3-option-a--local-deployment-docker-compose)
4. [Option B — Oracle Cloud Always-Free VM](#4-option-b--oracle-cloud-always-free-vm)
5. [Option B2 — Azure VM Deployment](#5-option-b2--azure-vm-deployment)
6. [Option C — Render + Supabase + Upstash (Managed Free Tier)](#6-option-c--render--supabase--upstash-managed-free-tier)
7. [Free API Keys](#7-free-api-keys)
8. [Environment Variable Reference](#8-environment-variable-reference)
9. [Monitoring Stack](#9-monitoring-stack)
10. [CI/CD with GitHub Actions](#10-cicd-with-github-actions)
11. [Kubernetes Security Features](#11-kubernetes-security-features)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Overview & Deployment Options

KrishiMitra AI consists of the following services:

| Service | Tech | Port |
|---------|------|------|
| Frontend | React + Vite (Nginx) | 80 / 443 |
| Backend | Spring Boot 3 + Java 21 | 8080 |
| ML Engine | Python FastAPI (ARIMA/LSTM/XGBoost) | 8003 |
| LLM Server | Python FastAPI (Mistral-7B router) | 8001 |
| PostgreSQL | v16 | 5432 |
| Redis | v7 | 6379 |
| Kafka + Zookeeper | Confluent 7.7 | 9092 |
| Prometheus | v2.53 | 9090 |
| Grafana | v11.1 | 3001 |

### Which option is right for you?

| Option | Cost | Effort | Best for |
|--------|------|--------|----------|
| **A — Local Docker Compose** | Free | Low | Development & testing |
| **B — Oracle Cloud Always-Free VM** | Free forever | Medium | Production-grade free hosting |
| **C — Render + Supabase + Upstash** | Free tier | Medium | Simpler cloud deploy (stateless services only) |

> **Recommended free production path → Option B** (Oracle Cloud ARM VM).  
> It is the only always-free cloud option with enough RAM (up to 24 GB) to run the entire stack.

---

## 2. Prerequisites

### All options require

- **Git** — `git --version`
- **Docker ≥ 24** and **Docker Compose ≥ 2.20** — `docker compose version`

  ```bash
  # Install Docker on Ubuntu/Debian
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker $USER   # log out and back in
  ```

- A copy of the repository:

  ```bash
  git clone https://github.com/Vipin-Baniya/KrishiMitra-AI.git
  cd KrishiMitra-AI
  ```

### Minimum hardware

| Mode | CPU | RAM | Disk |
|------|-----|-----|------|
| Full stack (all services) | 4 cores | 8 GB | 20 GB |
| Without LLM server | 2 cores | 4 GB | 10 GB |
| Frontend + Backend only | 1 core | 2 GB | 5 GB |

> **Tip:** The ML Engine and LLM Server are the heaviest services.  
> Omit `krishimitra-llm` from `docker-compose.full.yml` if you are RAM-constrained (under 6 GB).

---

## 3. Option A — Local Deployment (Docker Compose)

This is the fastest way to run everything on your own machine.

### Step 1 — Copy and configure environment variables

```bash
cp .env.example .env
```

Open `.env` and fill in **only the required fields** (the rest are optional):

```bash
# Generate a strong secret:
JWT_SECRET=$(openssl rand -base64 64)
echo "JWT_SECRET=$JWT_SECRET"

# Choose any password — only used locally:
POSTGRES_PASSWORD=local_dev_password
REDIS_PASSWORD=local_dev_password
```

Paste those values into `.env`.

### Step 2 — Start the full stack

```bash
cd integration
docker compose -f docker-compose.full.yml up -d
```

Docker will pull images and build the application containers.  
The first build takes **10–20 minutes** depending on your internet speed.

### Step 3 — Verify all services are healthy

```bash
docker compose -f docker-compose.full.yml ps
```

Wait until every container shows `healthy` or `running`.

```bash
# Quick smoke test
curl http://localhost:8080/actuator/health   # Backend → {"status":"UP"}
curl http://localhost:8003/health            # ML Engine → {"status":"ok"}
curl http://localhost:8001/health            # LLM Server → {"status":"ok"}
curl http://localhost                        # Frontend (HTTP 200)
```

### Step 4 — Open the application

| URL | Description |
|-----|-------------|
| `http://localhost` | Web frontend |
| `http://localhost:8080/swagger-ui.html` | API documentation |
| `http://localhost:3001` | Grafana (admin / krishimitra) |
| `http://localhost:9090` | Prometheus |

### Step 5 — Run end-to-end integration tests

```bash
pip install requests pytest
pytest integration/tests/test_e2e.py -v
```

### Useful commands

```bash
# Tail logs for a specific service
docker compose -f docker-compose.full.yml logs -f krishimitra-backend

# Restart a single service after a code change
docker compose -f docker-compose.full.yml restart krishimitra-ml

# Stop everything (keep data volumes)
docker compose -f docker-compose.full.yml down

# Stop and delete all data (clean slate)
docker compose -f docker-compose.full.yml down -v
```

---

## 4. Option B — Oracle Cloud Always-Free VM

Oracle Cloud's **Always Free** tier provides a permanent free ARM VM with up to **4 OCPUs and 24 GB RAM** — more than enough for the full stack.

### Step 1 — Create a free Oracle Cloud account

1. Go to [cloud.oracle.com](https://cloud.oracle.com) and click **Start for free**.
2. Complete identity verification (requires a credit card for verification; **you will not be charged**).
3. Select your **Home Region** — choose one closest to India (e.g., `ap-mumbai-1`).

### Step 2 — Create an Always-Free ARM instance

1. In the Oracle Cloud Console, navigate to **Compute → Instances → Create Instance**.
2. Under **Image and shape**:
   - Image: **Ubuntu 22.04**
   - Shape: Click **Change shape** → **Ampere** → **VM.Standard.A1.Flex**
   - Set **OCPUs: 4** and **Memory: 24 GB** (both within Always Free quota)
3. Under **Networking**, ensure a **public subnet** and **public IP address** are assigned.
4. Under **Add SSH keys**, upload your public SSH key or have Oracle generate one (download the private key).
5. Click **Create** and wait ~2 minutes for the instance to be `Running`.
6. Note the **Public IP address** shown in the instance details.

> **Always-Free quotas:** Up to 4 OCPUs + 24 GB RAM across all A1.Flex instances in your tenancy.

### Step 3 — Open required firewall ports

In the Oracle Cloud Console:

1. Go to **Networking → Virtual Cloud Networks → your VCN → Security Lists**.
2. Add the following **Ingress Rules**:

| Protocol | Port | Description |
|----------|------|-------------|
| TCP | 22 | SSH |
| TCP | 80 | HTTP (frontend) |
| TCP | 443 | HTTPS (frontend) |
| TCP | 8080 | Backend API (optional, for direct access) |
| TCP | 3001 | Grafana (optional) |
| TCP | 9090 | Prometheus (optional) |

Also open ports in the **instance's OS firewall**:

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 3001 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 9090 -j ACCEPT
sudo netfilter-persistent save
```

### Step 4 — SSH into the VM and install Docker

```bash
ssh -i /path/to/your-private-key.pem ubuntu@<YOUR_PUBLIC_IP>
```

```bash
# Update system
sudo apt-get update && sudo apt-get upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu

# Apply group change without logging out
newgrp docker

# Verify
docker --version
docker compose version
```

### Step 5 — Clone the repository onto the VM

```bash
git clone https://github.com/Vipin-Baniya/KrishiMitra-AI.git
cd KrishiMitra-AI
```

### Step 6 — Configure environment variables

```bash
cp .env.example .env
nano .env   # or: vi .env
```

Fill in the required values:

```bash
# Generate inside the VM:
JWT_SECRET=$(openssl rand -base64 64)
```

Required fields to set in `.env`:

```
JWT_SECRET=<generated above>
POSTGRES_PASSWORD=<choose a strong password>
REDIS_PASSWORD=<choose a strong password>
```

All other fields are optional — see [Section 6](#6-free-api-keys) for free API keys.

### Step 7 — Start the full stack

```bash
cd integration
docker compose -f docker-compose.full.yml up -d --build
```

The first build takes **15–25 minutes** on the ARM VM.  
Monitor progress:

```bash
docker compose -f docker-compose.full.yml logs -f
```

### Step 8 — Verify and access the application

```bash
# On the VM
curl http://localhost:8080/actuator/health
curl http://localhost

# From your browser (using the Oracle VM's public IP)
http://<YOUR_PUBLIC_IP>           # Web frontend
http://<YOUR_PUBLIC_IP>:3001      # Grafana
http://<YOUR_PUBLIC_IP>:9090      # Prometheus
```

### Step 9 — (Optional) Add a free domain with HTTPS

**Free domain options:**

- [DuckDNS](https://duckdns.org) — free `<name>.duckdns.org` subdomain (simplest)
- [No-IP](https://noip.com) — free dynamic DNS hostname

**Set up DuckDNS (easiest):**

1. Go to [duckdns.org](https://duckdns.org) and sign in with GitHub.
2. Create a subdomain (e.g., `krishimitra.duckdns.org`) and point it to your Oracle VM's public IP.
3. Install Certbot for free HTTPS (Let's Encrypt):

```bash
sudo apt-get install -y certbot

# Stop nginx temporarily
docker compose -f docker-compose.full.yml stop nginx

# Get certificate (replace with your DuckDNS domain)
sudo certbot certonly --standalone \
  -d krishimitra.duckdns.org \
  --email your@email.com \
  --agree-tos --no-eff-email

# Certificates saved to: /etc/letsencrypt/live/krishimitra.duckdns.org/

# Copy certs to Docker volume
sudo cp /etc/letsencrypt/live/krishimitra.duckdns.org/fullchain.pem \
        /var/lib/docker/volumes/integration_nginx-certs/_data/
sudo cp /etc/letsencrypt/live/krishimitra.duckdns.org/privkey.pem \
        /var/lib/docker/volumes/integration_nginx-certs/_data/

# Restart nginx
docker compose -f docker-compose.full.yml start nginx
```

4. Set a cron job to auto-renew certificates:

```bash
sudo crontab -e
# Add this line:
0 3 * * * certbot renew --quiet && docker compose -f /home/ubuntu/KrishiMitra-AI/integration/docker-compose.full.yml exec -T nginx nginx -s reload
```

### Step 10 — Keep the stack running after SSH disconnect

The `docker compose up -d` command already runs containers in the background.  
To auto-start on VM reboot, create a systemd service:

```bash
sudo nano /etc/systemd/system/krishimitra.service
```

Paste:

```ini
[Unit]
Description=KrishiMitra AI Stack
After=docker.service
Requires=docker.service

[Service]
WorkingDirectory=/home/ubuntu/KrishiMitra-AI/integration
ExecStart=/usr/bin/docker compose -f docker-compose.full.yml up
ExecStop=/usr/bin/docker compose -f docker-compose.full.yml down
Restart=always
RestartSec=10
User=ubuntu

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable krishimitra
sudo systemctl start krishimitra
```

---

## 5. Option B2 — Azure VM Deployment

This option mirrors Option B but uses **Microsoft Azure** instead of Oracle Cloud.  
It is ideal if you already have an Azure subscription or receive free Azure credits (e.g., Azure for Students gives $100/year).

### Step 1 — Create an Azure VM

1. Log in to the [Azure Portal](https://portal.azure.com).
2. Navigate to **Virtual Machines → Create → Azure virtual machine**.
3. Configure:
   - **Image:** Ubuntu Server 22.04 LTS
   - **Size:** Standard_B2s (2 vCPUs, 4 GB RAM) or larger for the full stack
   - **Authentication type:** SSH public key
   - Upload your public key or let Azure generate a key pair (download the `.pem` file)
4. Under **Networking**, ensure a **public IP** is assigned.
5. Click **Review + create → Create**.
6. Note the **Public IP address** from the VM overview page.

The default admin username for Azure Ubuntu VMs is **`azureuser`**.

### Step 2 — Open required firewall ports

In the Azure Portal, go to your VM → **Networking → Add inbound port rule**:

| Protocol | Port | Description |
|----------|------|-------------|
| TCP | 22 | SSH |
| TCP | 80 | HTTP (frontend) |
| TCP | 443 | HTTPS (frontend) |
| TCP | 8080 | Backend API (optional) |
| TCP | 3001 | Grafana (optional) |
| TCP | 9090 | Prometheus (optional) |

### Step 3 — SSH into the VM

```bash
# Replace with your key file path and VM public IP
ssh -i ~/.ssh/your-key.pem azureuser@<YOUR_PUBLIC_IP>
```

> **Tip:** Make sure the key file has the correct permissions:
> ```bash
> chmod 600 ~/.ssh/your-key.pem
> ```

### Step 4 — Install Docker

```bash
# Update system packages
sudo apt-get update && sudo apt-get upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker azureuser

# Apply group change without logging out
newgrp docker

# Verify
docker --version
docker compose version
```

### Step 5 — Clone and configure the repository

```bash
git clone https://github.com/Vipin-Baniya/KrishiMitra-AI.git
cd KrishiMitra-AI
cp .env.example .env
nano .env   # fill in JWT_SECRET and database passwords
```

### Step 6 — Start the stack

```bash
cd integration
docker compose -f docker-compose.full.yml up -d
```

### Step 7 — Verify

```bash
docker compose -f docker-compose.full.yml ps
curl http://localhost:8080/actuator/health
```

### Step 8 — Auto-start on reboot

```bash
sudo nano /etc/systemd/system/krishimitra.service
```

Paste:

```ini
[Unit]
Description=KrishiMitra AI Stack
After=docker.service
Requires=docker.service

[Service]
WorkingDirectory=/home/azureuser/KrishiMitra-AI/integration
ExecStart=/usr/bin/docker compose -f docker-compose.full.yml up
ExecStop=/usr/bin/docker compose -f docker-compose.full.yml down
Restart=always
RestartSec=10
User=azureuser

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable krishimitra
sudo systemctl start krishimitra
```

### Auto-deploy via GitHub Actions (Azure VM)

Add the following job to `cicd/github-actions/ci-cd.yml` to SSH-deploy to your Azure VM:

```yaml
deploy-azure-vm:
  name: "Deploy → Azure VM"
  runs-on: ubuntu-latest
  needs: [docker-build]
  if: github.ref == 'refs/heads/main'
  steps:
    - name: Deploy via SSH
      uses: appleboy/ssh-action@v1
      with:
        host:     ${{ secrets.AZURE_VM_HOST }}
        username: azureuser
        key:      ${{ secrets.AZURE_VM_SSH_KEY }}
        script: |
          cd ~/KrishiMitra-AI
          git pull origin main
          cd integration
          docker compose -f docker-compose.full.yml pull
          docker compose -f docker-compose.full.yml up -d --remove-orphans
          docker image prune -f
```

Add these secrets to your GitHub repository:

| Secret | Value |
|--------|-------|
| `AZURE_VM_HOST` | Azure VM public IP address |
| `AZURE_VM_SSH_KEY` | Contents of your private SSH key (PEM format) |

---

## 6. Option C — Render + Supabase + Upstash (Managed Free Tier)

This option uses fully-managed free cloud services for each component.  
It requires no VM but has more limited compute (CPU/RAM throttled on free tiers).

> **Limitation:** Kafka is not available on free managed tiers. In this configuration the backend falls back to direct database writes (no streaming). The ML and LLM engines are also excluded from this path due to RAM requirements; only the backend + frontend + database are deployed.

### Free services used

| Service | Provider | Free Limits |
|---------|----------|-------------|
| Frontend (static) | [Vercel](https://vercel.com) | Unlimited bandwidth |
| Backend API | [Render](https://render.com) | 750 hrs/month, 512 MB RAM |
| PostgreSQL | [Supabase](https://supabase.com) | 500 MB storage, 2 GB bandwidth |
| Redis | [Upstash](https://upstash.com) | 10,000 req/day, 256 MB |

### Step 1 — Set up PostgreSQL on Supabase

1. Go to [supabase.com](https://supabase.com) → **Start your project** → sign in with GitHub.
2. Click **New project**, fill in name and database password, choose the **India (ap-south-1)** region.
3. Wait ~2 minutes for the project to provision.
4. Go to **Project Settings → Database** → **Connection Pooling** and copy the **Pooler connection string** — looks like:
   ```
   postgresql://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:6543/postgres
   ```
   > ⚠️ Use the **pooler URL (port 6543)**, not the direct connection (port 5432). The pooler is required for serverless/PaaS deployments.
5. Go to **SQL Editor** and run the schema initialization:
   - Copy the contents of `integration/scripts/postgres-init.sql` and execute it.

### Step 2 — Set up Redis on Upstash

1. Go to [upstash.com](https://upstash.com) → sign in with GitHub → **Create Database**.
2. Choose **Redis**, select the **Mumbai** region, and enable **TLS**.
3. Copy the **Redis URL** (format: `rediss://:<password>@<host>:<port>`).

### Step 3 — Deploy the backend on Render

1. Go to [render.com](https://render.com) → sign in with GitHub → **New Web Service**.
2. Connect your GitHub repository (`Vipin-Baniya/KrishiMitra-AI`).
3. Set the following:
   - **Name:** `krishimitra-backend`
   - **Root directory:** `backend`
   - **Runtime:** Docker
   - **Plan:** Free
4. Under **Environment Variables**, add:

   | Key | Value |
   |-----|-------|
   | `JWT_SECRET` | `<generated with openssl rand -base64 64>` |
   | `DB_URL` | `jdbc:postgresql://<pooler-host>:6543/postgres` (from Supabase → Connection Pooling) |
   | `DB_USER` | `postgres.<ref>` (from Supabase → Connection Pooling) |
   | `DB_PASS` | `<your Supabase DB password>` |
   | `REDIS_HOST` | `<Upstash hostname>` |
   | `REDIS_PORT` | `<Upstash port>` |
   | `REDIS_PASS` | `<Upstash password>` |
   | `KAFKA_BROKERS` | *(leave empty — Kafka disabled in this mode)* |
   | `SPRING_PROFILES_ACTIVE` | `prod` |

5. Click **Create Web Service**. Render builds and deploys automatically.
6. Copy the deployed URL (e.g., `https://krishimitra-backend.onrender.com`).

### Step 4 — Deploy the frontend on Vercel

1. Go to [vercel.com](https://vercel.com) → sign in with GitHub → **Add New Project**.
2. Import `Vipin-Baniya/KrishiMitra-AI`.
3. Set the **Root Directory** to `frontend`.
4. Add an environment variable:
   - `VITE_API_URL` = `https://krishimitra-backend.onrender.com`
5. Click **Deploy**.

### Step 5 — Access the application

Vercel provides a URL like `https://krishimitra-ai.vercel.app`.  
The backend is accessible at `https://krishimitra-backend.onrender.com/swagger-ui.html`.

> **Note:** Render free instances **spin down after 15 minutes of inactivity**. The first request after idle takes ~30 seconds to start up. Upgrade to a paid plan to avoid this.

---

## 7. Free API Keys

The platform works without any external API keys. External APIs are **optional fallbacks**.

### data.gov.in API key (recommended — free)

Used to pull live commodity prices from Agmarknet.

1. Register at [data.gov.in/user/register](https://data.gov.in/user/register) (free).
2. Go to **My Account → API Keys** and generate a key.
3. Add to `.env`: `DATAGOV_API_KEY=<your key>`

### OpenAI API key (optional)

Used as a fallback when the custom LLM confidence is below 72%.

- New accounts receive **$5 free credit** (expires after 3 months).
- Sign up at [platform.openai.com](https://platform.openai.com) → **API Keys**.
- Add to `.env`: `OPENAI_API_KEY=sk-...`

### Anthropic API key (optional)

Used as the emergency LLM fallback only.

- New accounts receive **$5 free credit**.
- Sign up at [console.anthropic.com](https://console.anthropic.com).
- Add to `.env`: `ANTHROPIC_API_KEY=sk-ant-...`

### Twilio (optional — SMS/WhatsApp alerts)

- Free trial includes **$15.50 credit** and a trial number.
- Sign up at [twilio.com/try-twilio](https://www.twilio.com/try-twilio).
- Add to `.env`: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_SMS_FROM`

### Firebase Cloud Messaging (optional — Android push notifications)

- Completely free for up to 100 concurrent connections.
- Create a project at [console.firebase.google.com](https://console.firebase.google.com).
- Go to **Project Settings → Service accounts → Generate new private key**.
- Save the JSON file and set in `.env`:
  - `FCM_PROJECT_ID=<your project id>`
  - `FCM_SERVICE_ACCOUNT_PATH=/app/config/firebase-service-account.json`
  - Mount the JSON file as a Docker volume in `docker-compose.full.yml` if needed.

---

## 8. Environment Variable Reference

Below is the complete list of environment variables with their default values and descriptions.  
Minimum required variables are marked **Required**.

```bash
# ── Security (Required) ────────────────────────────────────────
JWT_SECRET=                    # Generate: openssl rand -base64 64

# ── Database (Required) ────────────────────────────────────────
POSTGRES_PASSWORD=             # Strong password
POSTGRES_HOST=postgres         # Docker: "postgres" | Cloud: RDS/Supabase host
POSTGRES_PORT=5432

# ── Redis (Required) ──────────────────────────────────────────
REDIS_PASSWORD=                # Strong password
REDIS_HOST=redis               # Docker: "redis" | Cloud: Upstash/ElastiCache host
REDIS_PORT=6379

# ── Kafka ─────────────────────────────────────────────────────
KAFKA_BROKERS=kafka:9092       # Docker: "kafka:9092" | Cloud: MSK endpoint

# ── LLM APIs (Optional) ───────────────────────────────────────
OPENAI_API_KEY=                # First fallback (free trial available)
ANTHROPIC_API_KEY=             # Emergency fallback (free trial available)

# ── Data (Optional) ───────────────────────────────────────────
DATAGOV_API_KEY=               # Live Agmarknet prices (free registration)

# ── Notifications (Optional) ──────────────────────────────────
TWILIO_ACCOUNT_SID=
TWILIO_AUTH_TOKEN=
TWILIO_SMS_FROM=               # Your Twilio phone number
TWILIO_WHATSAPP_FROM=          # whatsapp:+14155238886

# ── Firebase (Optional) ───────────────────────────────────────
FCM_PROJECT_ID=
FCM_SERVICE_ACCOUNT_PATH=/app/config/firebase-service-account.json

# ── Monitoring (Optional) ─────────────────────────────────────
GRAFANA_PASSWORD=krishimitra
SLACK_WEBHOOK_URL=
PAGERDUTY_SERVICE_KEY=

# ── Frontend (Optional) ───────────────────────────────────────
VITE_API_URL=                  # Empty = nginx proxies /api → backend

# ── Deployment (Optional) ─────────────────────────────────────
DOMAIN=localhost
IMAGE_TAG=latest
```

---

## 9. Monitoring Stack

The monitoring stack (Prometheus + Grafana + Loki + Alertmanager) is included in the main  
`docker-compose.full.yml`. It can also be started separately:

```bash
cd monitoring
./setup.sh start
```

### Access dashboards

| URL | Credentials |
|-----|-------------|
| `http://localhost:3001` | admin / `$GRAFANA_PASSWORD` (default: krishimitra) |
| `http://localhost:9090` | No auth |
| `http://localhost:9093` | No auth (Alertmanager) |

### Available Grafana dashboards

| Dashboard | Metrics |
|-----------|---------|
| System Overview | Request rate, p95 latency, error rate, service health |
| Business KPIs | DAU, registrations, sell advisories, AI chat volume |
| ML Engine | LLM routing %, forecast latency, model accuracy |
| Infrastructure | CPU, memory, disk, Redis, Kafka lag |

### Monitoring operations

```bash
# Check all services
cd monitoring && ./setup.sh status

# Reload Prometheus config after rule changes
cd monitoring && ./setup.sh reload

# Fire a test alert to verify Slack/PagerDuty routing
cd monitoring && ./setup.sh test-alert

# Backup all Grafana dashboards to JSON
cd monitoring && ./setup.sh backup
```

---

## 10. CI/CD with GitHub Actions

GitHub Actions is **free for public repositories** and provides 2,000 free minutes/month for private repositories.

The pipeline (`.github/workflows/ci-cd.yml`) automatically:

1. Runs backend unit tests (Java 21 + Spring Boot)
2. Runs ML engine tests (Python 3.11 + pytest)
3. Runs frontend lint and build (Node 20 + Vite)
4. Scans for security vulnerabilities (Trivy + OWASP)
5. Builds and pushes Docker images to GitHub Container Registry (GHCR)
6. Deploys to staging on every push to `main`
7. Deploys to production on every `v*.*.*` tag

### Step 1 — Fork the repository

Fork `Vipin-Baniya/KrishiMitra-AI` to your GitHub account.

### Step 2 — Add GitHub repository secrets

In your fork: **Settings → Secrets and variables → Actions → New repository secret**

| Secret name | Value | Required for |
|-------------|-------|--------------|
| `JWT_SECRET` | Output of `openssl rand -base64 64` | All deployments |
| `STAGING_KUBECONFIG` | Base64-encoded kubeconfig for staging cluster | Staging deploy |
| `PROD_KUBECONFIG` | Base64-encoded kubeconfig for production cluster | Production deploy |
| `SLACK_WEBHOOK_URL` | Slack webhook URL | Slack notifications |

> For free deployments (Docker Compose on a VM), you do **not** need  
> `STAGING_KUBECONFIG` or `PROD_KUBECONFIG`. You can disable those jobs in  
> `cicd/github-actions/ci-cd.yml` by removing the `deploy-staging` and  
> `deploy-production` job blocks.

### Step 3 — Trigger the pipeline

```bash
# Push to main triggers tests + build
git push origin main

# Tag a release for production deploy
git tag v1.0.0
git push origin v1.0.0
```

### Step 4 — View pipeline results

Go to your fork on GitHub → **Actions** tab to see live pipeline status.

### Auto-deploy to Oracle Cloud VM via SSH (no Kubernetes needed)

Add this deploy job to `cicd/github-actions/ci-cd.yml` to SSH-deploy to your free VM  
instead of using Helm/Kubernetes:

```yaml
deploy-vm:
  name: "Deploy → Oracle VM"
  runs-on: ubuntu-latest
  needs: [docker-build]
  if: github.ref == 'refs/heads/main'
  steps:
    - name: Deploy via SSH
      uses: appleboy/ssh-action@v1
      with:
        host:     ${{ secrets.VM_HOST }}
        username: ubuntu
        key:      ${{ secrets.VM_SSH_KEY }}
        script: |
          cd ~/KrishiMitra-AI
          git pull origin main
          cd integration
          docker compose -f docker-compose.full.yml pull
          docker compose -f docker-compose.full.yml up -d --remove-orphans
          docker image prune -f
```

Add these secrets to your GitHub repository:

| Secret | Value |
|--------|-------|
| `VM_HOST` | Oracle VM public IP address |
| `VM_SSH_KEY` | Contents of your private SSH key (PEM format) |

---

## 11. Kubernetes Security Features

The KrishiMitra Helm chart enforces a defence-in-depth security posture across all Kubernetes workloads.  
The following sections describe each layer.

### Pod and container security contexts

Every pod and container is hardened at the Kubernetes API level:

| Setting | Value | Rationale |
|---------|-------|-----------|
| `runAsNonRoot` | `true` | Prevents containers from running as root |
| `runAsUser` / `runAsGroup` | `1001` | Dedicated non-privileged UID/GID |
| `fsGroup` | `1001` | Volume mounts owned by the app group |
| `allowPrivilegeEscalation` | `false` | Blocks `setuid` / `sudo`-style escalation |
| `readOnlyRootFilesystem` | `true` | Prevents filesystem tampering; writable paths use `emptyDir` volumes |
| `capabilities.drop` | `[ALL]` | Drops every Linux capability; add back only if strictly required |
| `seccompProfile.type` | `RuntimeDefault` | Enables the container runtime's default syscall filter |
| `automountServiceAccountToken` | `false` | Prevents automatic credential injection unless explicitly needed |

### Role-Based Access Control (RBAC)

Each service runs under its own dedicated `ServiceAccount` and is granted only the Kubernetes API permissions it needs:

| Service | Allowed resources | Allowed verbs |
|---------|------------------|---------------|
| `backend` | ConfigMap (`krishimitra-config`), Secret (`krishimitra-secrets`) | `get`, `watch`, `list` / `get` |
| `ml-engine` | ConfigMap (`krishimitra-config`) | `get`, `watch`, `list` |
| `llm-server` | ConfigMap (`krishimitra-config`), Secret (`krishimitra-secrets`) | `get`, `watch`, `list` / `get` |
| `frontend` | *(none — empty Role)* | — |

### NetworkPolicies

A **default-deny** NetworkPolicy blocks all ingress and egress in the `krishimitra` namespace.  
Explicit allow rules are then added for each service:

| Source | Destination | Port | Rationale |
|--------|-------------|------|-----------|
| ingress-nginx | backend, frontend | 8080 / 80 | Public traffic entry-points |
| Prometheus | all services | service port | Metrics scraping |
| backend | ml-engine | 8003 | Forecast requests |
| backend | llm-server | 8001 | AI chat requests |
| backend, ml, llm | PostgreSQL | 5432 | Database access |
| backend, ml, llm | Redis | 6379 | Cache access |
| backend, llm | 443 | — | External AI API calls (OpenAI/Anthropic) |

### Polaris policy-as-code scanning

The CI/CD pipeline renders the Helm chart and passes the output through [Polaris](https://polaris.docs.fairwinds.com/) and Trivy.  
Any **danger**-level violation (e.g., missing `readOnlyRootFilesystem`, privilege escalation) fails the build before images are pushed.  
Configuration: `cicd/polaris-config.yaml`.

### Enforcing Pod Security Standards

Label the deployment namespace to enforce the **restricted** Pod Security Standard:

```bash
kubectl label namespace krishimitra \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/enforce-version=latest \
  pod-security.kubernetes.io/warn=restricted \
  pod-security.kubernetes.io/audit=restricted
```

> This label causes the Kubernetes API server to reject any pod that does not meet the restricted standard  
> (non-root user, no privilege escalation, read-only root FS, etc.).

---

## 12. Troubleshooting

### Containers won't start — "JWT_SECRET env var is required"

```bash
# Make sure .env exists and has JWT_SECRET set
cat .env | grep JWT_SECRET

# If missing, generate and add it:
echo "JWT_SECRET=$(openssl rand -base64 64)" >> .env
```

### Backend container exits immediately

```bash
# Check backend logs
docker compose -f docker-compose.full.yml logs krishimitra-backend

# Common cause: cannot connect to postgres
# Wait for postgres to be healthy, then restart backend:
docker compose -f docker-compose.full.yml restart krishimitra-backend
```

### ML Engine fails to start — "No module named X"

```bash
docker compose -f docker-compose.full.yml logs krishimitra-ml

# Rebuild the ML image:
docker compose -f docker-compose.full.yml build krishimitra-ml
docker compose -f docker-compose.full.yml up -d krishimitra-ml
```

### Oracle VM — out of disk space

```bash
df -h   # Check disk usage

# Remove unused Docker images/containers
docker system prune -af

# Remove old log files
sudo journalctl --vacuum-time=3d
```

### Oracle VM — out of memory (OOM kills)

```bash
# Check memory usage
free -h
docker stats --no-stream

# Option 1: Add swap space (2 GB)
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Option 2: Disable the LLM server (saves ~2 GB RAM)
# Comment out the 'krishimitra-llm' service in docker-compose.full.yml
```

### Grafana shows "No data"

```bash
# Make sure Prometheus is scraping targets
curl http://localhost:9090/api/v1/targets | python3 -m json.tool | grep health

# Re-import dashboards
cd monitoring && ./setup.sh import
```

### Frontend shows blank page or API errors

```bash
# Check nginx logs
docker compose -f docker-compose.full.yml logs nginx

# Verify backend is accessible from nginx container
docker compose -f docker-compose.full.yml exec nginx \
  wget -qO- http://krishimitra-backend:8080/actuator/health
```

### Check all service health at once

```bash
echo "=== Service Status ===" && \
docker compose -f integration/docker-compose.full.yml ps && \
echo "" && \
echo "=== Backend ===" && curl -sf http://localhost:8080/actuator/health | python3 -m json.tool && \
echo "=== ML Engine ===" && curl -sf http://localhost:8003/health && \
echo "=== LLM Server ===" && curl -sf http://localhost:8001/health && \
echo "=== Frontend ===" && curl -sfo /dev/null -w "HTTP %{http_code}\n" http://localhost
```

---

## Summary — Quickest Free Path

```bash
# 1. Clone
git clone https://github.com/Vipin-Baniya/KrishiMitra-AI.git
cd KrishiMitra-AI

# 2. Configure (minimum required settings)
cp .env.example .env
echo "JWT_SECRET=$(openssl rand -base64 64)" >> .env
echo "POSTGRES_PASSWORD=dev_password_123" >> .env
echo "REDIS_PASSWORD=dev_password_123" >> .env

# 3. Start
cd integration
docker compose -f docker-compose.full.yml up -d

# 4. Open
xdg-open http://localhost          # Linux
open http://localhost               # macOS
# Or navigate to http://localhost in your browser
```

The application will be running at **http://localhost** within a few minutes.

---

*For questions or issues, open a GitHub Issue at [github.com/Vipin-Baniya/KrishiMitra-AI/issues](https://github.com/Vipin-Baniya/KrishiMitra-AI/issues).*
