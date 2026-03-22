import React, { useState } from 'react';
import { useQuery } from 'react-query';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { priceApi, useAuthStore, useUIStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { LivePrice } from '../services/api';

// ── Mock forecast data until ML engine is live ───────────────
const MOCK_CHART: Record<string, Array<{ day:string; actual?:number; predicted?:number; lo?:number; hi?:number }>> = {
  wheat: [
    { day:'Mar 1',  actual:2050 }, { day:'Mar 5',  actual:2080 }, { day:'Mar 10', actual:2120 },
    { day:'Mar 15', actual:2150 }, { day:'Mar 21', actual:2180, predicted:2180, lo:2140, hi:2220 },
    { day:'Mar 25', predicted:2250, lo:2200, hi:2300 },
    { day:'Apr 1',  predicted:2350, lo:2270, hi:2430 },
    { day:'Apr 5',  predicted:2440, lo:2330, hi:2550 },
    { day:'Apr 10', predicted:2480, lo:2340, hi:2620 },
    { day:'Apr 15', predicted:2390, lo:2220, hi:2560 },
  ],
  soybean: [
    { day:'Mar 1',  actual:4400 }, { day:'Mar 5',  actual:4450 }, { day:'Mar 10', actual:4530 },
    { day:'Mar 15', actual:4580 }, { day:'Mar 21', actual:4620, predicted:4620, lo:4570, hi:4670 },
    { day:'Mar 25', predicted:4650, lo:4590, hi:4710 },
    { day:'Apr 1',  predicted:4630, lo:4550, hi:4710 },
    { day:'Apr 5',  predicted:4570, lo:4470, hi:4670 },
    { day:'Apr 10', predicted:4500, lo:4380, hi:4620 },
  ],
  tomato: [
    { day:'Mar 1',  actual:1100 }, { day:'Mar 5',  actual:980 }, { day:'Mar 10', actual:920 },
    { day:'Mar 15', actual:870 },  { day:'Mar 21', actual:840, predicted:840, lo:820, hi:860 },
    { day:'Mar 25', predicted:780, lo:740, hi:820 },
    { day:'Apr 1',  predicted:700, lo:650, hi:750 },
    { day:'Apr 5',  predicted:630, lo:570, hi:690 },
  ],
};

const CROPS_MOCK = [
  { name:'Wheat',   icon:'🌾', qty:'8 qtl', status:'WAIT',     statusColor:'amber', priceNow:2180, priceTarget:2480, daysLeft:15, pct:45 },
  { name:'Soybean', icon:'🫘', qty:'5 qtl', status:'SELL NOW', statusColor:'green', priceNow:4620, priceTarget:4620, daysLeft:0,  pct:100 },
  { name:'Tomato',  icon:'🍅', qty:'2 qtl', status:'URGENT',   statusColor:'red',   priceNow:840,  priceTarget:700,  daysLeft:-3, pct:0 },
];

const MANDIS_MOCK = [
  { name:'Indore',  dist:'0 km',  wheat:2180, demand:94 },
  { name:'Harda',   dist:'78 km', wheat:2290, demand:88 },
  { name:'Ujjain',  dist:'55 km', wheat:2240, demand:84 },
  { name:'Dewas',   dist:'38 km', wheat:2165, demand:71 },
];

const STATS = [
  { label:'Wheat Price',    value:'₹2,180', unit:'/qtl', change:'+3.2%', up:true,  sub:'Indore mandi' },
  { label:'Soybean Price',  value:'₹4,620', unit:'/qtl', change:'+1.8%', up:true,  sub:'Ujjain mandi' },
  { label:'Expected Gain',  value:'₹340',   unit:'/qtl', change:'in 15 days', up:true, sub:'If you wait' },
  { label:'Active Alerts',  value:'3',      unit:'',      change:'2 urgent',  up:false, sub:'View all' },
];

function CustomTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div style={{
      background:'var(--bg-surface)', border:'1px solid var(--border-md)',
      borderRadius:'var(--r-md)', padding:'10px 14px',
      boxShadow:'var(--shadow-md)', fontSize:12,
    }}>
      <div style={{ fontWeight:600, marginBottom:6 }}>{label}</div>
      {payload.map((p: any, i: number) => p.value != null && (
        <div key={i} style={{ color: p.color ?? 'var(--text-secondary)', marginBottom:2 }}>
          <span style={{ color:'var(--text-muted)' }}>{p.name}: </span>
          <strong>₹{p.value?.toLocaleString('en-IN')}</strong>
        </div>
      ))}
    </div>
  );
}

export default function Dashboard() {
  const { isMobile, isTablet } = useDevice();
  const farmer    = useAuthStore(s => s.farmer);
  const setPage   = useUIStore(s => s.setPage);
  const [crop, setCrop] = useState<'wheat'|'soybean'|'tomato'>('wheat');
  const chartColor: Record<string,string> = { wheat:'var(--c-green)', soybean:'var(--c-amber)', tomato:'var(--c-red)' };
  const color = chartColor[crop];

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth:'var(--content-max)', margin:'0 auto' }}>

      {/* Header */}
      <div className="flex items-start justify-between" style={{ marginBottom:24, flexWrap:'wrap', gap:12 }}>
        <div>
          <h1 style={{ marginBottom:4 }}>नमस्ते, {farmer?.name?.split(' ')[0] ?? 'Kisan'} 👋</h1>
          <p style={{ fontSize:14 }}>Wheat prices are up 3.2% this week — optimal sell window approaching</p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-secondary btn-sm" onClick={() => setPage('chat')}>Ask AI</button>
          <button className="btn btn-primary btn-sm" onClick={() => setPage('sell')}>Sell Advice →</button>
        </div>
      </div>

      {/* Stat Cards */}
      <div className={`grid-${isMobile ? 2 : 4}`} style={{ marginBottom:20 }}>
        {STATS.map((s, i) => (
          <div key={i} className="card card-pad-sm fade-in" style={{ animationDelay:`${i*0.06}s` }}>
            <div className="stat-label" style={{ marginBottom:8 }}>{s.label}</div>
            <div className="flex items-baseline gap-1" style={{ marginBottom:4 }}>
              <span className="stat-value" style={{ fontSize: isMobile ? 20 : 24 }}>{s.value}</span>
              {s.unit && <span style={{ fontSize:12, color:'var(--text-muted)' }}>{s.unit}</span>}
            </div>
            <div className="flex items-center justify-between">
              <span className={`stat-change ${s.up ? 'up':'down'}`}>{s.up ? '▲':'▼'} {s.change}</span>
              <span style={{ fontSize:11, color:'var(--text-muted)' }}>{s.sub}</span>
            </div>
          </div>
        ))}
      </div>

      {/* Main grid */}
      <div style={{ display:'grid', gridTemplateColumns: isMobile||isTablet ? '1fr' : '1fr 300px', gap:18, marginBottom:18 }}>

        {/* Price Chart */}
        <div className="card card-pad">
          <div className="flex items-center justify-between" style={{ marginBottom:16, flexWrap:'wrap', gap:10 }}>
            <div>
              <h2 style={{ fontSize:15, marginBottom:2 }}>Price Forecast</h2>
              <p style={{ fontSize:12, color:'var(--text-muted)' }}>30-day ARIMA + LSTM ensemble with confidence band</p>
            </div>
            <div className="flex gap-2">
              {(['wheat','soybean','tomato'] as const).map(c => (
                <button key={c} onClick={() => setCrop(c)}
                  className={`btn btn-sm ${crop===c?'btn-primary':'btn-ghost'}`}
                  style={{ textTransform:'capitalize' }}>{c}</button>
              ))}
            </div>
          </div>

          <div style={{ height:230 }}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={MOCK_CHART[crop]} margin={{ top:6, right:6, bottom:0, left:0 }}>
                <defs>
                  <linearGradient id={`g-${crop}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%"  stopColor={color} stopOpacity={0.18}/>
                    <stop offset="95%" stopColor={color} stopOpacity={0}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false}/>
                <XAxis dataKey="day" tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false}/>
                <YAxis tickFormatter={v => `₹${(v/1000).toFixed(1)}k`}
                  tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false} width={48}/>
                <Tooltip content={<CustomTooltip />}/>
                <Area dataKey="hi" stroke="none" fill={`url(#g-${crop})`} connectNulls legendType="none" name="Upper 95%" />
                <Area dataKey="lo" stroke="none" fill="var(--bg-surface)" connectNulls legendType="none" name="Lower 95%"/>
                <Area dataKey="actual"    name="Actual price" stroke={color} strokeWidth={2.5}
                  fill={`url(#g-${crop})`} dot={{ r:3, fill:color, strokeWidth:0 }} connectNulls activeDot={{ r:5 }}/>
                <Area dataKey="predicted" name="Predicted"    stroke={color} strokeWidth={2} strokeDasharray="5 3"
                  fill="none" dot={false} connectNulls/>
              </AreaChart>
            </ResponsiveContainer>
          </div>

          {/* Recommendation strip */}
          <div style={{
            marginTop:12, padding:'9px 13px',
            background:'var(--bg-elevated)', borderRadius:'var(--r-md)',
            border:`1px solid ${color}33`,
            display:'flex', alignItems:'center', gap:10, flexWrap:'wrap',
          }}>
            <div style={{ width:7, height:7, borderRadius:'50%', background:color, flexShrink:0 }}/>
            <span style={{ fontSize:12, color:'var(--text-primary)' }}>
              <strong>AI Rec: </strong>
              {crop==='wheat' ? 'Wait 15 days → sell at Harda mandi (₹2,480 expected)' :
               crop==='soybean' ? 'Sell now — price declining after Mar 25' :
               '⚠️ Sell immediately — price dropping fast'}
            </span>
            <span className="price-tag" style={{ marginLeft:'auto', fontSize:12 }}>
              {crop==='wheat' ? '₹2,480' : crop==='soybean' ? '₹4,650' : '₹840'}
            </span>
          </div>
        </div>

        {/* Right panel */}
        {!isMobile && (
          <div className="flex-col gap-4">
            {/* Weather */}
            <div className="card card-pad-sm">
              <div className="flex justify-between items-start" style={{ marginBottom:10 }}>
                <div>
                  <div style={{ fontWeight:600, fontSize:14 }}>Weather</div>
                  <div style={{ fontSize:11, color:'var(--text-muted)' }}>Indore, MP</div>
                </div>
                <div style={{ textAlign:'right' }}>
                  <div className="stat-value" style={{ fontSize:26 }}>34°</div>
                  <div style={{ fontSize:11, color:'var(--text-muted)' }}>Clear</div>
                </div>
              </div>
              <div style={{ display:'grid', gridTemplateColumns:'repeat(4,1fr)', gap:4 }}>
                {[{d:'Today',i:'☀️',h:34,l:21,r:0},{d:'Fri',i:'⛅',h:32,l:22,r:10},{d:'Sat',i:'🌧️',h:28,l:19,r:65},{d:'Sun',i:'⛅',h:30,l:20,r:30}].map((f,i)=>(
                  <div key={i} style={{ textAlign:'center', padding:'5px 3px' }}>
                    <div style={{ fontSize:10, color:'var(--text-muted)', marginBottom:3 }}>{f.d}</div>
                    <div style={{ fontSize:16, marginBottom:2 }}>{f.i}</div>
                    <div style={{ fontSize:11, fontWeight:600 }}>{f.h}°</div>
                    <div style={{ fontSize:10, color:'var(--text-muted)' }}>{f.l}°</div>
                    {f.r>0&&<div style={{ fontSize:9, color:'var(--info)', marginTop:2 }}>💧{f.r}%</div>}
                  </div>
                ))}
              </div>
              <div style={{ marginTop:8, padding:'6px 9px', background:'var(--info-dim)', borderRadius:'var(--r-sm)', fontSize:11, color:'var(--info)' }}>
                ⚠️ Rain Sat — harvest tomatoes by Fri
              </div>
            </div>

            {/* AI Insight card */}
            <div style={{
              background:'var(--accent-dim)', border:'1px solid var(--accent-border)',
              borderRadius:'var(--r-lg)', padding:14,
            }}>
              <div className="flex items-center gap-2" style={{ marginBottom:8 }}>
                <span style={{ fontSize:18 }}>🤖</span>
                <span style={{ fontWeight:600, fontSize:13, color:'var(--accent)' }}>AI Insight</span>
              </div>
              <p style={{ fontSize:12, color:'var(--text-primary)', lineHeight:1.55, margin:0 }}>
                Mandi arrivals in Indore down <strong>23%</strong> this week.
                Festival demand picking up. Wheat prices expected to
                <strong> peak ~Apr 5</strong>.
              </p>
              <button className="btn btn-primary btn-sm" style={{ marginTop:10 }} onClick={() => setPage('chat')}>
                Ask more →
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Bottom grid */}
      <div style={{ display:'grid', gridTemplateColumns: isMobile ? '1fr' : '1fr 1fr', gap:18 }}>

        {/* My Crops */}
        <div className="card card-pad">
          <div className="flex justify-between items-center" style={{ marginBottom:14 }}>
            <h2 style={{ fontSize:15 }}>My Crops</h2>
            <button className="btn btn-ghost btn-sm">+ Add Crop</button>
          </div>
          <div className="flex-col gap-2">
            {CROPS_MOCK.map((c,i) => (
              <div key={i} style={{
                background:'var(--bg-elevated)', borderRadius:'var(--r-md)',
                padding:'11px 13px', border:'1px solid var(--border)',
                borderLeft:`3px solid var(--c-${c.statusColor==='amber'?'amber':c.statusColor==='green'?'green':'red'})`,
              }}>
                <div className="flex items-center gap-2">
                  <span style={{ fontSize:24 }}>{c.icon}</span>
                  <div style={{ flex:1, minWidth:0 }}>
                    <div className="flex items-center gap-2" style={{ marginBottom:2 }}>
                      <span style={{ fontWeight:600, fontSize:13 }}>{c.name}</span>
                      <span className={`badge badge-${c.statusColor==='amber'?'amber':c.statusColor==='green'?'green':'red'}`}>
                        {c.status}
                      </span>
                    </div>
                    <div style={{ fontSize:11, color:'var(--text-muted)' }}>{c.qty}</div>
                    <div className="flex gap-3" style={{ marginTop:5 }}>
                      <span style={{ fontSize:12 }}>Now: <strong>₹{c.priceNow.toLocaleString('en-IN')}</strong></span>
                      {c.daysLeft > 0 && <span style={{ fontSize:12, color:'var(--c-green)' }}>Target: ₹{c.priceTarget} (+{c.daysLeft}d)</span>}
                      {c.daysLeft < 0 && <span style={{ fontSize:12, color:'var(--c-red)' }}>Declining ↓</span>}
                    </div>
                  </div>
                  <button className="btn btn-ghost btn-sm" onClick={() => setPage('simulator')}>Sim →</button>
                </div>
                {c.daysLeft > 0 && (
                  <div style={{ marginTop:8 }}>
                    <div className="progress"><div className="progress-fill" style={{ width:`${c.pct}%` }}/></div>
                    <div style={{ fontSize:10, color:'var(--text-muted)', marginTop:3 }}>{c.daysLeft} days until optimal window</div>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Top Mandis */}
        <div className="card card-pad">
          <div className="flex justify-between items-center" style={{ marginBottom:14 }}>
            <h2 style={{ fontSize:15 }}>Mandis Near You</h2>
            <button className="btn btn-ghost btn-sm" onClick={() => setPage('mandi')}>View all</button>
          </div>
          <table className="data-table">
            <thead>
              <tr><th>Mandi</th><th>Wheat ₹/qtl</th><th>Demand</th></tr>
            </thead>
            <tbody>
              {MANDIS_MOCK.map((m,i) => (
                <tr key={i}>
                  <td>
                    <div style={{ fontWeight:500, fontSize:13 }}>{m.name}</div>
                    <div style={{ fontSize:11, color:'var(--text-muted)' }}>{m.dist}</div>
                  </td>
                  <td><span className="price-tag">₹{m.wheat.toLocaleString('en-IN')}</span></td>
                  <td>
                    <div className="flex items-center gap-2">
                      <div className="progress" style={{ width:52 }}>
                        <div className="progress-fill" style={{ width:`${m.demand}%`, background: m.demand>85?'var(--c-green)':'var(--c-amber)' }}/>
                      </div>
                      <span style={{ fontSize:12 }}>{m.demand}%</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
