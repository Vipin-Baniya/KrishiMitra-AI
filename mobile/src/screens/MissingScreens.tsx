// ════════════════════════════════════════════════════════════
//  src/screens/SellAdvisorScreen.tsx
// ════════════════════════════════════════════════════════════
import React, { useState } from 'react';
import {
  ScrollView, View, Text, TouchableOpacity,
  Switch, ActivityIndicator, Platform,
} from 'react-native';
import { useMutation } from '@tanstack/react-query';
import { sellApi, useAuthStore } from '../services/api';
import { useTheme, SPACING, FONT_SIZE, FONT_WEIGHT } from '../services/foundation';
import {
  Card, Badge, PriceTag, ScreenHeader, Button,
  ProgressBar, Divider, FadeIn, SkeletonRows,
} from '../components/ui';
import type { SellAdvice } from '../services/api';

const COMMODITIES = ['Wheat','Soybean','Onion','Tomato','Potato','Cotton','Maize','Gram'];
const MANDIS = ['Indore','Ujjain','Dewas','Harda','Bhopal','Vidisha'];
const STORAGE_COSTS: Record<string,number> = { Wheat:2.5,Soybean:4,Onion:6,Tomato:8,Potato:5,Cotton:3,Maize:1.5,Gram:2 };
const DECISION_META: Record<string,{icon:string;color:string;label:string}> = {
  SELL_NOW:   { icon:'💰', color:'#4FB483', label:'Sell Now' },
  WAIT_N_DAYS:{ icon:'⏳', color:'#F0953A', label:'Wait' },
  HOLD:       { icon:'🔒', color:'#4370F5', label:'Hold' },
};

export function SellAdvisorScreen() {
  const { colors } = useTheme();
  const farmer = useAuthStore(s => s.farmer);
  const [commodity, setCommodity] = useState(farmer?.crops?.[0]?.commodity ?? 'Wheat');
  const [mandi,     setMandi]     = useState('Indore');
  const [quantity,  setQuantity]  = useState(farmer?.crops?.[0]?.quantityQuintal ?? 8);
  const [storage,   setStorage]   = useState(true);
  const [advice,    setAdvice]    = useState<SellAdvice | null>(null);

  const mutation = useMutation({
    mutationFn: () => sellApi.getAdvice({ commodity, mandi, quantityQuintal: quantity, storageAvailable: storage }),
    onSuccess: setAdvice,
  });

  const meta = advice ? (DECISION_META[advice.sellDecision] ?? DECISION_META.HOLD) : null;

  return (
    <ScrollView style={{ flex:1, backgroundColor:colors.bg }} showsVerticalScrollIndicator={false}
      contentContainerStyle={{ paddingBottom:32 }}>
      <ScreenHeader title="Sell Advisor" subtitle="AI-powered SELL / WAIT / HOLD decision" />

      {/* Commodity chips */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false}
        style={{ paddingLeft:SPACING.lg, marginBottom:SPACING.md }}
        contentContainerStyle={{ gap:8 }}>
        {COMMODITIES.map(c => (
          <TouchableOpacity key={c} onPress={() => setCommodity(c)} style={{
            backgroundColor: commodity===c ? colors.accent : colors.elevated,
            borderRadius:20, paddingHorizontal:14, paddingVertical:7,
            borderWidth:1, borderColor:commodity===c?'transparent':colors.border,
          }}>
            <Text style={{ fontSize:13, fontWeight:FONT_WEIGHT.semibold, color:commodity===c?colors.textInverse:colors.textSecondary }}>
              {c}
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {/* Inputs */}
      <View style={{ paddingHorizontal:SPACING.lg, gap:SPACING.md }}>
        <Card>
          <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.6, marginBottom:SPACING.sm }}>Target mandi</Text>
          <View style={{ flexDirection:'row', flexWrap:'wrap', gap:8 }}>
            {MANDIS.map(m => (
              <TouchableOpacity key={m} onPress={() => setMandi(m)} style={{
                backgroundColor: mandi===m?colors.accent:colors.elevated,
                borderRadius:8, paddingHorizontal:12, paddingVertical:6,
                borderWidth:1, borderColor:mandi===m?'transparent':colors.border,
              }}>
                <Text style={{ fontSize:13, color:mandi===m?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>
                  {m}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          <Divider />

          <View style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between' }}>
            <View>
              <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.semibold, color:colors.textPrimary }}>Storage available</Text>
              <Text style={{ fontSize:FONT_SIZE.xs, color:colors.textMuted }}>₹{STORAGE_COSTS[commodity] ?? 3}/qtl/day cost</Text>
            </View>
            <Switch value={storage} onValueChange={setStorage} trackColor={{ true:colors.accent }} thumbColor="#fff" />
          </View>

          <Divider />

          <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.6, marginBottom:SPACING.sm }}>
            Quantity: {quantity} qtl
          </Text>
          <View style={{ flexDirection:'row', gap:8, flexWrap:'wrap' }}>
            {[2,5,8,10,20,50].map(q => (
              <TouchableOpacity key={q} onPress={() => setQuantity(q)} style={{
                backgroundColor: quantity===q?colors.accent:colors.elevated,
                borderRadius:8, paddingHorizontal:14, paddingVertical:6,
                borderWidth:1, borderColor:quantity===q?'transparent':colors.border,
              }}>
                <Text style={{ fontSize:13, color:quantity===q?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>
                  {q} qtl
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </Card>

        <Button label={mutation.isPending ? 'Analysing…' : 'Get AI advice'} onPress={() => mutation.mutate()}
          loading={mutation.isPending} fullWidth size="lg" />

        {/* Result */}
        {advice && meta && (
          <FadeIn>
            <Card>
              <View style={{ flexDirection:'row', alignItems:'center', gap:SPACING.md, marginBottom:SPACING.lg }}>
                <Text style={{ fontSize:40 }}>{meta.icon}</Text>
                <View style={{ flex:1 }}>
                  <Text style={{ fontSize:11, color:colors.textMuted, fontWeight:FONT_WEIGHT.bold, textTransform:'uppercase', letterSpacing:0.6, marginBottom:4 }}>AI Decision</Text>
                  <Text style={{ fontSize:24, fontWeight:FONT_WEIGHT.black, color:meta.color, letterSpacing:-0.5 }}>
                    {meta.label}{advice.sellDecision==='WAIT_N_DAYS' ? ` ${advice.waitDays} days` : ''}
                  </Text>
                </View>
                <PriceTag price={advice.peakPrice} size="md" />
              </View>

              <View style={{ flexDirection:'row', gap:SPACING.sm, marginBottom:SPACING.md }}>
                {[
                  { label:'Current',   val:`₹${advice.currentPrice.toLocaleString('en-IN')}` },
                  { label:'Gain/qtl',  val:`+₹${advice.profitGainPerQtl.toLocaleString('en-IN')}`, color:colors.chartGreen },
                  { label:'Total',     val:`₹${advice.totalProfitGain.toLocaleString('en-IN')}`,   color:colors.chartGreen },
                ].map((s,i) => (
                  <View key={i} style={{ flex:1, backgroundColor:colors.elevated, borderRadius:10, padding:10, borderWidth:1, borderColor:colors.border }}>
                    <Text style={{ fontSize:10, color:colors.textMuted, marginBottom:4 }}>{s.label}</Text>
                    <Text style={{ fontSize:13, fontWeight:FONT_WEIGHT.bold, color:s.color ?? colors.textPrimary }}>{s.val}</Text>
                  </View>
                ))}
              </View>

              <View style={{ backgroundColor:colors.elevated, borderRadius:10, padding:SPACING.md, borderWidth:1, borderColor:colors.border, marginBottom:SPACING.md }}>
                <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:5 }}>Reasoning</Text>
                <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textPrimary, lineHeight:21 }}>{advice.reasoning}</Text>
              </View>

              <View style={{ marginBottom:SPACING.md }}>
                <View style={{ flexDirection:'row', justifyContent:'space-between', marginBottom:6 }}>
                  <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5 }}>Confidence</Text>
                  <Text style={{ fontSize:13, fontWeight:FONT_WEIGHT.bold, color:advice.confidence>0.75?colors.accent:colors.warning }}>
                    {Math.round(advice.confidence*100)}%
                  </Text>
                </View>
                <ProgressBar pct={advice.confidence * 100} color={advice.confidence>0.75?colors.accent:colors.warning}/>
              </View>

              <View style={{ flexDirection:'row', gap:SPACING.lg }}>
                <Text style={{ fontSize:12, color:colors.textMuted }}>📦 Storage: ₹{advice.storageCost.toLocaleString('en-IN')}</Text>
                <Text style={{ fontSize:12, color:colors.textMuted }}>🚛 Transport: ₹{advice.transportCost.toLocaleString('en-IN')}</Text>
              </View>
            </Card>
          </FadeIn>
        )}
      </View>
    </ScrollView>
  );
}

// ════════════════════════════════════════════════════════════
//  src/screens/ProfitSimScreen.tsx
// ════════════════════════════════════════════════════════════
import { BarChart, BarData } from 'react-native-gifted-charts';

export function ProfitSimScreen() {
  const { colors } = useTheme();
  const { params } = useRoute<any>();
  const [waitDays, setWaitDays] = useState(7);
  const [quantity, setQuantity] = useState(8);
  const commodity = params?.commodity ?? 'Wheat';

  const STORAGE_RATE: Record<string,number> = { Wheat:2.5,Soybean:4,Onion:6,Tomato:8,Potato:5,Cotton:3,Maize:1.5,Gram:2 };
  const TREND: Record<string,number> = { Wheat:15,Soybean:5,Tomato:-35,Onion:-15,Cotton:20,Maize:8,Gram:7,Potato:-5 };
  const BASE_PRICES: Record<string,number> = { Wheat:2180,Soybean:4620,Onion:1200,Tomato:840,Potato:960,Cotton:6500,Maize:1850,Gram:5400 };

  function calcProfit(days: number) {
    const cp = BASE_PRICES[commodity] ?? 2000;
    const predicted = Math.max(cp + (TREND[commodity] ?? 0) * days / 7, 0);
    const storage = (STORAGE_RATE[commodity] ?? 3) * days;
    const transport = 800;
    const sellNow = cp * quantity - transport;
    const net = predicted * quantity - storage - transport;
    return { profit: Math.round(net - sellNow), predicted: Math.round(predicted) };
  }

  const scenarios = [0,1,3,5,7,10,14,21,30].map(d => ({ days:d, ...calcProfit(d) }));
  const current   = calcProfit(waitDays);
  const best      = scenarios.reduce((b,s) => s.profit > b.profit ? s : b, scenarios[0]);

  const barData: BarData[] = scenarios.map(s => ({
    value:     Math.abs(s.profit),
    label:     `${s.days}d`,
    frontColor: s.days===waitDays ? colors.accent : s.profit>=0 ? colors.chartGreen : colors.chartRed,
    opacity:   s.days===waitDays ? 1 : 0.55,
  }));

  return (
    <ScrollView style={{ flex:1, backgroundColor:colors.bg }} contentContainerStyle={{ padding:SPACING.lg, paddingBottom:32 }}>
      {/* Sliders */}
      <Card style={{ marginBottom:SPACING.md }}>
        <Text style={{ fontSize:FONT_SIZE.md, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary, marginBottom:SPACING.md }}>{commodity} Profit Simulator</Text>

        <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:SPACING.sm }}>
          Wait days: {waitDays===0?'Sell today':`${waitDays} days`}
        </Text>
        <View style={{ flexDirection:'row', flexWrap:'wrap', gap:8, marginBottom:SPACING.md }}>
          {[0,3,7,10,15,21,30].map(d => (
            <TouchableOpacity key={d} onPress={() => setWaitDays(d)} style={{
              backgroundColor: waitDays===d?colors.accent:colors.elevated,
              borderRadius:8, paddingHorizontal:12, paddingVertical:6,
              borderWidth:1, borderColor:waitDays===d?'transparent':colors.border,
            }}>
              <Text style={{ fontSize:13, color:waitDays===d?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>
                {d===0?'Now':`${d}d`}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:SPACING.sm }}>
          Quantity: {quantity} qtl
        </Text>
        <View style={{ flexDirection:'row', flexWrap:'wrap', gap:8 }}>
          {[2,5,8,10,20,50].map(q => (
            <TouchableOpacity key={q} onPress={() => setQuantity(q)} style={{
              backgroundColor: quantity===q?colors.accent:colors.elevated,
              borderRadius:8, paddingHorizontal:12, paddingVertical:6,
              borderWidth:1, borderColor:quantity===q?'transparent':colors.border,
            }}>
              <Text style={{ fontSize:13, color:quantity===q?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>{q} qtl</Text>
            </TouchableOpacity>
          ))}
        </View>
      </Card>

      {/* Result cards */}
      <View style={{ flexDirection:'row', gap:SPACING.sm, marginBottom:SPACING.md }}>
        {[
          { label:'Predicted price', val:`₹${current.predicted.toLocaleString('en-IN')}`, sub:'per quintal', color:colors.accent },
          { label:'Extra profit', val:`${current.profit>=0?'+':''}₹${current.profit.toLocaleString('en-IN')}`, sub:'vs selling today', color:current.profit>=0?colors.chartGreen:colors.chartRed },
        ].map((s,i) => (
          <Card key={i} style={{ flex:1 }}>
            <Text style={{ fontSize:10, color:colors.textMuted, marginBottom:6 }}>{s.label}</Text>
            <Text style={{ fontSize:18, fontWeight:FONT_WEIGHT.bold, color:s.color }}>{s.val}</Text>
            <Text style={{ fontSize:10, color:colors.textMuted, marginTop:3 }}>{s.sub}</Text>
          </Card>
        ))}
      </View>

      {/* Best scenario */}
      <Card style={{ marginBottom:SPACING.md, backgroundColor:best.profit>0?`${colors.accent}18`:colors.elevated }}>
        <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.accent, textTransform:'uppercase', letterSpacing:0.5, marginBottom:6 }}>🎯 Optimal</Text>
        <Text style={{ fontSize:20, fontWeight:FONT_WEIGHT.black, color:colors.textPrimary }}>{best.days===0?'Sell today':`Wait ${best.days} days`}</Text>
        <Text style={{ fontSize:13, color:best.profit>=0?colors.chartGreen:colors.chartRed, marginTop:4, fontWeight:FONT_WEIGHT.bold }}>
          {best.profit>=0?'+':''}₹{best.profit.toLocaleString('en-IN')} extra profit
        </Text>
      </Card>

      {/* Chart */}
      <Card>
        <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary, marginBottom:SPACING.md }}>Profit by wait day</Text>
        <BarChart
          data={barData}
          barWidth={24}
          spacing={8}
          hideRules
          xAxisThickness={0}
          yAxisThickness={0}
          yAxisTextStyle={{ color:colors.textMuted, fontSize:10 }}
          xAxisLabelTextStyle={{ color:colors.textMuted, fontSize:10 }}
          noOfSections={4}
          height={160}
          formatYLabel={v => `₹${(Math.abs(+v)/1000).toFixed(0)}k`}
        />
      </Card>
    </ScrollView>
  );
}

import { useRoute } from '@react-navigation/native';

// ════════════════════════════════════════════════════════════
//  src/screens/CropAdvisorScreen.tsx
// ════════════════════════════════════════════════════════════
export function CropAdvisorScreen() {
  const { colors } = useTheme();
  const farmer = useAuthStore(s => s.farmer);
  const [soil, setSoil]     = useState('Black cotton');
  const [season, setSeason] = useState('Rabi');
  const [recs, setRecs]     = useState<any[] | null>(null);

  const MOCK = [
    { crop:'Wheat',   icon:'🌾', score:0.94, profit:'₹18k–22k/acre', risk:'low',    days:120, mandi:'Harda',  reason:'Ideal for black cotton soil in rabi season.' },
    { crop:'Gram',    icon:'🟡', score:0.87, profit:'₹14k–18k/acre', risk:'low',    days:105, mandi:'Indore', reason:'Low water needs, MSP support reduces risk.' },
    { crop:'Mustard', icon:'💛', score:0.81, profit:'₹12k–16k/acre', risk:'low',    days:115, mandi:'Bhopal', reason:'Excellent ROI, minimal input costs.' },
    { crop:'Onion',   icon:'🧅', score:0.73, profit:'₹25k–60k/acre', risk:'high',   days:130, mandi:'Dewas',  reason:'Highest profit but price volatile.' },
  ];
  const RISK_CLR: Record<string,string> = { low:colors.chartGreen, medium:colors.warning, high:colors.chartRed };

  return (
    <ScrollView style={{ flex:1, backgroundColor:colors.bg }} contentContainerStyle={{ paddingBottom:32 }}>
      <ScreenHeader title="Crop Advisor" subtitle={`${farmer?.district}, ${farmer?.state}`} />

      <View style={{ paddingHorizontal:SPACING.lg, gap:SPACING.md }}>
        <Card>
          <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:SPACING.sm }}>Soil type</Text>
          <View style={{ flexDirection:'row', flexWrap:'wrap', gap:8, marginBottom:SPACING.md }}>
            {['Black cotton','Red','Alluvial','Sandy loam'].map(s => (
              <TouchableOpacity key={s} onPress={() => setSoil(s)} style={{
                backgroundColor: soil===s?colors.accent:colors.elevated,
                borderRadius:8, paddingHorizontal:12, paddingVertical:6,
                borderWidth:1, borderColor:soil===s?'transparent':colors.border,
              }}>
                <Text style={{ fontSize:12, color:soil===s?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>{s}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <Text style={{ fontSize:11, fontWeight:FONT_WEIGHT.bold, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:SPACING.sm }}>Season</Text>
          <View style={{ flexDirection:'row', gap:8 }}>
            {['Kharif','Rabi','Zaid'].map(s => (
              <TouchableOpacity key={s} onPress={() => setSeason(s)} style={{
                flex:1, backgroundColor: season===s?colors.accent:colors.elevated, borderRadius:8,
                paddingVertical:8, alignItems:'center',
                borderWidth:1, borderColor:season===s?'transparent':colors.border,
              }}>
                <Text style={{ fontSize:13, color:season===s?colors.textInverse:colors.textSecondary, fontWeight:FONT_WEIGHT.semibold }}>{s}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </Card>

        <Button label="Get recommendations" onPress={() => setRecs(MOCK)} fullWidth size="lg" />

        {recs && recs.map((r,i) => (
          <FadeIn key={i} delay={i*80}>
            <Card accent={RISK_CLR[r.risk]}>
              <View style={{ flexDirection:'row', gap:SPACING.md }}>
                <Text style={{ fontSize:34 }}>{r.icon}</Text>
                <View style={{ flex:1 }}>
                  <View style={{ flexDirection:'row', alignItems:'center', gap:8, marginBottom:6, flexWrap:'wrap' }}>
                    <Text style={{ fontSize:FONT_SIZE.lg, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary }}>{r.crop}</Text>
                    <View style={{ backgroundColor:`${RISK_CLR[r.risk]}22`, paddingHorizontal:8, paddingVertical:2, borderRadius:RADIUS_FULL }}>
                      <Text style={{ fontSize:10, fontWeight:FONT_WEIGHT.bold, color:RISK_CLR[r.risk] }}>{r.risk.toUpperCase()} RISK</Text>
                    </View>
                    {i===0 && <View style={{ backgroundColor:'rgba(79,180,131,0.15)', paddingHorizontal:8, paddingVertical:2, borderRadius:RADIUS_FULL }}>
                      <Text style={{ fontSize:10, fontWeight:FONT_WEIGHT.bold, color:colors.accent }}>BEST MATCH</Text>
                    </View>}
                  </View>
                  <View style={{ flexDirection:'row', gap:SPACING.sm, marginBottom:SPACING.sm }}>
                    <View style={{ flex:1, height:5, backgroundColor:colors.elevated, borderRadius:99, overflow:'hidden' }}>
                      <View style={{ height:'100%', width:`${Math.round(r.score*100)}%`, backgroundColor:colors.accent, borderRadius:99 }}/>
                    </View>
                    <Text style={{ fontSize:12, fontWeight:FONT_WEIGHT.bold, color:colors.accent }}>{Math.round(r.score*100)}%</Text>
                  </View>
                  <View style={{ flexDirection:'row', gap:SPACING.lg, marginBottom:SPACING.sm }}>
                    <Text style={{ fontSize:12, color:colors.chartGreen, fontWeight:FONT_WEIGHT.semibold }}>{r.profit}</Text>
                    <Text style={{ fontSize:12, color:colors.textMuted }}>{r.days}d · {r.mandi}</Text>
                  </View>
                  <Text style={{ fontSize:FONT_SIZE.xs, color:colors.textSecondary, lineHeight:18 }}>{r.reason}</Text>
                </View>
              </View>
            </Card>
          </FadeIn>
        ))}
      </View>
    </ScrollView>
  );
}

const RADIUS_FULL = 999;

// ════════════════════════════════════════════════════════════
//  src/screens/ProfileScreen.tsx
// ════════════════════════════════════════════════════════════
import { useQuery } from '@tanstack/react-query';
import { farmerApi } from '../services/api';

export function ProfileScreen() {
  const { colors } = useTheme();
  const { farmer, logout, setFarmer } = useAuthStore();
  const { data: profile } = useQuery({ queryKey:['profile'], queryFn: farmerApi.getProfile });

  const INFO = [
    { label:'Name',      value: profile?.name ?? farmer?.name },
    { label:'Mobile',    value: profile?.phone ?? farmer?.phone },
    { label:'District',  value: profile?.district ?? farmer?.district },
    { label:'State',     value: profile?.state ?? farmer?.state },
    { label:'Language',  value: profile?.preferredLang?.toUpperCase() ?? 'HI' },
  ];

  return (
    <ScrollView style={{ flex:1, backgroundColor:colors.bg }} contentContainerStyle={{ padding:SPACING.lg, paddingBottom:40 }}>
      {/* Avatar */}
      <View style={{ alignItems:'center', paddingVertical:SPACING.xl }}>
        <View style={{ width:72, height:72, borderRadius:36, backgroundColor:colors.accentDim, alignItems:'center', justifyContent:'center', marginBottom:SPACING.md, borderWidth:2, borderColor:`${colors.accent}44` }}>
          <Text style={{ fontSize:32 }}>👨‍🌾</Text>
        </View>
        <Text style={{ fontSize:FONT_SIZE.xl, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary }}>{profile?.name ?? farmer?.name}</Text>
        <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted, marginTop:4 }}>{profile?.district}, {profile?.state}</Text>
      </View>

      {/* Info card */}
      <Card style={{ marginBottom:SPACING.md }}>
        {INFO.map((item,i) => (
          <View key={i} style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between', paddingVertical:12, borderBottomWidth:i<INFO.length-1?0.5:0, borderBottomColor:colors.border }}>
            <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted }}>{item.label}</Text>
            <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.semibold, color:colors.textPrimary }}>{item.value ?? '—'}</Text>
          </View>
        ))}
      </Card>

      {/* Crops */}
      {(profile?.crops ?? farmer?.crops ?? []).length > 0 && (
        <Card style={{ marginBottom:SPACING.md }}>
          <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary, marginBottom:SPACING.md }}>My crops</Text>
          {(profile?.crops ?? farmer?.crops ?? []).map((c,i) => (
            <View key={i} style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between', paddingVertical:8, borderBottomWidth:0.5, borderBottomColor:colors.border }}>
              <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.medium, color:colors.textPrimary }}>{c.commodity}</Text>
              <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted }}>{c.quantityQuintal ?? '—'} qtl</Text>
            </View>
          ))}
        </Card>
      )}

      {/* Logout */}
      <TouchableOpacity onPress={logout} style={{
        backgroundColor:colors.dangerDim, borderRadius:12, paddingVertical:14,
        alignItems:'center', borderWidth:1, borderColor:colors.danger,
      }}>
        <Text style={{ fontSize:FONT_SIZE.md, fontWeight:FONT_WEIGHT.bold, color:colors.danger }}>Logout</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

// ════════════════════════════════════════════════════════════
//  src/screens/ForecastDetailScreen.tsx
// ════════════════════════════════════════════════════════════
import { LineChart } from 'react-native-gifted-charts';

export function ForecastDetailScreen() {
  const { colors } = useTheme();
  const route = useRoute<any>();
  const { commodity, mandi } = route.params ?? { commodity:'Wheat', mandi:'Indore' };

  const { data, isLoading } = useQuery({
    queryKey: ['forecast', commodity, mandi],
    queryFn:  () => priceApi.getForecast(commodity, mandi),
  });

  const chartData = data ? data.horizons.map((d,i) => ({
    value:      data.pointForecast[i],
    label:      `${d}d`,
    dataPointText: i===data.horizons.length-1 ? `₹${data.pointForecast[i].toFixed(0)}` : undefined,
  })) : [];

  return (
    <ScrollView style={{ flex:1, backgroundColor:colors.bg }} contentContainerStyle={{ padding:SPACING.lg, paddingBottom:32 }}>
      {isLoading ? (
        <SkeletonRows n={4} height={80} />
      ) : data ? (
        <>
          {/* Header */}
          <View style={{ marginBottom:SPACING.lg }}>
            <Text style={{ fontSize:FONT_SIZE.xxl, fontWeight:FONT_WEIGHT.black, color:colors.textPrimary, letterSpacing:-0.5 }}>{commodity}</Text>
            <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted }}>{mandi} mandi · 30-day forecast</Text>
          </View>

          {/* Decision banner */}
          <View style={{
            backgroundColor: data.sellDecision==='SELL_NOW' ? `${colors.chartGreen}18` : `${colors.warning}18`,
            borderRadius:12, padding:SPACING.lg, marginBottom:SPACING.md,
            borderWidth:1.5, borderColor: data.sellDecision==='SELL_NOW' ? `${colors.chartGreen}44` : `${colors.warning}44`,
          }}>
            <Text style={{ fontSize:11, color:colors.textMuted, textTransform:'uppercase', letterSpacing:0.5, marginBottom:6 }}>AI Decision</Text>
            <Text style={{ fontSize:22, fontWeight:FONT_WEIGHT.black, color: data.sellDecision==='SELL_NOW'?colors.chartGreen:colors.warning }}>
              {data.sellDecision==='SELL_NOW' ? '💰 Sell Now' : `⏳ Wait ${data.waitDays} days`}
            </Text>
            <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textSecondary, marginTop:6 }}>
              Peak ₹{data.peakPrice.toLocaleString('en-IN')} on day {data.peakDay} · Confidence {Math.round(data.confidence*100)}%
            </Text>
          </View>

          {/* Chart */}
          <Card style={{ marginBottom:SPACING.md }}>
            <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary, marginBottom:SPACING.md }}>Price forecast</Text>
            <LineChart
              data={chartData}
              color={colors.accent}
              thickness={2.5}
              areaChart
              startFillColor={`${colors.accent}40`}
              endFillColor={`${colors.accent}00`}
              curved
              height={180}
              yAxisTextStyle={{ color:colors.textMuted, fontSize:10 }}
              xAxisLabelTextStyle={{ color:colors.textMuted, fontSize:10 }}
              yAxisColor="transparent"
              xAxisColor={colors.border}
              rulesColor={colors.border}
              rulesType="dashed"
              noOfSections={4}
              formatYLabel={v => `₹${(+v/1000).toFixed(1)}k`}
            />
          </Card>

          {/* Stats */}
          <View style={{ flexDirection:'row', gap:SPACING.sm }}>
            {[
              { label:'Current',  val:`₹${data.currentPrice.toLocaleString('en-IN')}` },
              { label:'Peak',     val:`₹${data.peakPrice.toLocaleString('en-IN')}`,   color:colors.chartGreen },
              { label:'Profit',   val:`+₹${data.profitGain.toLocaleString('en-IN')}`, color:colors.chartGreen },
            ].map((s,i) => (
              <Card key={i} style={{ flex:1 }}>
                <Text style={{ fontSize:10, color:colors.textMuted, marginBottom:5 }}>{s.label}</Text>
                <Text style={{ fontSize:15, fontWeight:FONT_WEIGHT.bold, color:s.color ?? colors.textPrimary }}>{s.val}</Text>
              </Card>
            ))}
          </View>
        </>
      ) : (
        <View style={{ alignItems:'center', padding:40 }}>
          <Text style={{ color:colors.textMuted }}>No forecast available</Text>
        </View>
      )}
    </ScrollView>
  );
}

import { priceApi } from '../services/api';
import { SkeletonRows } from '../components/ui';

export default SellAdvisorScreen;
