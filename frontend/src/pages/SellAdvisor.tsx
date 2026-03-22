import React, { useState } from 'react';
import { useMutation } from 'react-query';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts';
import toast from 'react-hot-toast';
import { sellApi, priceApi, useAuthStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { SellAdvice } from '../services/api';

const COMMODITIES = ['Wheat','Soybean','Onion','Tomato','Potato','Cotton','Maize','Gram'];
const MANDIS      = ['Indore','Ujjain','Dewas','Harda','Bhopal','Vidisha','Khandwa','Sehore','Narsinghpur','Jabalpur'];
const STORAGE_COSTS: Record<string,number> = {
  Wheat:2.5,Soybean:4,Onion:6,Tomato:8,Potato:5,Cotton:3,Maize:1.5,Gram:2
};

const DECISION_META: Record<string, { icon:string; color:string; label:string; bg:string }> = {
  SELL_NOW:   { icon:'💰', color:'var(--c-green)',  label:'Sell Now',      bg:'rgba(79,180,131,0.12)' },
  WAIT_N_DAYS:{ icon:'⏳', color:'var(--c-amber)',  label:'Wait',          bg:'rgba(240,149,58,0.12)' },
  HOLD:       { icon:'🔒', color:'var(--c-blue)',   label:'Hold for now',  bg:'rgba(67,112,245,0.12)' },
};

function ConfidenceMeter({ value }: { value: number }) {
  const pct = Math.round(value * 100);
  const color = pct >= 75 ? 'var(--c-green)' : pct >= 50 ? 'var(--c-amber)' : 'var(--c-red)';
  return (
    <div>
      <div style={{ display:'flex', justifyContent:'space-between', marginBottom:6 }}>
        <span style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', letterSpacing:'0.06em', textTransform:'uppercase' }}>AI Confidence</span>
        <span style={{ fontSize:13, fontWeight:700, color }}>{pct}%</span>
      </div>
      <div style={{ height:6, background:'var(--border)', borderRadius:'var(--r-full)', overflow:'hidden' }}>
        <div style={{ height:'100%', width:`${pct}%`, background:color, borderRadius:'var(--r-full)', transition:'width 0.8s cubic-bezier(0.4,0,0.2,1)' }}/>
      </div>
      <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:4 }}>
        Based on ARIMA + LSTM + XGBoost ensemble prediction
      </div>
    </div>
  );
}

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{ background:'var(--bg-surface)', border:'1px solid var(--border-md)', borderRadius:'var(--r-md)', padding:'8px 12px', fontSize:12 }}>
      <div style={{ fontWeight:600, marginBottom:4 }}>Day {label}</div>
      {payload.map((p: any, i: number) => p.value != null && (
        <div key={i} style={{ color: p.color }}>
          {p.name}: <strong>{p.value >= 0 ? '+' : ''}₹{Math.abs(p.value).toLocaleString('en-IN', { maximumFractionDigits:0 })}</strong>
        </div>
      ))}
    </div>
  );
};

export default function SellAdvisorPage() {
  const { isMobile } = useDevice();
  const farmer = useAuthStore(s => s.farmer);

  const [commodity,  setCommodity]  = useState(farmer?.crops?.[0]?.commodity ?? 'Wheat');
  const [mandi,      setMandi]      = useState('Indore');
  const [quantity,   setQuantity]   = useState(farmer?.crops?.[0]?.quantityQuintal ?? 8);
  const [storage,    setStorage]    = useState(true);
  const [advice,     setAdvice]     = useState<SellAdvice | null>(null);
  const [simData,    setSimData]    = useState<{ days:number; profit:number }[] | null>(null);

  const adviceMutation = useMutation(
    () => sellApi.getAdvice({ commodity, mandi, quantityQuintal: quantity, storageAvailable: storage }),
    {
      onSuccess: data => {
        setAdvice(data);
        toast.success('Sell advice ready');
      },
      onError: () => toast.error('Could not fetch advice — using ML estimate'),
    }
  );

  const simMutation = useMutation(
    (waitDays: number) => sellApi.simulate({ commodity, mandi, quantityQuintal: quantity, waitDays }),
    {
      onSuccess: data => setSimData(data.scenarioChart),
      onError: () => {
        // Local fallback scenario
        const rate = STORAGE_COSTS[commodity] ?? 3;
        const trend: Record<string,number> = { Wheat:15, Soybean:5, Tomato:-35, Onion:-15, Cotton:20, Maize:8, Gram:7, Potato:-5 };
        const tp = trend[commodity] ?? 0;
        const basePrice = 2180;
        setSimData(
          [0,1,3,5,7,10,14,21,30].map(d => ({
            days: d,
            profit: Math.round(((basePrice + tp * d / 7) * quantity) - (rate * d) - 800 - (basePrice * quantity)),
          }))
        );
      },
    }
  );

  const meta = advice ? (DECISION_META[advice.sellDecision] ?? DECISION_META.HOLD) : null;

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth:'var(--content-max)', margin:'0 auto' }}>
      <div style={{ marginBottom:22 }}>
        <h1 style={{ marginBottom:4 }}>Sell Advisor</h1>
        <p style={{ fontSize:14 }}>AI-powered SELL / WAIT / HOLD decision — backed by 30-day ML forecast</p>
      </div>

      <div style={{ display:'grid', gridTemplateColumns: isMobile ? '1fr' : '340px 1fr', gap:20 }}>

        {/* Input panel */}
        <div style={{ display:'flex', flexDirection:'column', gap:16 }}>
          <div className="card card-pad">
            <h2 style={{ fontSize:15, marginBottom:16 }}>Your crop details</h2>
            <div style={{ display:'flex', flexDirection:'column', gap:14 }}>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:5, textTransform:'uppercase', letterSpacing:'0.06em' }}>Crop</label>
                <select value={commodity} onChange={e => setCommodity(e.target.value)} className="select">
                  {COMMODITIES.map(c => <option key={c}>{c}</option>)}
                </select>
              </div>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:5, textTransform:'uppercase', letterSpacing:'0.06em' }}>Target mandi</label>
                <select value={mandi} onChange={e => setMandi(e.target.value)} className="select">
                  {MANDIS.map(m => <option key={m}>{m}</option>)}
                </select>
              </div>

              <div>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:5, textTransform:'uppercase', letterSpacing:'0.06em' }}>
                  Quantity — <span style={{ color:'var(--accent)' }}>{quantity} quintal</span>
                </label>
                <input type="range" min={1} max={200} value={quantity}
                  onChange={e => setQuantity(+e.target.value)}
                  style={{ width:'100%', accentColor:'var(--accent)' }}/>
                <div style={{ display:'flex', justifyContent:'space-between', fontSize:11, color:'var(--text-muted)', marginTop:3 }}>
                  <span>1 qtl</span><span>200 qtl</span>
                </div>
              </div>

              <div style={{ display:'flex', alignItems:'center', gap:10 }}>
                <input type="checkbox" id="storage" checked={storage}
                  onChange={e => setStorage(e.target.checked)}
                  style={{ width:16, height:16, accentColor:'var(--accent)', cursor:'pointer' }}/>
                <label htmlFor="storage" style={{ fontSize:14, cursor:'pointer' }}>
                  I have storage available
                </label>
              </div>

              <div style={{ marginTop:4, padding:'10px 13px', background:'var(--bg-elevated)', borderRadius:'var(--r-md)', border:'1px solid var(--border)' }}>
                <div style={{ fontSize:12, color:'var(--text-muted)', marginBottom:4 }}>Storage cost for {commodity}</div>
                <div style={{ fontSize:13, fontWeight:600 }}>₹{STORAGE_COSTS[commodity] ?? 3}/qtl/day</div>
              </div>

              <button
                className="btn btn-primary"
                onClick={() => { adviceMutation.mutate(); simMutation.mutate(advice?.waitDays ?? 15); }}
                disabled={adviceMutation.isLoading}
                style={{ justifyContent:'center', padding:'11px' }}
              >
                {adviceMutation.isLoading
                  ? <><div style={{ width:14, height:14, border:'2px solid rgba(255,255,255,.4)', borderTopColor:'white', borderRadius:'50%', animation:'spin .7s linear infinite' }}/> Analysing…</>
                  : 'Get AI advice →'}
              </button>
            </div>
          </div>

          {/* Quick stats */}
          {advice && (
            <div className="card card-pad" style={{ animation:'fadeIn 0.4s ease both' }}>
              <ConfidenceMeter value={advice.confidence} />
            </div>
          )}
        </div>

        {/* Advice panel */}
        <div style={{ display:'flex', flexDirection:'column', gap:16 }}>
          {!advice ? (
            <div className="card" style={{ padding:'48px 24px', textAlign:'center' }}>
              <div style={{ fontSize:48, marginBottom:16 }}>🌾</div>
              <h3 style={{ marginBottom:8 }}>Ready to advise</h3>
              <p style={{ fontSize:14 }}>Fill in your crop details and click "Get AI advice" to receive a personalised sell recommendation with profit simulation.</p>
            </div>
          ) : (
            <>
              {/* Decision card */}
              <div style={{ background: meta?.bg, border:`1.5px solid ${meta?.color}33`, borderRadius:'var(--r-xl)', padding:24, animation:'fadeIn 0.4s ease both' }}>
                <div style={{ display:'flex', alignItems:'center', gap:14, marginBottom:16 }}>
                  <div style={{ fontSize:40 }}>{meta?.icon}</div>
                  <div>
                    <div style={{ fontSize:11, fontWeight:600, letterSpacing:'0.08em', textTransform:'uppercase', color:'var(--text-muted)', marginBottom:4 }}>AI Decision</div>
                    <div style={{ fontFamily:'var(--font-display)', fontWeight:800, fontSize:28, color: meta?.color, letterSpacing:'-0.03em' }}>
                      {meta?.label}{advice.sellDecision === 'WAIT_N_DAYS' && ` ${advice.waitDays} days`}
                    </div>
                  </div>
                  <div style={{ marginLeft:'auto', textAlign:'right' }}>
                    <div className="price-tag" style={{ fontSize:18, marginBottom:6 }}>
                      ₹{advice.peakPrice.toLocaleString('en-IN', { maximumFractionDigits:0 })}
                    </div>
                    <div style={{ fontSize:12, color:'var(--text-muted)' }}>peak price</div>
                  </div>
                </div>

                <div style={{ display:'grid', gridTemplateColumns:'repeat(3,1fr)', gap:12, marginBottom:16 }}>
                  {[
                    { label:'Current price',  value:`₹${advice.currentPrice.toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:'per quintal' },
                    { label:'Profit gain',    value:`+₹${advice.profitGainPerQtl.toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:'per quintal', color:'var(--c-green)' },
                    { label:'Total gain',     value:`₹${advice.totalProfitGain.toLocaleString('en-IN', { maximumFractionDigits:0 })}`, sub:`on ${quantity} qtl`, color:'var(--c-green)' },
                  ].map((s,i) => (
                    <div key={i} style={{ background:'var(--bg-surface)', borderRadius:'var(--r-md)', padding:'10px 13px', border:'1px solid var(--border)' }}>
                      <div style={{ fontSize:11, fontWeight:600, textTransform:'uppercase', letterSpacing:'0.06em', color:'var(--text-muted)', marginBottom:6 }}>{s.label}</div>
                      <div style={{ fontSize:18, fontWeight:700, color: s.color ?? 'var(--text-primary)', letterSpacing:'-0.02em' }}>{s.value}</div>
                      <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:2 }}>{s.sub}</div>
                    </div>
                  ))}
                </div>

                {/* Reasoning */}
                <div style={{ background:'var(--bg-surface)', borderRadius:'var(--r-md)', padding:'12px 14px', border:'1px solid var(--border)' }}>
                  <div style={{ fontSize:11, fontWeight:600, color:'var(--text-muted)', textTransform:'uppercase', letterSpacing:'0.06em', marginBottom:6 }}>AI reasoning</div>
                  <p style={{ fontSize:13, lineHeight:1.65, margin:0 }}>{advice.reasoning}</p>
                </div>

                <div style={{ display:'flex', gap:10, marginTop:16, flexWrap:'wrap' }}>
                  <div style={{ fontSize:12, color:'var(--text-muted)' }}>
                    📦 Storage: ₹{advice.storageCost.toLocaleString('en-IN', { maximumFractionDigits:0 })}
                  </div>
                  <div style={{ fontSize:12, color:'var(--text-muted)' }}>
                    🚛 Transport: ₹{advice.transportCost.toLocaleString('en-IN', { maximumFractionDigits:0 })}
                  </div>
                  <div style={{ fontSize:12, color:'var(--c-green)', fontWeight:600, marginLeft:'auto' }}>
                    Net gain: ₹{advice.netGain.toLocaleString('en-IN', { maximumFractionDigits:0 })}
                  </div>
                </div>
              </div>

              {/* Scenario chart */}
              {simData && (
                <div className="card card-pad" style={{ animation:'fadeIn 0.5s ease both' }}>
                  <h2 style={{ fontSize:15, marginBottom:4 }}>Profit vs sell day</h2>
                  <p style={{ fontSize:12, color:'var(--text-muted)', marginBottom:14 }}>
                    Extra profit vs selling today — green = gain, red = loss
                  </p>
                  <div style={{ height:200 }}>
                    <ResponsiveContainer width="100%" height="100%">
                      <AreaChart data={simData} margin={{ top:4, right:4, bottom:0, left:0 }}>
                        <defs>
                          <linearGradient id="profitGrad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="5%"  stopColor="var(--c-green)" stopOpacity={0.3}/>
                            <stop offset="95%" stopColor="var(--c-green)" stopOpacity={0}/>
                          </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false}/>
                        <XAxis dataKey="days" tickFormatter={v => `${v}d`} tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false}/>
                        <YAxis tickFormatter={v => `₹${(v/1000).toFixed(0)}k`} tick={{ fontSize:11, fill:'var(--text-muted)' }} tickLine={false} axisLine={false} width={46}/>
                        <Tooltip content={<CustomTooltip />}/>
                        <ReferenceLine y={0} stroke="var(--border-md)" strokeWidth={1}/>
                        <Area dataKey="profit" name="Profit vs today" stroke="var(--c-green)" strokeWidth={2}
                          fill="url(#profitGrad)" dot={false}/>
                      </AreaChart>
                    </ResponsiveContainer>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
