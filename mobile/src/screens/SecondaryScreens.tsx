// ════════════════════════════════════════════════════════════
//  src/screens/AiChatScreen.tsx
// ════════════════════════════════════════════════════════════
import React, { useState, useRef, useEffect } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, FlatList,
  KeyboardAvoidingView, Platform, ActivityIndicator, ScrollView,
} from 'react-native';
import { useMutation } from '@tanstack/react-query';
import { chatApi, useAuthStore } from '../services/api';
import { useTheme, SPACING, FONT_SIZE, FONT_WEIGHT } from '../services/foundation';
import { ScreenHeader, Button } from '../components/ui';

interface ChatMsg { role:'user'|'assistant'; content:string; id:string; }

const GREETING: ChatMsg = {
  role: 'assistant', id: 'greeting',
  content: 'Namaskar! 🌾 Main KrishiMitra AI hoon.\n\nAapki help kar sakta hoon:\n• Mandi price analysis & predictions\n• Best time to sell your crops\n• Crop planning & storage decisions\n\nKya jaanna chahte hain?',
};

const QUICK = [
  'गेहूं अभी बेचूं या रुकूं?',
  'Wheat price next 15 days?',
  'Best mandi near Indore?',
  'Should I store tomatoes?',
];

const LANGUAGES = [
  { code:'hi', label:'हिंदी' },
  { code:'en', label:'English' },
  { code:'mr', label:'मराठी' },
];

export function AiChatScreen() {
  const { colors } = useTheme();
  const farmer      = useAuthStore(s => s.farmer);
  const [messages, setMessages] = useState<ChatMsg[]>([GREETING]);
  const [input,    setInput]    = useState('');
  const [lang,     setLang]     = useState(farmer?.preferredLang ?? 'hi');
  const [sessId,   setSessId]   = useState<string|null>(null);
  const flatRef = useRef<FlatList>(null);

  const sendMutation = useMutation({
    mutationFn: (text: string) => chatApi.send({
      sessionId: sessId,
      message:   text,
      language:  lang,
      context:   farmer ? {
        location:   `${farmer.district}, ${farmer.state}`,
        crops:      farmer.crops.map(c => c.commodity),
        farmerName: farmer.name,
      } : undefined,
    }),
    onSuccess: resp => {
      setSessId(resp.sessionId);
      setMessages(prev => [...prev, { role:'assistant', content: resp.content, id: Date.now().toString() }]);
    },
    onError: () => {
      setMessages(prev => [...prev, { role:'assistant', content:'⚠️ Connection error. Please try again.', id: Date.now().toString() }]);
    },
  });

  function handleSend(text?: string) {
    const msg = (text ?? input).trim();
    if (!msg || sendMutation.isPending) return;
    setInput('');
    setMessages(prev => [...prev, { role:'user', content: msg, id: Date.now().toString() }]);
    sendMutation.mutate(msg);
  }

  useEffect(() => {
    if (messages.length > 1) {
      setTimeout(() => flatRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [messages]);

  function renderItem({ item }: { item: ChatMsg }) {
    const isUser = item.role === 'user';
    return (
      <View style={{ paddingHorizontal: SPACING.lg, paddingVertical: 4, alignItems: isUser ? 'flex-end' : 'flex-start' }}>
        {!isUser && (
          <View style={{ flexDirection:'row', alignItems:'center', gap: 6, marginBottom: 4 }}>
            <View style={{ width:20, height:20, borderRadius:10, backgroundColor:`${colors.accent}30`, alignItems:'center', justifyContent:'center' }}>
              <Text style={{ fontSize:11 }}>🤖</Text>
            </View>
            <Text style={{ fontSize:11, color:colors.textMuted, fontWeight:FONT_WEIGHT.medium }}>KrishiMitra</Text>
          </View>
        )}
        <View style={{
          maxWidth: '85%',
          backgroundColor: isUser ? colors.accent : colors.elevated,
          borderRadius: 16,
          borderBottomRightRadius: isUser ? 4 : 16,
          borderBottomLeftRadius:  isUser ? 16 : 4,
          padding: SPACING.md,
          borderWidth: isUser ? 0 : 0.5,
          borderColor: colors.border,
        }}>
          <Text style={{ fontSize: FONT_SIZE.sm, color: isUser ? colors.textInverse : colors.textPrimary, lineHeight: 22 }}>
            {item.content}
          </Text>
        </View>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={{ flex:1, backgroundColor: colors.bg }}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
    >
      {/* Header */}
      <View style={{ paddingHorizontal:SPACING.lg, paddingTop:SPACING.lg, paddingBottom:SPACING.sm, borderBottomWidth:0.5, borderBottomColor:colors.border, backgroundColor:colors.surface }}>
        <View style={{ flexDirection:'row', alignItems:'center', justifyContent:'space-between' }}>
          <View style={{ flexDirection:'row', alignItems:'center', gap: SPACING.sm }}>
            <View style={{ width:36, height:36, borderRadius:10, backgroundColor:colors.accent, alignItems:'center', justifyContent:'center' }}>
              <Text style={{ fontSize:18 }}>🤖</Text>
            </View>
            <View>
              <Text style={{ fontSize:FONT_SIZE.md, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary }}>KrishiMitra AI</Text>
              <View style={{ flexDirection:'row', alignItems:'center', gap:5 }}>
                <View style={{ width:6, height:6, borderRadius:3, backgroundColor:colors.accent }} />
                <Text style={{ fontSize:11, color:colors.textMuted }}>Custom LLM → GPT-4o → Claude</Text>
              </View>
            </View>
          </View>
          {/* Language selector */}
          <View style={{ flexDirection:'row', gap:5 }}>
            {LANGUAGES.map(l => (
              <TouchableOpacity key={l.code} onPress={() => setLang(l.code)} style={{
                backgroundColor: lang===l.code ? colors.accent : colors.elevated,
                borderRadius:6, paddingHorizontal:8, paddingVertical:4,
                borderWidth:1, borderColor:lang===l.code?'transparent':colors.border,
              }}>
                <Text style={{ fontSize:11, color: lang===l.code?colors.textInverse:colors.textMuted, fontWeight:FONT_WEIGHT.medium }}>{l.label}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>
      </View>

      {/* Messages */}
      <FlatList
        ref={flatRef}
        data={messages}
        keyExtractor={m => m.id}
        renderItem={renderItem}
        contentContainerStyle={{ paddingVertical: SPACING.md }}
        showsVerticalScrollIndicator={false}
        ListFooterComponent={sendMutation.isPending ? (
          <View style={{ paddingHorizontal:SPACING.lg, paddingVertical:4, alignItems:'flex-start' }}>
            <View style={{ backgroundColor:colors.elevated, borderRadius:16, borderBottomLeftRadius:4, padding:SPACING.md, flexDirection:'row', gap:5 }}>
              {[0,1,2].map(i => (
                <View key={i} style={{ width:7, height:7, borderRadius:4, backgroundColor:colors.textMuted, opacity: 0.6 }} />
              ))}
            </View>
          </View>
        ) : null}
      />

      {/* Quick prompts */}
      {messages.length <= 1 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false}
          style={{ paddingLeft:SPACING.lg, marginBottom:SPACING.sm }}
          contentContainerStyle={{ gap:8 }}>
          {QUICK.map((q,i) => (
            <TouchableOpacity key={i} onPress={() => handleSend(q)} style={{
              backgroundColor:colors.elevated, borderRadius:20,
              paddingHorizontal:12, paddingVertical:7,
              borderWidth:1, borderColor:colors.border,
            }}>
              <Text style={{ fontSize:12, color:colors.textSecondary }}>{q}</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      )}

      {/* Input */}
      <View style={{ paddingHorizontal:SPACING.lg, paddingVertical:SPACING.sm, borderTopWidth:0.5, borderTopColor:colors.border, backgroundColor:colors.surface, flexDirection:'row', gap:SPACING.sm, alignItems:'flex-end' }}>
        <TextInput
          value={input}
          onChangeText={setInput}
          placeholder="Hindi ya English mein poochhen…"
          placeholderTextColor={colors.textMuted}
          multiline
          style={{
            flex:1, backgroundColor:colors.elevated, borderRadius:20,
            paddingHorizontal:SPACING.md, paddingVertical:9,
            fontSize:FONT_SIZE.sm, color:colors.textPrimary,
            maxHeight:100, borderWidth:1, borderColor:colors.border,
          }}
          returnKeyType="send"
          onSubmitEditing={() => handleSend()}
        />
        <TouchableOpacity
          onPress={() => handleSend()}
          disabled={!input.trim() || sendMutation.isPending}
          style={{
            width:40, height:40, borderRadius:20,
            backgroundColor: (!input.trim()||sendMutation.isPending) ? colors.elevated : colors.accent,
            alignItems:'center', justifyContent:'center',
          }}
        >
          {sendMutation.isPending
            ? <ActivityIndicator size="small" color={colors.accent} />
            : <Text style={{ color: !input.trim() ? colors.textMuted : colors.textInverse, fontSize:18, marginTop:-2 }}>↗</Text>
          }
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  );
}

// ════════════════════════════════════════════════════════════
//  src/screens/AlertsScreen.tsx
// ════════════════════════════════════════════════════════════
import { alertApi } from '../services/api';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Badge, Card, SkeletonRows, ScreenHeader, Button } from '../components/ui';

export function AlertsScreen() {
  const { colors } = useTheme();
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey:['alerts'], queryFn:()=>alertApi.list() });
  const markAll = useMutation({ mutationFn: alertApi.markAllRead, onSuccess: ()=>qc.invalidateQueries({queryKey:['alerts']}) });

  const alerts = data?.alerts ?? [];

  return (
    <View style={{ flex:1, backgroundColor:colors.bg }}>
      <ScreenHeader
        title="Alerts"
        subtitle={`${data?.totalUnread ?? 0} unread`}
        right={data?.totalUnread ? (
          <TouchableOpacity onPress={()=>markAll.mutate()} style={{ backgroundColor:colors.elevated, borderRadius:8, paddingHorizontal:12, paddingVertical:6, borderWidth:1, borderColor:colors.border }}>
            <Text style={{ fontSize:12, color:colors.textSecondary, fontWeight:FONT_WEIGHT.medium }}>Mark all read</Text>
          </TouchableOpacity>
        ) : undefined}
      />

      {isLoading
        ? <View style={{ paddingHorizontal:SPACING.lg }}><SkeletonRows n={5} height={80} /></View>
        : (
          <FlatList
            data={alerts}
            keyExtractor={a=>a.id}
            contentContainerStyle={{ paddingHorizontal:SPACING.lg, gap:SPACING.sm, paddingBottom:24 }}
            ListEmptyComponent={
              <View style={{ alignItems:'center', paddingTop:80 }}>
                <Text style={{ fontSize:48, marginBottom:12 }}>✅</Text>
                <Text style={{ fontSize:FONT_SIZE.lg, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary, marginBottom:6 }}>All caught up!</Text>
                <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted }}>No alerts right now</Text>
              </View>
            }
            renderItem={({ item:a }) => (
              <Card accent={a.severity==='URGENT'?colors.danger:a.severity==='WARNING'?colors.warning:colors.info}
                style={{ opacity: a.isRead ? 0.7 : 1 }}>
                <View style={{ flexDirection:'row', gap:SPACING.sm }}>
                  <Text style={{ fontSize:22 }}>{a.severity==='URGENT'?'🔴':a.severity==='WARNING'?'🟡':'🔵'}</Text>
                  <View style={{ flex:1 }}>
                    <View style={{ flexDirection:'row', alignItems:'center', gap:8, marginBottom:4, flexWrap:'wrap' }}>
                      <Text style={{ fontSize:FONT_SIZE.sm, fontWeight:FONT_WEIGHT.bold, color:colors.textPrimary }}>{a.title}</Text>
                      {!a.isRead && <Badge label="NEW" color="green" />}
                      {a.commodity && <Badge label={a.commodity} color="muted" />}
                    </View>
                    <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textSecondary, lineHeight:20 }}>{a.body}</Text>
                    <Text style={{ fontSize:FONT_SIZE.xs, color:colors.textMuted, marginTop:6 }}>
                      {new Date(a.createdAt).toLocaleDateString('en-IN', { day:'numeric', month:'short', hour:'2-digit', minute:'2-digit' })}
                    </Text>
                  </View>
                </View>
              </Card>
            )}
          />
        )
      }
    </View>
  );
}

// ════════════════════════════════════════════════════════════
//  src/screens/LoginScreen.tsx
// ════════════════════════════════════════════════════════════
import { authApi, farmerApi } from '../services/api';
import Toast from 'react-native-toast-message';

export function LoginScreen() {
  const { colors } = useTheme();
  const setAuth = useAuthStore(s => s.setAuth);
  const [mode, setMode]         = useState<'login'|'register'>('login');
  const [phone, setPhone]       = useState('');
  const [password, setPassword] = useState('');
  const [name, setName]         = useState('');
  const [district, setDistrict] = useState('');

  const loginMutation = useMutation({
    mutationFn: () => authApi.login(phone, password),
    onSuccess:  async tokens => {
      const { MMKV } = await import('react-native-mmkv');
      const s = new MMKV({ id:'krishimitra' });
      s.set('km_access', tokens.accessToken);
      s.set('km_refresh', tokens.refreshToken);
      const profile = await farmerApi.getProfile();
      setAuth(tokens.accessToken, tokens.refreshToken, profile);
    },
    onError: () => Toast.show({ type:'error', text1:'Login failed', text2:'Check your credentials' }),
  });

  const registerMutation = useMutation({
    mutationFn: () => authApi.register({ phone, password, name, district, state:'Madhya Pradesh', preferredLang:'hi' }),
    onSuccess:  async tokens => {
      const { MMKV } = await import('react-native-mmkv');
      const s = new MMKV({ id:'krishimitra' });
      s.set('km_access', tokens.accessToken);
      s.set('km_refresh', tokens.refreshToken);
      const profile = await farmerApi.getProfile();
      setAuth(tokens.accessToken, tokens.refreshToken, profile);
    },
    onError: (e:any) => Toast.show({ type:'error', text1:'Registration failed', text2: e.response?.data?.message ?? 'Try again' }),
  });

  const loading = loginMutation.isPending || registerMutation.isPending;

  const inputStyle = {
    backgroundColor:colors.elevated, borderRadius:RADIUS_MD, borderWidth:1, borderColor:colors.border,
    paddingHorizontal:SPACING.md, paddingVertical:11, fontSize:FONT_SIZE.sm, color:colors.textPrimary, marginBottom:SPACING.md,
  };

  return (
    <KeyboardAvoidingView style={{ flex:1, backgroundColor:colors.bg }} behavior={Platform.OS==='ios'?'padding':undefined}>
      <ScrollView contentContainerStyle={{ flexGrow:1, justifyContent:'center', padding:SPACING.xl }}>
        <View style={{ alignItems:'center', marginBottom:SPACING.xxl }}>
          <Text style={{ fontSize:56, marginBottom:SPACING.md }}>🌾</Text>
          <Text style={{ fontSize:FONT_SIZE.display, fontWeight:FONT_WEIGHT.black, color:colors.textPrimary, letterSpacing:-1 }}>KrishiMitra</Text>
          <Text style={{ fontSize:FONT_SIZE.sm, color:colors.textMuted, marginTop:4 }}>AI Platform for Indian Farmers</Text>
        </View>

        <Card>
          {/* Mode toggle */}
          <View style={{ flexDirection:'row', backgroundColor:colors.elevated, borderRadius:RADIUS_MD, padding:4, marginBottom:SPACING.lg }}>
            {(['login','register'] as const).map(m => (
              <TouchableOpacity key={m} onPress={()=>setMode(m)} style={{
                flex:1, paddingVertical:8, borderRadius:RADIUS_MD-2, alignItems:'center',
                backgroundColor: mode===m ? colors.surface : 'transparent',
              }}>
                <Text style={{ fontSize:FONT_SIZE.sm, fontWeight: mode===m?FONT_WEIGHT.bold:FONT_WEIGHT.regular, color: mode===m?colors.textPrimary:colors.textMuted }}>
                  {m==='login'?'Login':'Register'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {mode==='register' && (
            <>
              <TextInput value={name} onChangeText={setName} placeholder="Full name" placeholderTextColor={colors.textMuted} style={inputStyle} />
              <TextInput value={district} onChangeText={setDistrict} placeholder="District (e.g. Indore)" placeholderTextColor={colors.textMuted} style={inputStyle} />
            </>
          )}

          <TextInput value={phone} onChangeText={setPhone} placeholder="Mobile number (10 digits)" placeholderTextColor={colors.textMuted} keyboardType="phone-pad" maxLength={10} style={inputStyle} />
          <TextInput value={password} onChangeText={setPassword} placeholder={mode==='login'?'Password':'Min 8 characters'} placeholderTextColor={colors.textMuted} secureTextEntry style={{ ...inputStyle, marginBottom:SPACING.lg }} />

          <TouchableOpacity
            onPress={() => mode==='login' ? loginMutation.mutate() : registerMutation.mutate()}
            disabled={loading || !phone || !password}
            style={{ backgroundColor: (!phone||!password||loading)?colors.elevated:colors.accent, borderRadius:RADIUS_MD, paddingVertical:13, alignItems:'center' }}
          >
            {loading
              ? <ActivityIndicator color={colors.accent} />
              : <Text style={{ fontSize:FONT_SIZE.md, fontWeight:FONT_WEIGHT.bold, color:(!phone||!password)?colors.textMuted:colors.textInverse }}>{mode==='login'?'Login':'Create Account'}</Text>
            }
          </TouchableOpacity>
        </Card>

        <Text style={{ textAlign:'center', fontSize:FONT_SIZE.xs, color:colors.textMuted, marginTop:SPACING.xl }}>
          KrishiMitra AI · Data from Agmarknet · Prices in ₹/quintal
        </Text>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const RADIUS_MD = 10;

export default AiChatScreen;
