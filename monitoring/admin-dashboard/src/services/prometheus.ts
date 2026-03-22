// ════════════════════════════════════════════════════════════
//  admin-dashboard/src/services/prometheus.ts
//  Queries Prometheus HTTP API directly
// ════════════════════════════════════════════════════════════
import axios from 'axios';

const PROM = axios.create({ baseURL: import.meta.env.VITE_PROMETHEUS_URL ?? 'http://localhost:9090' });
const GRAFANA = axios.create({
  baseURL: import.meta.env.VITE_GRAFANA_URL ?? 'http://localhost:3001',
  auth: { username: 'admin', password: import.meta.env.VITE_GRAFANA_PASSWORD ?? 'krishimitra' },
});

export interface PrometheusResult { metric: Record<string, string>; value: [number, string]; }
export interface PrometheusRangeResult { metric: Record<string, string>; values: [number, string][]; }

async function query(expr: string): Promise<PrometheusResult[]> {
  const { data } = await PROM.get('/api/v1/query', { params: { query: expr } });
  return data.data?.result ?? [];
}

async function queryRange(expr: string, start: string, end: string, step: string): Promise<PrometheusRangeResult[]> {
  const { data } = await PROM.get('/api/v1/query_range', { params: { query: expr, start, end, step } });
  return data.data?.result ?? [];
}

export function scalar(results: PrometheusResult[]): number {
  return parseFloat(results[0]?.value[1] ?? '0');
}

// ─────────────────────────────────────────────────────────────
// Metric fetchers used by React hooks
// ─────────────────────────────────────────────────────────────
export const metrics = {

  // Services up/down
  servicesUp: () => query('count(up{job=~"krishimitra.*"} == 1)'),
  servicesDown: () => query('count(up{job=~"krishimitra.*"} == 0) or vector(0)'),

  // Backend
  requestRate:    () => query('sum(rate(http_server_requests_seconds_count{job="krishimitra-backend"}[2m]))'),
  errorRate:      () => query('sum(rate(http_server_requests_seconds_count{job="krishimitra-backend",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{job="krishimitra-backend"}[5m]))'),
  p95Latency:     () => query('histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="krishimitra-backend"}[5m])) by (le))'),
  heapUsagePct:   () => query('jvm_memory_used_bytes{job="krishimitra-backend",area="heap"} / jvm_memory_max_bytes{job="krishimitra-backend",area="heap"}'),
  dbConnPending:  () => query('hikaricp_connections_pending{job="krishimitra-backend"}'),

  // ML
  mlCustomPct:    () => query('sum(rate(krishimitra_requests_total{model="custom",status="success"}[10m])) / sum(rate(krishimitra_requests_total[10m]))'),
  mlLatencyP95:   () => query('histogram_quantile(0.95, rate(krishimitra_ml_latency_seconds_bucket[5m]))'),
  mlFallbackRate: () => query('sum(rate(krishimitra_ml_fallbacks_total[10m]))'),

  // Business KPIs
  activeFarmers:   () => query('increase(krishimitra_farmer_logins_total[24h])'),
  newRegistrations:() => query('increase(krishimitra_farmer_registrations_total[24h])'),
  sellAdvisories:  () => query('increase(krishimitra_sell_advisories_total[24h])'),
  chatMessages:    () => query('increase(krishimitra_chat_messages_total[24h])'),
  forecastsServed: () => query('increase(krishimitra_forecast_requests_total[24h])'),

  // Infra
  cpuUsage:        () => query('100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)'),
  diskUsagePct:    () => query('1 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"})'),
  redisMemPct:     () => query('redis_memory_used_bytes / redis_memory_max_bytes'),
  kafkaLag:        () => query('sum(kafka_consumer_group_lag{group="krishimitra-backend"})'),

  // Range series for charts (last 6h, 30s steps)
  requestRateSeries: (hours = 6) => queryRange(
    'sum(rate(http_server_requests_seconds_count{job="krishimitra-backend"}[2m]))',
    `now-${hours}h`, 'now', '30s'
  ),
  latencySeries: (hours = 6) => queryRange(
    'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="krishimitra-backend"}[5m])) by (le))',
    `now-${hours}h`, 'now', '30s'
  ),
  errorRateSeries: (hours = 6) => queryRange(
    'sum(rate(http_server_requests_seconds_count{job="krishimitra-backend",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{job="krishimitra-backend"}[5m]))',
    `now-${hours}h`, 'now', '30s'
  ),
  llmRoutingSeries: (hours = 6) => queryRange(
    'rate(krishimitra_requests_total[5m])',
    `now-${hours}h`, 'now', '30s'
  ),

  // Active alerts
  activeAlerts: () => PROM.get('/api/v1/alerts').then(r => r.data.data?.alerts ?? []),
};

// ════════════════════════════════════════════════════════════
//  src/hooks/useMetrics.ts
// ════════════════════════════════════════════════════════════
import { useQuery } from 'react-query';
import { metrics, scalar, type PrometheusResult } from './prometheus';

const REFETCH_MS = 15_000;  // refresh every 15s

export function useOverviewMetrics() {
  const opts = { refetchInterval: REFETCH_MS, staleTime: 10_000 };
  return {
    servicesUp:       useQuery(['services-up'],       metrics.servicesUp,      opts),
    requestRate:      useQuery(['request-rate'],      metrics.requestRate,     opts),
    errorRate:        useQuery(['error-rate'],        metrics.errorRate,       opts),
    p95Latency:       useQuery(['p95-latency'],       metrics.p95Latency,      opts),
    mlCustomPct:      useQuery(['ml-custom-pct'],     metrics.mlCustomPct,     opts),
    cpuUsage:         useQuery(['cpu-usage'],         metrics.cpuUsage,        opts),
    diskUsagePct:     useQuery(['disk-usage'],        metrics.diskUsagePct,    opts),
    redisMemPct:      useQuery(['redis-mem'],         metrics.redisMemPct,     opts),
    kafkaLag:         useQuery(['kafka-lag'],         metrics.kafkaLag,        opts),
    activeAlerts:     useQuery(['active-alerts'],     metrics.activeAlerts,    opts),
  };
}

export function useBusinessMetrics() {
  const opts = { refetchInterval: 60_000, staleTime: 55_000 };
  return {
    activeFarmers:    useQuery(['active-farmers'],    metrics.activeFarmers,    opts),
    newRegistrations: useQuery(['new-regs'],          metrics.newRegistrations, opts),
    sellAdvisories:   useQuery(['sell-advisories'],   metrics.sellAdvisories,   opts),
    chatMessages:     useQuery(['chat-messages'],     metrics.chatMessages,     opts),
    forecastsServed:  useQuery(['forecasts-served'],  metrics.forecastsServed,  opts),
    mlFallbackRate:   useQuery(['ml-fallback'],       metrics.mlFallbackRate,   opts),
  };
}

export function useChartSeries(hours = 6) {
  const opts = { refetchInterval: 30_000, staleTime: 25_000 };
  return {
    requestRate: useQuery(['rr-series', hours],     () => metrics.requestRateSeries(hours), opts),
    latency:     useQuery(['lat-series', hours],    () => metrics.latencySeries(hours),     opts),
    errorRate:   useQuery(['err-series', hours],    () => metrics.errorRateSeries(hours),   opts),
    llmRouting:  useQuery(['llm-series', hours],    () => metrics.llmRoutingSeries(hours),  opts),
  };
}

// Converts Prometheus range result to recharts format
export function toChartData(results: { values: [number, string][] }[] | undefined, labels?: string[]) {
  if (!results?.length) return [];
  const base = results[0];
  return base.values.map(([ts], i) => {
    const point: Record<string, number> = {
      time: ts * 1000,
      ...(results.reduce((acc, r, idx) => ({
        ...acc,
        [labels?.[idx] ?? `series${idx}`]: parseFloat(r.values[i]?.[1] ?? '0'),
      }), {})),
    };
    return point;
  });
}
