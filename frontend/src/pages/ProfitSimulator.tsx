import React, { useState, useMemo } from 'react';
import { useMutation } from 'react-query';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Cell, ReferenceLine,
} from 'recharts';
import toast from 'react-hot-toast';
import { sellApi } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { ProfitSim } from '../services/api';

const COMMODITIES = ['Wheat','Soybean','Onion','Tomato','Potato','Cotton','Maize','Gram'];
const MANDIS      = ['Indore','Ujjain','Dewas','Harda','Bhopal','Vidisha','Khandwa','Sehore'];

const STORAGE_RATE: Record<string,number> = {
  Wheat:2.5, Soybean:4, Onion:6, Tomato:8, Potato:5, Cotton:3, Maize:1.5, Gram:2
};

const ALL_HORIZONS = [0,1,3,5,7,10,12,15,18,21,25,30];

// Local simulation (before backend responds)
function localSim(commodity: string, quantity: number, waitDays: number, currentPrice: number) {
  const trend = { Wheat:15, Soybean:5, Tomato:-35, Onion:-15, Cotton:20, Maize:8, Gram:7, Potato:-5 };
  const storageRate = STORAGE_RATE[commodity] ?? 3;
  const predicted = Math.max(currentPrice + (trend[commodity as keyof typeof trend] ?? 0) * waitDays / 7, 0);
  const storage   = storageRate * waitDays;
  const transport = 800;
  const gross     = predicted * quantity;
  const net       = gross - storage - transport;
  const sellNow   = currentPrice * quantity - transport;
  return { predicted, storage, transport, gross, net, profitVsNow: net - sellNow };
}

const BASE_PRICES: Record<string,number> = {
  Wheat:2180, Soybean:4620, Onion:1200, Tomato:840, Potato:960, Cotton:6500, Maize:1850, Gram:5400
};

function SimTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  const v = payload[0]?.value;
  return (
    <div style={{ background:'var(--bg-surface)', border:'1px solid var(--border-md)', borderRadius:'var(--r-md)', padding:'8px 12px', fontSize:12 }}>
      <div style={{ fontWeight:600, marginBottom:4 }}>Wait {label} days</div>
      <div style={{ color: v >= 0 ? 'var(--c-green)' : 'var(--c-red)' }}>
        {v >= 0 ? '+' : ''}₹{Math.abs(v).toLocaleString('en-IN')} vs selling today
      </div>
    </div>
  );
}

export default function ProfitSimulator() {
  const { isMobile } = useDevice();
  const [commodity, setCommodity] = useState('Wheat');
  const [mandi,     setMandi]     = useState('Indore');
  const [quantity,  setQuantity]  = useState(8);
  const [waitDays,  setWaitDays]  = useState(0);

  const currentPrice = BASE_PRICES[commodity] ?? 2000;

  const { data: backendSim, mutate: runSim, isLoading } = useMutation(
    () => sellApi.simulate({ commodity, mandi, quantityQuintal: quantity, waitDays }),
    { onError: () => toast.error('Backend unavailable — using local estimate') }
  );

  // Use backend data or local fallback
  const sim = useMemo<Partial<ProfitSim>>(() => {
    if (backendSim) return backendSim;
    const l = localSim(commodity, quantity, waitDays, currentPrice);
    return {
      currentPrice, quantity, waitDays,
      predictedPrice: l.predicted,
      storageCost:    l.storage,
      transportCost:  l.transport,
      grossRevenue:   l.gross,
      netRevenue:     l.net,
      profitVsNow:    l.profitVsNow,
      scenarioChart:  ALL_HORIZONS.map(d => {
        const s = localSim(commodity, quantity, d, currentPrice);
        return { days: d, profit: s.profitVsNow };
      }),
    };
  }, [backendSim, commodity, quantity, waitDays, currentPrice]);

  const best = useMemo(() => {
    if (!sim.scenarioChart) return { days: 0, profit: 0 };
    return sim.scenarioChart.reduce((b, s) => s.profit > b.profit ? s : b, sim.scenarioChart[0]);
  }, [sim.scenarioChart]);

  const breakdown = [
    { label:'Gross Revenue', value:  sim.grossRevenue ?? 0, color:'var(--c-green)',  pct:100 },
    { label:'Storage Cost',  value: -(sim.storageCost ?? 0), color:'var(--c-amber)', pct: ((sim.storageCost ?? 0) / (sim.grossRevenue ?? 1)) * 100 },
    { label:'Transport',     value: -(sim.transportCost ?? 0), color:'var(--c-blue)', pct: ((sim.transportCost ?? 0) / (sim.grossRevenue ?? 1)) * 100 },
    { label:'Net Revenue',   value:  sim.netRevenue ?? 0, color:'var(--accent)',     pct: ((sim.netRevenue ?? 0) / (sim.grossRevenue ?? 1)) * 100, bold: true },
  ];

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth:'var(--content-max)', margin:'0 auto' }}>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{ marginBottom: 4 }}>Profit Simulator</h1>
        <p style={{ fontSize: 14 }}>What-if engine — calculate exact profit for every scenario</p>
      </div>

      <div style={{ display:'grid', gridTemplateColumns: isMobile ? '1fr' : '340px 1fr', gap:20 }}>
        {/* Controls */}
        <div className="flex-col gap-4">
          <div className="card card-pad">
            <h2 style={{ fontSize:15, marginBottom:16 }}>Simulation Inputs</h2>
            <div className="flex-col gap-4">

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:6 }}>CROP</label>
                <select value={commodity} onChange={e => setCommodity(e.target.value)} className="select">
                  {COMMODITIES.map(c => <option key={c}>{c}</option>)}
                </select>
              </div>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:6 }}>TARGET MANDI</label>
                <select value={mandi} onChange={e => setMandi(e.target.value)} className="select">
                  {MANDIS.map(m => <option key={m}>{m}</option>)}
                </select>
              </div>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:6 }}>
                  QUANTITY — <span style={{ color:'var(--accent)' }}>{quantity} quintal</span>
                </label>
                <input type="range" min={1} max={100} value={quantity}
                  onChange={e => setQuantity(+e.target.value)}
                  style={{ width:'100%', accentColor:'var(--accent)' }}/>
                <div className="flex justify-between" style={{ fontSize:11, color:'var(--text-muted)', marginTop:3 }}>
                  <span>1 qtl</span><span>100 qtl</span>
                </div>
              </div>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:6 }}>
                  WAIT DAYS — <span style={{ color:'var(--accent)' }}>{waitDays === 0 ? 'Sell today' : `${waitDays} days`}</span>
                </label>
                <input type="range" min={0} max={30} value={waitDays}
                  onChange={e => setWaitDays(+e.target.value)}
                  style={{ width:'100%', accentColor:'var(--accent)' }}/>
                <div className="flex justify-between" style={{ fontSize:11, color:'var(--text-muted)', marginTop:3 }}>
                  <span>Sell now</span><span>30 days</span>
                </div>
              </div>

              <button
                className="btn btn-primary"
                onClick={() => runSim()}
                disabled={isLoading}
                style={{ marginTop:4 }}
              >
                {isLoading
                  ? <><div style={{ width:14, height:14, border:'2px solid rgba(255,255,255,.4)', borderTopColor:'white', borderRadius:'50%', animation:'spin .7s linear infinite' }} /> Calculating…</>
                  : 'Get ML Prediction →'}
              </button>
            </div>
          </div>

          {/* Optimal strategy */}
          <div style={{
            background: best.profit > 0 ? 'var(--accent-dim)' : 'var(--danger-dim)',
            border: `1px solid ${best.profit > 0 ? 'var(--accent-border)' : 'var(--danger)'}`,
            borderRadius:'var(--r-lg)', padding:16,
          }}>
            <div style={{ fontSize:11, fontWeight:600, textTransform:'uppercase', letterSpacing:'0.06em', color: best.profit > 0 ? 'var(--accent)':'var(--danger)', marginBottom:8 }}>
              🎯 Optimal Strategy
            </div>
            <div className="stat-value" style={{ fontSize:22, marginBottom:6 }}>
              {best.days === 0 ? 'Sell Today' : `Wait ${best.days} days`}
            </div>
            <div style={{ fontSize:13, color:'var(--text-secondary)', marginBottom:10 }}>
              Extra profit vs selling today:
            </div>
            <div className="stat-value" style={{ fontSize:28, color: best.profit >= 0 ? 'var(--c-green)':'var(--c-red)' }}>
              {best.profit >= 0 ? '+' : ''}₹{Math.abs(best.profit).toLocaleString('en-IN', { maximumFractionDigits:0 })}
            </div>
            <div style={{ fontSize:12, color:'var(--text-muted)', marginTop:4 }}>Sell at {mandi} mandi</div>
          </div>
        </div>

        {/* Results */}
        <div className="flex-col gap-4">
          {/* Live result cards */}
          <div className="grid-3" style={{ gap:12 }}>
            {[
              { label:'Sell Price',   val:`₹${(sim.predictedPrice ?? currentPrice).toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:'per quintal', color:'var(--c-green)' },
              { label:'Storage Cost', val:`-₹${(sim.storageCost ?? 0).toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:`${waitDays}d × ${quantity}qtl`, color:'var(--c-amber)' },
              { label:'Net Revenue',  val:`₹${(sim.netRevenue ?? 0).toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:'after all costs', color:'var(--accent)' },
            ].map((item,i) => (
              <div key={i} className="card card-pad-sm">
                <div className="stat-label" style={{ marginBottom:6 }}>{item.label}</div>
                <div className="stat-value" style={{ fontSize:20, color:item.color }}>{item.val}</div>
                <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:3 }}>{item.sub}</div>
              </div>
            ))}
          </div>

          {/* Cost breakdown */}
          <div className="card card-pad">
            <h3 style={{ fontSize:14, marginBottom:14 }}>Cost Breakdown</h3>
            {breakdown.map((row,i) => (
              <div key={i} style={{ marginBottom:11 }}>
                <div className="flex justify-between" style={{ marginBottom:5 }}>
                  <span style={{ fontSize:13, fontWeight: row.bold ? 600 : 400 }}>{row.label}</span>
                  <span style={{ fontSize:13, fontWeight: row.bold ? 700 : 500, color:row.color }}>
                    {row.value >= 0 ? '' : '−'}₹{Math.abs(row.value).toLocaleString('en-IN', { maximumFractionDigits:0 })}
                  </span>
                </div>
                <div className="progress">
                  <div className="progress-fill" style={{ width:`${Math.min(100, Math.abs(row.pct))}%`, background:row.color }}/>
                </div>
              </div>
            ))}
          </div>

          {/* Scenario chart */}
          <div className="card card-pad">
            <h3 style={{ fontSize:14, marginBottom:4 }}>Profit vs Wait Days</h3>
            <p style={{ fontSize:12, color:'var(--text-muted)', marginBottom:14 }}>Extra profit compared to selling today</p>
            <div style={{ height:200 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={sim.scenarioChart ?? []} margin={{ top:4, right:4, bottom:0, left:0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)"/>
                  <XAxis dataKey="days" tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false}
                    tickFormatter={v => `${v}d`}/>
                  <YAxis tickFormatter={v => `₹${(v/1000).toFixed(0)}k`}
                    tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false} width={46}/>
                  <Tooltip content={<SimTooltip />}/>
                  <ReferenceLine y={0} stroke="var(--border-md)" strokeWidth={1}/>
                  <Bar dataKey="profit" radius={[4,4,0,0]}>
                    {(sim.scenarioChart ?? []).map((s, i) => (
                      <Cell
                        key={i}
                        fill={s.days === waitDays ? 'var(--accent)'
                          : s.profit >= 0 ? 'var(--c-green)' : 'var(--c-red)'}
                        opacity={s.days === waitDays ? 1 : 0.55}
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
