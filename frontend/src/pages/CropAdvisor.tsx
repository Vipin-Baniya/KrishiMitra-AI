import React, { useState } from 'react';
import { useMutation } from 'react-query';
import toast from 'react-hot-toast';
import { cropApi, useAuthStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import type { CropSuggestion } from '../services/api';

const SOIL_TYPES   = ['Black cotton','Red','Alluvial','Laterite','Sandy loam','Clay loam','Loamy'];
const SEASONS      = ['Kharif (Jun–Oct)','Rabi (Oct–Mar)','Zaid (Mar–Jun)'];
const WATER_AVAIL  = ['Irrigated','Rain-fed only','Partially irrigated'];
const LAND_SIZES   = ['< 1 acre','1–3 acres','3–10 acres','10+ acres'];
const RISK_PREFS   = ['Low – stable income','Medium – balanced','High – max profit'];

const RISK_COLOR: Record<string,string> = {
  low:    'var(--c-green)',
  medium: 'var(--c-amber)',
  high:   'var(--c-red)',
};

const MOCK_RECS: CropSuggestion[] = [
  { crop:'Wheat',   icon:'🌾', matchScore:0.94, profitRange:'₹18,000–₹22,000/acre', riskLevel:'low',    growthDays:120, bestMandi:'Harda',   reason:'Ideal for black cotton soil + winter season. Strong price trend in Madhya Pradesh.' },
  { crop:'Gram',    icon:'🟡', matchScore:0.87, profitRange:'₹14,000–₹18,000/acre', riskLevel:'low',    growthDays:105, bestMandi:'Indore',  reason:'Low water needs suit partial irrigation. MSP support reduces downside risk.' },
  { crop:'Mustard', icon:'💛', matchScore:0.81, profitRange:'₹12,000–₹16,000/acre', riskLevel:'low',    growthDays:115, bestMandi:'Bhopal',  reason:'Excellent return on investment in rabi season with minimal input costs.' },
  { crop:'Onion',   icon:'🧅', matchScore:0.73, profitRange:'₹25,000–₹60,000/acre', riskLevel:'high',   growthDays:130, bestMandi:'Dewas',   reason:'Highest profit potential but price volatile. Requires good irrigation.' },
  { crop:'Soybean', icon:'🫘', matchScore:0.68, profitRange:'₹16,000–₹22,000/acre', riskLevel:'medium', growthDays:100, bestMandi:'Ujjain',  reason:'MP is the largest soybean producer — strong local mandi demand.' },
];

function MatchBar({ score }: { score: number }) {
  const pct = Math.round(score * 100);
  const color = pct >= 85 ? 'var(--c-green)' : pct >= 70 ? 'var(--c-amber)' : 'var(--c-red)';
  return (
    <div style={{ display:'flex', alignItems:'center', gap:8 }}>
      <div style={{ flex:1, height:5, background:'var(--border)', borderRadius:'var(--r-full)', overflow:'hidden' }}>
        <div style={{ height:'100%', width:`${pct}%`, background:color, borderRadius:'var(--r-full)', transition:'width 0.8s cubic-bezier(0.4,0,0.2,1)' }}/>
      </div>
      <span style={{ fontSize:12, fontWeight:700, color, minWidth:32 }}>{pct}%</span>
    </div>
  );
}

export default function CropAdvisorPage() {
  const { isMobile } = useDevice();
  const farmer = useAuthStore(s => s.farmer);

  const [soil,     setSoil]     = useState(SOIL_TYPES[0]);
  const [season,   setSeason]   = useState(SEASONS[0]);
  const [water,    setWater]    = useState(WATER_AVAIL[0]);
  const [land,     setLand]     = useState(LAND_SIZES[1]);
  const [risk,     setRisk]     = useState(RISK_PREFS[0]);
  const [budget,   setBudget]   = useState(15000);
  const [recs,     setRecs]     = useState<CropSuggestion[] | null>(null);

  const recMutation = useMutation(
    () => cropApi.recommend({
      district: farmer?.district ?? 'Indore',
      state:    farmer?.state ?? 'Madhya Pradesh',
      soilType: soil,
      season:   season.split(' ')[0],
      waterAvailability: water,
      landSize: land,
      riskPreference: risk,
      budgetPerAcre: budget,
    }),
    {
      onSuccess: data => { setRecs(data.recommendations); toast.success('Recommendations ready!'); },
      onError:   ()   => { setRecs(MOCK_RECS); toast('Using offline recommendations', { icon:'ℹ️' }); },
    }
  );

  return (
    <div style={{ padding: isMobile ? '14px' : '24px 28px', maxWidth:'var(--content-max)', margin:'0 auto' }}>
      <div style={{ marginBottom:22 }}>
        <h1 style={{ marginBottom:4 }}>Crop Advisor</h1>
        <p style={{ fontSize:14 }}>AI recommendations based on your soil, season, and market demand</p>
      </div>

      <div style={{ display:'grid', gridTemplateColumns: isMobile ? '1fr' : '320px 1fr', gap:20 }}>

        {/* Form */}
        <div className="card card-pad">
          <h2 style={{ fontSize:15, marginBottom:16 }}>Farm details</h2>
          <div style={{ display:'flex', flexDirection:'column', gap:14 }}>

            {[
              { label:'Soil type',          value:soil,   onChange:setSoil,   opts:SOIL_TYPES },
              { label:'Planting season',    value:season, onChange:setSeason, opts:SEASONS },
              { label:'Water availability', value:water,  onChange:setWater,  opts:WATER_AVAIL },
              { label:'Land size',          value:land,   onChange:setLand,   opts:LAND_SIZES },
              { label:'Risk preference',    value:risk,   onChange:setRisk,   opts:RISK_PREFS },
            ].map(field => (
              <div key={field.label}>
                <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:5, textTransform:'uppercase', letterSpacing:'0.06em' }}>
                  {field.label}
                </label>
                <select value={field.value} onChange={e => field.onChange(e.target.value)} className="select">
                  {field.opts.map(o => <option key={o}>{o}</option>)}
                </select>
              </div>
            ))}

            <div>
              <label style={{ fontSize:12, fontWeight:600, color:'var(--text-muted)', display:'block', marginBottom:5, textTransform:'uppercase', letterSpacing:'0.06em' }}>
                Input budget — <span style={{ color:'var(--accent)' }}>₹{budget.toLocaleString('en-IN')}/acre</span>
              </label>
              <input type="range" min={5000} max={80000} step={1000} value={budget}
                onChange={e => setBudget(+e.target.value)}
                style={{ width:'100%', accentColor:'var(--accent)' }}/>
              <div style={{ display:'flex', justifyContent:'space-between', fontSize:11, color:'var(--text-muted)', marginTop:3 }}>
                <span>₹5k</span><span>₹80k</span>
              </div>
            </div>

            <div style={{ padding:'10px 13px', background:'var(--bg-elevated)', borderRadius:'var(--r-md)', border:'1px solid var(--border)' }}>
              <div style={{ fontSize:12, color:'var(--text-muted)', marginBottom:2 }}>Location</div>
              <div style={{ fontSize:13, fontWeight:600 }}>{farmer?.district ?? 'Indore'}, {farmer?.state ?? 'Madhya Pradesh'}</div>
            </div>

            <button
              className="btn btn-primary"
              onClick={() => recMutation.mutate()}
              disabled={recMutation.isLoading}
              style={{ justifyContent:'center', padding:'11px', marginTop:4 }}
            >
              {recMutation.isLoading
                ? <><div style={{ width:14, height:14, border:'2px solid rgba(255,255,255,.4)', borderTopColor:'white', borderRadius:'50%', animation:'spin .7s linear infinite' }}/> Analysing market…</>
                : '🌱 Get crop recommendations'}
            </button>
          </div>
        </div>

        {/* Results */}
        <div>
          {!recs ? (
            <div className="card" style={{ padding:'48px 24px', textAlign:'center' }}>
              <div style={{ fontSize:56, marginBottom:16 }}>🌾</div>
              <h3 style={{ marginBottom:8 }}>Ready to recommend</h3>
              <p style={{ fontSize:14 }}>Fill in your farm details and the AI will suggest the best crops for your soil type, season, and market conditions — with profit estimates and the best mandi to sell at.</p>
            </div>
          ) : (
            <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
              <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom:4 }}>
                <h2 style={{ fontSize:15 }}>Recommended crops</h2>
                <span style={{ fontSize:12, color:'var(--text-muted)' }}>{recs.length} options ranked by match score</span>
              </div>

              {recs.map((rec, i) => (
                <div key={i} className="card card-pad"
                  style={{
                    borderLeft:`3px solid ${RISK_COLOR[rec.riskLevel] ?? 'var(--border)'}`,
                    animation:`fadeIn 0.4s ease both`,
                    animationDelay:`${i * 0.07}s`,
                  }}>
                  <div style={{ display:'flex', alignItems:'flex-start', gap:16 }}>
                    <div style={{ fontSize:40, flexShrink:0 }}>{rec.icon}</div>
                    <div style={{ flex:1, minWidth:0 }}>
                      {/* Header */}
                      <div style={{ display:'flex', alignItems:'center', gap:10, marginBottom:8, flexWrap:'wrap' }}>
                        <span style={{ fontSize:18, fontWeight:700, fontFamily:'var(--font-display)' }}>{rec.crop}</span>
                        <span style={{ fontSize:11, fontWeight:600, textTransform:'uppercase', letterSpacing:'0.05em',
                          color: RISK_COLOR[rec.riskLevel], background:`${RISK_COLOR[rec.riskLevel]}22`,
                          padding:'2px 8px', borderRadius:'var(--r-full)' }}>
                          {rec.riskLevel} risk
                        </span>
                        {i === 0 && (
                          <span style={{ fontSize:11, fontWeight:700, color:'var(--c-green)',
                            background:'rgba(79,180,131,0.13)', padding:'2px 8px',
                            borderRadius:'var(--r-full)', border:'1px solid rgba(79,180,131,0.25)' }}>
                            ★ Best match
                          </span>
                        )}
                      </div>

                      {/* Match score */}
                      <div style={{ marginBottom:12 }}>
                        <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:4 }}>Match score for your farm</div>
                        <MatchBar score={rec.matchScore} />
                      </div>

                      {/* Stats grid */}
                      <div style={{ display:'grid', gridTemplateColumns:'repeat(3,1fr)', gap:10, marginBottom:12 }}>
                        <div>
                          <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:3 }}>Profit range</div>
                          <div style={{ fontSize:13, fontWeight:600, color:'var(--c-green)' }}>{rec.profitRange}</div>
                        </div>
                        <div>
                          <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:3 }}>Growth period</div>
                          <div style={{ fontSize:13, fontWeight:600 }}>{rec.growthDays} days</div>
                        </div>
                        <div>
                          <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:3 }}>Best mandi</div>
                          <div style={{ fontSize:13, fontWeight:600 }}>{rec.bestMandi}</div>
                        </div>
                      </div>

                      {/* AI reason */}
                      <div style={{ background:'var(--bg-elevated)', borderRadius:'var(--r-md)', padding:'9px 12px', border:'1px solid var(--border)' }}>
                        <span style={{ fontSize:11, fontWeight:600, color:'var(--text-muted)', textTransform:'uppercase', letterSpacing:'0.06em' }}>AI reasoning: </span>
                        <span style={{ fontSize:13, color:'var(--text-primary)' }}>{rec.reason}</span>
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
