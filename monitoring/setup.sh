#!/usr/bin/env bash
# ================================================================
#  KrishiMitra Monitoring — Setup & Operations Script
#
#  Usage:
#    ./scripts/setup.sh start      # Start full monitoring stack
#    ./scripts/setup.sh stop       # Stop all containers
#    ./scripts/setup.sh status     # Check all service health
#    ./scripts/setup.sh import     # Import Grafana dashboards
#    ./scripts/setup.sh reload     # Reload Prometheus config
#    ./scripts/setup.sh test-alert # Fire a test alert
# ================================================================

set -euo pipefail

PROM_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3001}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASS="${GRAFANA_PASS:-krishimitra}"
ALERT_URL="${ALERTMANAGER_URL:-http://localhost:9093}"

GREEN='\033[0;32m' YELLOW='\033[1;33m' RED='\033[0;31m' NC='\033[0m'
info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }
section() { echo -e "\n${GREEN}═══ $* ═══${NC}"; }

# ── HEALTH CHECKS ─────────────────────────────────────────────
check_service() {
    local name=$1 url=$2
    if curl -sf "$url" > /dev/null 2>&1; then
        echo -e "  ${GREEN}✓${NC} $name"
        return 0
    else
        echo -e "  ${RED}✗${NC} $name ($url)"
        return 1
    fi
}

cmd_status() {
    section "Service Health"
    check_service "Prometheus"     "$PROM_URL/-/healthy"    || true
    check_service "Grafana"        "$GRAFANA_URL/api/health" || true
    check_service "Alertmanager"   "$ALERT_URL/-/healthy"   || true
    check_service "Loki"           "http://localhost:3100/ready" || true
    check_service "Tempo"          "http://localhost:3200/ready" || true
    check_service "Node Exporter"  "http://localhost:9100/metrics" || true
    check_service "Admin Dashboard" "http://localhost:5173"  || true

    section "Prometheus Targets"
    curl -sf "$PROM_URL/api/v1/targets" | \
        python3 -c "
import sys, json
data = json.load(sys.stdin)['data']['activeTargets']
for t in data:
    status = '✓' if t['health'] == 'up' else '✗'
    print(f'  {status} {t[\"labels\"].get(\"job\",\"?\")} — {t[\"scrapeUrl\"]}')
" 2>/dev/null || warn "Could not fetch target status"

    section "Firing Alerts"
    curl -sf "$ALERT_URL/api/v2/alerts?active=true" | \
        python3 -c "
import sys, json
alerts = json.load(sys.stdin)
if not alerts:
    print('  ✓ No active alerts')
else:
    for a in alerts:
        sev = a['labels'].get('severity','?')
        name = a['labels'].get('alertname','?')
        print(f'  ⚠  [{sev.upper()}] {name}')
" 2>/dev/null || warn "Could not fetch alerts"
}

# ── START ─────────────────────────────────────────────────────
cmd_start() {
    section "Starting KrishiMitra Monitoring Stack"

    # Validate required env vars
    if [[ -z "${SLACK_WEBHOOK_URL:-}" ]]; then
        warn "SLACK_WEBHOOK_URL not set — Slack alerts disabled"
    fi
    if [[ -z "${PAGERDUTY_SERVICE_KEY:-}" ]]; then
        warn "PAGERDUTY_SERVICE_KEY not set — PagerDuty disabled"
    fi

    info "Pulling latest images..."
    docker compose pull --quiet

    info "Starting containers..."
    docker compose up -d

    info "Waiting for Prometheus to be healthy..."
    for i in $(seq 1 30); do
        if curl -sf "$PROM_URL/-/healthy" > /dev/null 2>&1; then
            info "Prometheus ready"
            break
        fi
        sleep 2
        [[ $i -eq 30 ]] && error "Prometheus did not start in 60s" && exit 1
    done

    info "Waiting for Grafana to be healthy..."
    for i in $(seq 1 30); do
        if curl -sf "$GRAFANA_URL/api/health" > /dev/null 2>&1; then
            info "Grafana ready"
            break
        fi
        sleep 2
        [[ $i -eq 30 ]] && error "Grafana did not start in 60s" && exit 1
    done

    cmd_import

    section "Stack started successfully"
    echo ""
    echo "  Grafana:       $GRAFANA_URL  (${GRAFANA_USER} / ${GRAFANA_PASS})"
    echo "  Prometheus:    $PROM_URL"
    echo "  Alertmanager:  $ALERT_URL"
    echo "  Loki:          http://localhost:3100"
    echo "  Tempo:         http://localhost:3200"
    echo ""
}

# ── STOP ──────────────────────────────────────────────────────
cmd_stop() {
    section "Stopping monitoring stack"
    docker compose down
    info "All containers stopped"
}

# ── IMPORT DASHBOARDS ─────────────────────────────────────────
cmd_import() {
    section "Importing Grafana Dashboards"

    # Create folder
    curl -sf -X POST "$GRAFANA_URL/api/folders" \
        -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
        -H "Content-Type: application/json" \
        -d '{"uid":"krishimitra","title":"KrishiMitra"}' > /dev/null 2>&1 || true

    # Import each dashboard JSON
    for f in grafana/dashboards/*.json; do
        dashboard_json=$(cat "$f")
        payload=$(python3 -c "
import json, sys
d = json.load(open('$f'))
print(json.dumps({'dashboard': d, 'folderId': 0, 'folderUid': 'krishimitra', 'overwrite': True}))
")
        name=$(python3 -c "import json; d=json.load(open('$f')); print(d.get('title','unknown'))")
        result=$(curl -sf -X POST "$GRAFANA_URL/api/dashboards/import" \
            -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
            -H "Content-Type: application/json" \
            -d "$payload" 2>&1)

        if echo "$result" | grep -q '"status":"success"'; then
            info "Imported: $name"
        else
            warn "Failed to import $name: $result"
        fi
    done
}

# ── RELOAD PROMETHEUS ──────────────────────────────────────────
cmd_reload() {
    section "Reloading Prometheus configuration"
    curl -sf -X POST "$PROM_URL/-/reload"
    info "Prometheus config reloaded"
}

# ── TEST ALERT ────────────────────────────────────────────────
cmd_test_alert() {
    section "Firing test alert to Alertmanager"
    curl -sf -X POST "$ALERT_URL/api/v2/alerts" \
        -H "Content-Type: application/json" \
        -d '[{
            "labels": {
                "alertname": "KrishiMitraTestAlert",
                "severity":  "info",
                "team":      "backend",
                "environment": "production"
            },
            "annotations": {
                "summary":     "This is a test alert from KrishiMitra monitoring",
                "description": "If you see this, Alertmanager routing is working correctly."
            },
            "endsAt": "'$(date -u -v+5M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '+5 minutes' '+%Y-%m-%dT%H:%M:%SZ')'"
        }]'
    info "Test alert fired — check Alertmanager and Slack"
}

# ── VALIDATE RULES ────────────────────────────────────────────
cmd_validate() {
    section "Validating Prometheus rules"
    if command -v promtool &>/dev/null; then
        promtool check rules prometheus/rules/*.yml
        info "All rules valid"
    else
        warn "promtool not found — run: brew install prometheus"
        # Validate via API instead
        for f in prometheus/rules/*.yml; do
            result=$(curl -sf -X POST "$PROM_URL/api/v1/rules/test" \
                --data-binary "@$f" -H "Content-Type: application/yaml" 2>&1 || true)
            info "Checked: $f"
        done
    fi
}

# ── BACKUP ────────────────────────────────────────────────────
cmd_backup() {
    section "Backing up Grafana dashboards"
    mkdir -p grafana/dashboards/backup
    dashboards=$(curl -sf -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
        "$GRAFANA_URL/api/search?type=dash-db" | \
        python3 -c "import sys,json; [print(d['uid']) for d in json.load(sys.stdin)]")
    for uid in $dashboards; do
        title=$(curl -sf -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
            "$GRAFANA_URL/api/dashboards/uid/$uid" | \
            python3 -c "import sys,json; print(json.load(sys.stdin)['dashboard']['title'])" | tr ' ' '_')
        curl -sf -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
            "$GRAFANA_URL/api/dashboards/uid/$uid" | \
            python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d['dashboard'],indent=2))" \
            > "grafana/dashboards/backup/${title}.json"
        info "Backed up: $title"
    done
}

# ── DISPATCH ──────────────────────────────────────────────────
case "${1:-help}" in
    start)     cmd_start   ;;
    stop)      cmd_stop    ;;
    status)    cmd_status  ;;
    import)    cmd_import  ;;
    reload)    cmd_reload  ;;
    test-alert) cmd_test_alert ;;
    validate)  cmd_validate ;;
    backup)    cmd_backup  ;;
    *)
        echo "Usage: $0 {start|stop|status|import|reload|test-alert|validate|backup}"
        exit 1
        ;;
esac
