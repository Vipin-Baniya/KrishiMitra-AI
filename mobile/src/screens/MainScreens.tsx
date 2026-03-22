// ════════════════════════════════════════════════════════════
//  src/screens/DashboardScreen.tsx
// ════════════════════════════════════════════════════════════
import React, { useState } from 'react';
import {
  ScrollView, View, Text, RefreshControl, Pressable, TouchableOpacity,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { LineChart } from 'react-native-gifted-charts';
import { useAuthStore } from '../store/authStore';
import { priceApi } from '../services/api';
import { useTheme, SPACING, FONT_SIZE, FONT_WEIGHT } from '../services/foundation';
import {
  Card, StatCard, Badge, PriceTag, SectionHeader,
  TrendIndicator, SkeletonRows, Divider, FadeIn, ScreenHeader, Button, ProgressBar,
} from '../components/ui';

const CROPS = [
  { name:'Wheat',   icon:'🌾', qty:'8 qtl', decision:'WAIT',     dColor:'amber',  priceNow:2180, target:2480, days:15, pct:45 },
  { name:'Soybean', icon:'🫘', qty:'5 qtl', decision:'SELL NOW', dColor:'green',  priceNow:4620, target:4620, days:0,  pct:100 },
  { name:'Tomato',  icon:'🍅', qty:'2 qtl', decision:'URGENT',   dColor:'red',    priceNow:840,  target:700,  days:-3, pct:0 },
];

const CHART_DATA = [2050,2080,2120,2150,2180,2240,2350,2440,2480,2390].map((v,i) => ({ value:v, label: i===0?'Mar 1':i===4?'Mar 21':i===8?'Apr 10':'' }));

export function DashboardScreen() {
  const { colors } = useTheme();
  const navigation  = useNavigation<any>();
  const farmer      = useAuthStore(s => s.farmer);
  const [refreshing, setRefreshing] = useState(false);
  const [cropTab, setCropTab] = useState<'wheat'|'soybean'|'tomato'>('wheat');

  const { data: prices, isLoading, refetch } = useQuery({
    queryKey: ['live-prices', 'Wheat'],
    queryFn:  () => priceApi.getLive('Wheat'),
    staleTime: 5 * 60 * 1000,
  });

  async function onRefresh() {
    setRefreshing(true);
    await refetch();
    setRefreshing(false);
  }

  const greetName = farmer?.name.split(' ')[0] ?? 'Kisan';

  return (
    <ScrollView
      style={{ flex:1, backgroundColor: colors.bg }}
      contentContainerStyle={{ paddingBottom: 24 }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={colors.accent} />}
      showsVerticalScrollIndicator={false}
    >
      <ScreenHeader
        title={`नमस्ते, ${greetName} 👋`}
        subtitle="Wheat prices up 3.2% this week"
        right={
          <TouchableOpacity
            onPress={() => navigation.navigate('Chat')}
            style={{ backgroundColor: colors.accent, borderRadius: 20, paddingHorizontal: 14, paddingVertical: 7 }}
          >
            <Text style={{ color: colors.textInverse, fontSize: 13, fontWeight: FONT_WEIGHT.semibold }}>Ask AI</Text>
          </TouchableOpacity>
        }
      />

      {/* Stats grid */}
      <View style={{ paddingHorizontal: SPACING.lg, gap: SPACING.sm }}>
        <View style={{ flexDirection:'row', gap: SPACING.sm }}>
          <View style={{ flex:1 }}>
            <StatCard label="Wheat Price" value="₹2,180" unit="/qtl" change="+3.2%" changeUp sub="Indore mandi" />
          </View>
          <View style={{ flex:1 }}>
            <StatCard label="Expected Gain" value="₹340" unit="/qtl" change="in 15 days" changeUp sub="If you wait" color={colors.chartGreen} />
          </View>
        </View>
        <View style={{ flexDirection:'row', gap: SPACING.sm }}>
          <View style={{ flex:1 }}>
            <StatCard label="Soybean" value="₹4,620" unit="/qtl" change="+1.8%" changeUp sub="Ujjain mandi" />
          </View>
          <View style={{ flex:1 }}>
            <StatCard label="Active Alerts" value={farmer?.unreadAlerts ?? 3} change="2 urgent" sub="View all" color={colors.danger} />
          </View>
        </View>
      </View>

      {/* Price chart card */}
      <View style={{ paddingHorizontal: SPACING.lg, marginTop: SPACING.lg }}>
        <Card>
          <SectionHeader title="Price Forecast (30 days)" />

          {/* Crop tabs */}
          <View style={{ flexDirection:'row', gap: 6, marginBottom: SPACING.md }}>
            {(['wheat','soybean','tomato'] as const).map(c => (
              <Pressable key={c} onPress={() => setCropTab(c)} style={{
                paddingHorizontal: 12, paddingVertical: 5, borderRadius: RADIUS_MD,
                backgroundColor: cropTab===c ? colors.accent : colors.elevated,
              }}>
                <Text style={{ fontSize:12, fontWeight: FONT_WEIGHT.semibold, color: cropTab===c ? colors.textInverse : colors.textMuted, textTransform:'capitalize' }}>
                  {c}
                </Text>
              </Pressable>
            ))}
          </View>

          <LineChart
            data={CHART_DATA}
            width={260}
            height={160}
            color={colors.accent}
            thickness={2.5}
            dataPointsColor={colors.accent}
            dataPointsRadius={3}
            startFillColor={`${colors.accent}40`}
            endFillColor={`${colors.accent}00`}
            areaChart
            curved
            hideYAxisText={false}
            yAxisTextStyle={{ color: colors.textMuted, fontSize: 10 }}
            xAxisLabelTextStyle={{ color: colors.textMuted, fontSize: 10 }}
            yAxisColor="transparent"
            xAxisColor={colors.border}
            rulesColor={colors.border}
            rulesType="dashed"
            noOfSections={4}
            yAxisTextNumberOfLines={1}
            formatYLabel={v => `₹${(+v/1000).toFixed(1)}k`}
          />

          {/* Recommendation strip */}
          <View style={{ marginTop: SPACING.md, backgroundColor: `${colors.accent}15`, borderRadius: 10, padding: SPACING.md, flexDirection:'row', alignItems:'center', gap: SPACING.sm }}>
            <View style={{ width:7, height:7, borderRadius:4, backgroundColor: colors.accent }} />
            <Text style={{ flex:1, fontSize:13, color: colors.textPrimary }}>
              <Text style={{ fontWeight: FONT_WEIGHT.semibold }}>AI Rec: </Text>
              Wait 15 days → sell at Harda mandi (₹2,480 expected)
            </Text>
            <PriceTag price={2480} size="sm" />
          </View>
        </Card>
      </View>

      {/* My Crops */}
      <View style={{ paddingHorizontal: SPACING.lg, marginTop: SPACING.lg }}>
        <SectionHeader title="My Crops" actionLabel="+ Add" />
        <View style={{ gap: SPACING.sm }}>
          {CROPS.map((crop, i) => (
            <FadeIn key={i} delay={i * 80}>
              <Card accent={crop.dColor==='amber'?colors.warning:crop.dColor==='green'?colors.accent:colors.danger}>
                <View style={{ flexDirection:'row', alignItems:'center', gap: SPACING.md }}>
                  <Text style={{ fontSize: 32 }}>{crop.icon}</Text>
                  <View style={{ flex:1 }}>
                    <View style={{ flexDirection:'row', alignItems:'center', gap: SPACING.sm, marginBottom: 4 }}>
                      <Text style={{ fontSize: FONT_SIZE.md, fontWeight: FONT_WEIGHT.bold, color: colors.textPrimary }}>{crop.name}</Text>
                      <Badge label={crop.decision} color={crop.dColor as any} />
                    </View>
                    <Text style={{ fontSize: FONT_SIZE.sm, color: colors.textMuted }}>{crop.qty}</Text>
                    <View style={{ flexDirection:'row', gap: SPACING.lg, marginTop: 6 }}>
                      <Text style={{ fontSize: 12, color: colors.textPrimary }}>Now: <Text style={{ fontWeight: FONT_WEIGHT.bold }}>₹{crop.priceNow.toLocaleString('en-IN')}</Text></Text>
                      {crop.days > 0 && <Text style={{ fontSize:12, color:colors.chartGreen }}>Target: ₹{crop.target} (+{crop.days}d)</Text>}
                      {crop.days < 0 && <Text style={{ fontSize:12, color:colors.chartRed }}>Declining ↓</Text>}
                    </View>
                    {crop.days > 0 && (
                      <View style={{ marginTop: 8, gap: 4 }}>
                        <ProgressBar pct={crop.pct} />
                        <Text style={{ fontSize:10, color:colors.textMuted }}>{crop.days} days until optimal window</Text>
                      </View>
                    )}
                  </View>
                  <Pressable onPress={() => navigation.navigate('ProfitSim', { commodity: crop.name, mandi:'Indore' })}
                    style={{ backgroundColor:colors.elevated, borderRadius:8, paddingHorizontal:10, paddingVertical:6, borderWidth:1, borderColor:colors.border }}>
                    <Text style={{ fontSize:12, color:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>Sim →</Text>
                  </Pressable>
                </View>
              </Card>
            </FadeIn>
          ))}
        </View>
      </View>
    </ScrollView>
  );
}

const RADIUS_MD = 8;

// ════════════════════════════════════════════════════════════
//  src/screens/MandiPricesScreen.tsx
// ════════════════════════════════════════════════════════════
import React, { useState } from 'react';
import { ScrollView, View, Text, TextInput, FlatList, TouchableOpacity, RefreshControl } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { priceApi } from '../services/api';
import { useTheme, SPACING, FONT_SIZE, FONT_WEIGHT } from '../services/foundation';
import { Card, Badge, PriceTag, TrendIndicator, SkeletonRows, ScreenHeader } from '../components/ui';

const COMMODITIES = ['Wheat','Soybean','Onion','Tomato','Cotton','Maize','Gram'];

export function MandiPricesScreen() {
  const { colors } = useTheme();
  const [commodity, setCommodity] = useState('Wheat');
  const [search, setSearch] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['live', commodity],
    queryFn:  () => priceApi.getLive(commodity),
    staleTime: 5 * 60_000,
  });

  const filtered = (data ?? []).filter(p =>
    !search || p.mandi.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <View style={{ flex:1, backgroundColor: colors.bg }}>
      <ScreenHeader title="Mandi Prices" subtitle="Live from Agmarknet · 30 min refresh" />

      {/* Commodity chips */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false}
        style={{ paddingLeft: SPACING.lg, marginBottom: SPACING.sm }}
        contentContainerStyle={{ gap: 8 }}>
        {COMMODITIES.map(c => (
          <TouchableOpacity key={c} onPress={() => setCommodity(c)} style={{
            backgroundColor: commodity===c ? colors.accent : colors.elevated,
            borderRadius: RADIUS_MD, paddingHorizontal: 14, paddingVertical: 7,
            borderWidth: commodity===c ? 0 : 1, borderColor: colors.border,
          }}>
            <Text style={{ fontSize:13, fontWeight:FONT_WEIGHT.semibold, color: commodity===c ? colors.textInverse : colors.textSecondary }}>
              {c}
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {/* Search */}
      <View style={{ paddingHorizontal: SPACING.lg, marginBottom: SPACING.md }}>
        <TextInput
          value={search} onChangeText={setSearch}
          placeholder="Search mandi..."
          placeholderTextColor={colors.textMuted}
          style={{
            backgroundColor: colors.elevated, borderRadius: RADIUS_MD,
            borderWidth:1, borderColor: colors.border,
            paddingHorizontal: SPACING.md, paddingVertical: 9,
            fontSize: FONT_SIZE.sm, color: colors.textPrimary,
          }}
        />
      </View>

      {/* List */}
      {isLoading
        ? <View style={{ paddingHorizontal: SPACING.lg }}><SkeletonRows n={6} /></View>
        : (
          <FlatList
            data={filtered}
            keyExtractor={(_, i) => String(i)}
            contentContainerStyle={{ paddingHorizontal: SPACING.lg, gap: SPACING.sm, paddingBottom: 24 }}
            refreshControl={<RefreshControl refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await refetch(); setRefreshing(false); }} tintColor={colors.accent} />}
            ListEmptyComponent={
              <View style={{ padding: 40, alignItems:'center' }}>
                <Text style={{ color: colors.textMuted, fontSize: FONT_SIZE.sm }}>No prices found</Text>
              </View>
            }
            renderItem={({ item: p }) => (
              <Card>
                <View style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between' }}>
                  <View style={{ flex:1 }}>
                    <Text style={{ fontSize: FONT_SIZE.md, fontWeight: FONT_WEIGHT.bold, color: colors.textPrimary }}>{p.mandi}</Text>
                    <Text style={{ fontSize: FONT_SIZE.xs, color: colors.textMuted, marginTop:2 }}>{p.district}, {p.state}</Text>
                  </View>
                  <View style={{ alignItems:'flex-end', gap: 5 }}>
                    <PriceTag price={p.modalPrice} />
                    <TrendIndicator direction={p.trendDirection} pct={p.changePct} />
                  </View>
                </View>
                <View style={{ flexDirection:'row', gap: SPACING.lg, marginTop: SPACING.sm }}>
                  <Text style={{ fontSize:12, color:colors.textMuted }}>Min ₹{p.minPrice.toLocaleString('en-IN')}</Text>
                  <Text style={{ fontSize:12, color:colors.textMuted }}>Max ₹{p.maxPrice.toLocaleString('en-IN')}</Text>
                  <Text style={{ fontSize:12, color:colors.textMuted }}>{p.priceDate}</Text>
                </View>
              </Card>
            )}
          />
        )
      }
    </View>
  );
}

export default DashboardScreen;
