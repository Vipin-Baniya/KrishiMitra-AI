import React, { useState, useMemo } from 'react';
import { QueryClient, QueryClientProvider } from 'react-query';
import {
  LineChart, Line, AreaChart, Area, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer, ReferenceLine,
} from 'recharts';
import { format } from 'date-fns';
import { useOverviewMetrics, useBusinessMetrics, useChartSeries, toChartData, scalar } from './services/prometheus';

const qc = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 10_000 } } });

// ─────────────────────────────────────────────────────────────
// DESIGN TOKENS (inline — no external CSS dependency for admin)
// ─────────────────────────────────────────────────────────────
const T = {
  bg:      '#0F1210',
  surface: '#161C18',
  card:    '#1C2420',
  border:  'rgba(232,237,233,0.08)',
  text:    '#E4EBE6',
  muted:   '#627068',
  accent:  '#4FB483',
  danger:  '#F4736B',
  warning: '#F5B73A',
  info:    '#60A5FA',
  purple:  '#A78BFA',
};

const styles = {
  app: {
    minHeight: '100vh', background: T.bg,
    color: T.text, fontFamily: "'DM Sans', system-ui, sans-serif",
    fontSize: 14,
  },
  header: {
    background: T.surface, borderBottom: `1px solid ${T.border}`,
    padding: '12px 24px', display: 'flex', alignItems: 'center',
    justifyContent: 'space-between', position: 'sticky' as const, top: 0, zIndex: 100,
  },
  main:  { padding: '20px 24px', maxWidth: 1600, margin: '0 auto' },
  card:  { background: T.card, border: `1px solid ${T.border}`, borderRadius: 12, padding: '16px 20px' },
  label: { fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase' as const, color: T.muted, marginBottom: 6 },
  val:   { fontSize: 28, fontWeight: 700, letterSpacing: '-0.03em', lineHeight: 1.1 },
  sub:   { fontSize: 12, color: T.muted, marginTop: 4 },
  grid2: { display: 'grid', gridTemplateColumns: 'repeat(2,1fr)', gap: 16 },
  grid3: { display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 16 },
  grid4: { display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 16 },
  grid6: { display: 'grid', gridTemplateColumns: 'repeat(6,1fr)', gap: 12 },
};

// ─────────────────────────────────────────────────────────────
// COMPONENTS
// ─────────────────────────────────────────────────────────────

function StatCard({ label, value, unit = '', sub = '', color = T.text, trend, loading }: {
  label: string; value: string | number; unit?: string; sub?: string;
  color?: string; trend?: 'up'|'down'|'flat'; loading?: boolean;
}) {
  return (
    <div style={{ ...styles.card }}>
      <div style={styles.label}>{label}</div>
      {loading
        ? <div style={{ height: 32, background: T.border, borderRadius: 6, marginBottom: 8, animation: 'pulse 1.5s infinite' }} />
        : (
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
            <span style={{ ...styles.val, color }}>{typeof value === 'number' ? value.toLocaleString('en-IN') : value}</span>
            {unit && <span style={{ fontSize: 13, color: T.muted }}>{unit}</span>}
          </div>
        )
      }
      {sub && <div style={styles.sub}>{sub}</div>}
    </div>
  );
}

function SectionTitle({ children, action }: { children: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
      <h2 style={{ fontSize: 15, fontWeight: 600, margin: 0, color: T.text }}>{children as string}</h2>
      {action}
    </div>
  );
}

function StatusDot({ up }: { up: boolean }) {
  return (
    <span style={{
      display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
      background: up ? T.accent : T.danger,
      boxShadow: `0 0 6px ${up ? T.accent : T.danger}`,
      flexShrink: 0,
    }} />
  );
}

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{ background: T.surface, border: `1px solid ${T.border}`, borderRadius: 8, padding: '8px 12px', fontSize: 12 }}>
      <div style={{ color: T.muted, marginBottom: 4 }}>
        {typeof label === 'number' ? format(new Date(label), 'HH:mm') : label}
      </div>
      {payload.map((p: any, i: number) => (
        <div key={i} style={{ color: p.color, marginBottom: 2 }}>
          {p.name}: <strong>{typeof p.value === 'number' ? p.value.toFixed(3) : p.value}</strong>
        </div>
      ))}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// PAGES
// ─────────────────────────────────────────────────────────────

function OverviewPage() {
  const { servicesUp, requestRate, errorRate, p95Latency, mlCustomPct, cpuUsage, diskUsagePct, redisMemPct, kafkaLag, activeAlerts } = useOverviewMetrics();
  const { requestRate: rrSeries, latency: latSeries, errorRate: errSeries, llmRouting: llmSeries } = useChartSeries(6);

  const rrData   = useMemo(() => toChartData(rrSeries.data,  ['req/s']),  [rrSeries.data]);
  const latData  = useMemo(() => toChartData(latSeries.data, ['p95 (s)']), [latSeries.data]);
  const errData  = useMemo(() => toChartData(errSeries.data, ['error%']), [errSeries.data]);

  const alerts = (activeAlerts.data as any[]) ?? [];
  const critical = alerts.filter((a: any) => a.labels?.severity === 'critical');
  const warning  = alerts.filter((a: any) => a.labels?.severity === 'warning');

  const SERVICES = [
    { name: 'Spring Boot', job: 'krishimitra-backend' },
    { name: 'ML Engine',   job: 'krishimitra-ml-engine' },
    { name: 'LLM Server',  job: 'krishimitra-llm-server' },
    { name: 'PostgreSQL',  job: 'postgres' },
    { name: 'Redis',       job: 'redis' },
    { name: 'Kafka',       job: 'kafka' },
  ];

  return (
    <div>
      {/* Alert banner */}
      {critical.length > 0 && (
        <div style={{ background: `${T.danger}22`, border: `1px solid ${T.danger}`, borderRadius: 10, padding: '10px 16px', marginBottom: 18, display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 18 }}>🔥</span>
          <span style={{ color: T.danger, fontWeight: 600 }}>{critical.length} CRITICAL alert{critical.length > 1 ? 's' : ''} firing:</span>
          <span style={{ color: T.text }}>{critical.map((a: any) => a.labels.alertname).join(', ')}</span>
        </div>
      )}

      {/* Top stat cards */}
      <div style={{ ...styles.grid6, marginBottom: 20 }}>
        <StatCard label="Services Up"    value={scalar(servicesUp.data ?? [])}     unit="/6"  color={scalar(servicesUp.data ?? []) >= 5 ? T.accent : T.danger} loading={servicesUp.isLoading} />
        <StatCard label="Request Rate"   value={(scalar(requestRate.data ?? [])).toFixed(1)} unit="rps" color={T.text}   loading={requestRate.isLoading}  sub="backend" />
        <StatCard label="p95 Latency"    value={(scalar(p95Latency.data ?? []) * 1000).toFixed(0)} unit="ms" color={scalar(p95Latency.data ?? []) > 1 ? T.warning : T.accent} loading={p95Latency.isLoading} />
        <StatCard label="Error Rate"     value={(scalar(errorRate.data ?? []) * 100).toFixed(2)}  unit="%"  color={scalar(errorRate.data ?? []) > 0.05 ? T.danger : T.accent}  loading={errorRate.isLoading} />
        <StatCard label="Custom LLM %"   value={(scalar(mlCustomPct.data ?? []) * 100).toFixed(1)} unit="%" color={scalar(mlCustomPct.data ?? []) > 0.7 ? T.accent : T.warning} loading={mlCustomPct.isLoading} sub="primary model" />
        <StatCard label="CPU Usage"      value={(scalar(cpuUsage.data ?? [])).toFixed(1)} unit="%"  color={scalar(cpuUsage.data ?? []) > 80 ? T.danger : T.text}  loading={cpuUsage.isLoading} />
      </div>

      {/* Charts row */}
      <div style={{ ...styles.grid2, marginBottom: 20 }}>
        <div style={styles.card}>
          <SectionTitle>Request Rate (last 6h)</SectionTitle>
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={rrData} margin={{ top:4, right:4, bottom:0, left:0 }}>
              <defs>
                <linearGradient id="rrGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%"  stopColor={T.accent} stopOpacity={0.3}/>
                  <stop offset="95%" stopColor={T.accent} stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke={T.border} vertical={false}/>
              <XAxis dataKey="time" tickFormatter={v => format(new Date(v), 'HH:mm')} tick={{ fontSize: 10, fill: T.muted }} tickLine={false} axisLine={false}/>
              <YAxis tick={{ fontSize: 10, fill: T.muted }} tickLine={false} axisLine={false} width={35}/>
              <Tooltip content={<CustomTooltip />}/>
              <Area dataKey="req/s" stroke={T.accent} strokeWidth={2} fill="url(#rrGrad)" dot={false}/>
            </AreaChart>
          </ResponsiveContainer>
        </div>

        <div style={styles.card}>
          <SectionTitle>p95 Latency (last 6h)</SectionTitle>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={latData} margin={{ top:4, right:4, bottom:0, left:0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={T.border} vertical={false}/>
              <XAxis dataKey="time" tickFormatter={v => format(new Date(v), 'HH:mm')} tick={{ fontSize: 10, fill: T.muted }} tickLine={false} axisLine={false}/>
              <YAxis tickFormatter={v => `${(v*1000).toFixed(0)}ms`} tick={{ fontSize: 10, fill: T.muted }} tickLine={false} axisLine={false} width={50}/>
              <Tooltip content={<CustomTooltip />}/>
              <ReferenceLine y={2} stroke={T.warning} strokeDasharray="4 3" label={{ value:'SLO', fill: T.warning, fontSize: 10 }}/>
              <Line dataKey="p95 (s)" stroke={T.info} strokeWidth={2} dot={false}/>
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Service health + Infra row */}
      <div style={{ ...styles.grid2, marginBottom: 20 }}>
        {/* Service health */}
        <div style={styles.card}>
          <SectionTitle>Service Health</SectionTitle>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {SERVICES.map(svc => (
              <div key={svc.job} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderBottom: `1px solid ${T.border}` }}>
                <StatusDot up={true} />
                <span style={{ flex: 1, fontWeight: 500 }}>{svc.name}</span>
                <span style={{ fontSize: 12, color: T.muted }}>{svc.job}</span>
                <span style={{ fontSize: 11, color: T.accent, fontWeight: 600 }}>UP</span>
              </div>
            ))}
          </div>
        </div>

        {/* Infra metrics */}
        <div style={styles.card}>
          <SectionTitle>Infrastructure</SectionTitle>
          {[
            { label: 'CPU Usage',       value: scalar(cpuUsage.data ?? []),     unit: '%',  warn: 80, critical: 90 },
            { label: 'Disk Usage',      value: scalar(diskUsagePct.data ?? []) * 100, unit: '%',  warn: 75, critical: 90 },
            { label: 'Redis Memory',    value: scalar(redisMemPct.data ?? []) * 100,  unit: '%',  warn: 75, critical: 90 },
            { label: 'Kafka Lag',       value: scalar(kafkaLag.data ?? []),    unit: ' msgs', warn: 100, critical: 1000 },
          ].map(item => (
            <div key={item.label} style={{ marginBottom: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 5 }}>
                <span style={{ fontSize: 13 }}>{item.label}</span>
                <span style={{ fontSize: 13, fontWeight: 600, color: item.value >= item.critical ? T.danger : item.value >= item.warn ? T.warning : T.accent }}>
                  {item.value.toFixed(1)}{item.unit}
                </span>
              </div>
              <div style={{ height: 5, background: T.border, borderRadius: 99, overflow: 'hidden' }}>
                <div style={{
                  height: '100%', borderRadius: 99,
                  width: `${Math.min(100, (item.value / item.critical) * 100)}%`,
                  background: item.value >= item.critical ? T.danger : item.value >= item.warn ? T.warning : T.accent,
                  transition: 'width 0.6s',
                }} />
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Active Alerts table */}
      <div style={styles.card}>
        <SectionTitle>
          Active Alerts
          <span style={{ fontSize: 11, color: T.muted }}>
            {critical.length} critical · {warning.length} warning
          </span>
        </SectionTitle>
        {alerts.length === 0
          ? <div style={{ textAlign: 'center', padding: '32px', color: T.muted }}>✅ No active alerts — all systems healthy</div>
          : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Severity','Alert','Description','Duration'].map(h => (
                    <th key={h} style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: T.muted, padding: '8px 12px', textAlign: 'left', borderBottom: `1px solid ${T.border}` }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {alerts.slice(0, 15).map((a: any, i: number) => (
                  <tr key={i} style={{ borderBottom: `1px solid ${T.border}` }}>
                    <td style={{ padding: '10px 12px' }}>
                      <span style={{
                        display: 'inline-block', padding: '2px 8px', borderRadius: 99,
                        fontSize: 11, fontWeight: 600,
                        background: a.labels.severity === 'critical' ? `${T.danger}22` : a.labels.severity === 'warning' ? `${T.warning}22` : `${T.info}22`,
                        color:      a.labels.severity === 'critical' ? T.danger : a.labels.severity === 'warning' ? T.warning : T.info,
                      }}>
                        {(a.labels.severity ?? 'info').toUpperCase()}
                      </span>
                    </td>
                    <td style={{ padding: '10px 12px', fontWeight: 500 }}>{a.labels.alertname}</td>
                    <td style={{ padding: '10px 12px', color: T.muted, fontSize: 12 }}>{a.annotations?.description ?? a.annotations?.summary ?? '—'}</td>
                    <td style={{ padding: '10px 12px', fontSize: 12, color: T.muted }}>
                      {a.activeAt ? format(new Date(a.activeAt), 'HH:mm dd/MM') : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        }
      </div>
    </div>
  );
}

function BusinessPage() {
  const { activeFarmers, newRegistrations, sellAdvisories, chatMessages, forecastsServed, mlFallbackRate } = useBusinessMetrics();

  const kpis = [
    { label: 'Active Farmers (24h)',      data: activeFarmers,    unit: '', color: T.accent },
    { label: 'New Registrations (24h)',   data: newRegistrations, unit: '', color: T.info },
    { label: 'Sell Advisories (24h)',     data: sellAdvisories,   unit: '', color: T.warning },
    { label: 'AI Chat Messages (24h)',    data: chatMessages,     unit: '', color: T.purple },
    { label: 'Forecasts Served (24h)',    data: forecastsServed,  unit: '', color: T.accent },
    { label: 'LLM Fallback Rate/min',     data: mlFallbackRate,   unit: '/min', color: T.danger },
  ];

  return (
    <div>
      <div style={{ ...styles.grid3, marginBottom: 20 }}>
        {kpis.map(kpi => (
          <StatCard
            key={kpi.label}
            label={kpi.label}
            value={Math.round(scalar(kpi.data.data ?? []))}
            unit={kpi.unit}
            color={kpi.color}
            loading={kpi.data.isLoading}
          />
        ))}
      </div>
      <div style={styles.card}>
        <SectionTitle>Business Metrics</SectionTitle>
        <p style={{ color: T.muted, fontSize: 13 }}>
          Connect to Grafana Business KPIs dashboard for time-series charts.{' '}
          <a href="http://localhost:3001/d/krishimitra-business" target="_blank" rel="noreferrer"
            style={{ color: T.accent }}>Open in Grafana →</a>
        </p>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// ROOT APP
// ─────────────────────────────────────────────────────────────

const PAGES: Record<string, React.ReactElement> = {
  overview: <OverviewPage />,
  business: <BusinessPage />,
};

function AdminApp() {
  const [page, setPage] = useState('overview');
  const [lastRefresh, setLastRefresh] = useState(new Date());

  return (
    <div style={styles.app}>
      {/* Header */}
      <header style={styles.header}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <span style={{ fontSize: 20 }}>🌾</span>
          <span style={{ fontWeight: 700, fontSize: 16, letterSpacing: '-0.02em' }}>KrishiMitra Admin</span>
          <div style={{ width: 1, height: 18, background: T.border, margin: '0 4px' }} />
          {Object.keys(PAGES).map(p => (
            <button key={p} onClick={() => setPage(p)} style={{
              background: page === p ? `${T.accent}22` : 'transparent',
              color:      page === p ? T.accent : T.muted,
              border:     'none', padding: '5px 12px', borderRadius: 8,
              cursor: 'pointer', fontSize: 13, fontWeight: page === p ? 600 : 400,
              transition: 'all 0.15s', textTransform: 'capitalize',
            }}>
              {p}
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <span style={{ fontSize: 12, color: T.muted }}>
            Updated {format(lastRefresh, 'HH:mm:ss')}
          </span>
          <a href="http://localhost:3001" target="_blank" rel="noreferrer"
            style={{ color: T.accent, fontSize: 12, fontWeight: 500 }}>
            Grafana →
          </a>
          <a href="http://localhost:9090" target="_blank" rel="noreferrer"
            style={{ color: T.info, fontSize: 12, fontWeight: 500 }}>
            Prometheus →
          </a>
          <a href="http://localhost:9093" target="_blank" rel="noreferrer"
            style={{ color: T.warning, fontSize: 12, fontWeight: 500 }}>
            Alertmanager →
          </a>
        </div>
      </header>

      {/* Main */}
      <main style={styles.main}>
        <div style={{ marginBottom: 18, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h1 style={{ fontSize: 20, fontWeight: 700, margin: 0, textTransform: 'capitalize' }}>{page}</h1>
          <span style={{ fontSize: 11, color: T.muted }}>Auto-refresh every 15s · Data from Prometheus</span>
        </div>
        {PAGES[page]}
      </main>
    </div>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={qc}>
      <AdminApp />
    </QueryClientProvider>
  );
}
