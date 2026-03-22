// ════════════════════════════════════════════════════════════
//  src/services/theme.ts — Design tokens, OS-aware
// ════════════════════════════════════════════════════════════
import { Platform, useColorScheme } from 'react-native';

export const SPACING  = { xs:4, sm:8, md:12, lg:16, xl:24, xxl:32 };
export const RADIUS   = { sm:6, md:10, lg:16, xl:24, full:999 };
export const FONT_SIZE= { xs:10, sm:12, md:14, lg:16, xl:20, xxl:26, display:32 };
export const FONT_WEIGHT: Record<string, '400'|'500'|'600'|'700'|'800'> = {
  regular: '400', medium: '500', semibold: '600', bold: '700', black: '800',
};

// Platform-specific font families
const FONTS = Platform.select({
  ios: {
    display: 'SF Pro Display',
    body:    'SF Pro Text',
    mono:    'SF Mono',
  },
  android: {
    display: 'Roboto',
    body:    'Roboto',
    mono:    'monospace',
  },
  default: {
    display: 'System',
    body:    'System',
    mono:    'monospace',
  },
}) as { display: string; body: string; mono: string };

export const FONT_FAMILY = FONTS;

function makeTheme(dark: boolean) {
  return {
    dark,
    colors: {
      bg:        dark ? '#0E1210' : '#F6F4EE',
      surface:   dark ? '#161B18' : '#FFFFFF',
      elevated:  dark ? '#1E2520' : '#FAF9F6',
      card:      dark ? '#1C2420' : '#FFFFFF',
      border:    dark ? 'rgba(232,237,233,0.08)' : 'rgba(24,24,15,0.08)',
      borderMd:  dark ? 'rgba(232,237,233,0.13)' : 'rgba(24,24,15,0.13)',

      textPrimary:   dark ? '#E4EBE6' : '#18180F',
      textSecondary: dark ? '#A3B0A8' : '#524F3E',
      textMuted:     dark ? '#586260' : '#9A9784',
      textInverse:   dark ? '#0E1210' : '#FFFFFF',

      accent:       dark ? '#4FB483' : '#2B6A4E',
      accentLight:  dark ? '#72C99B' : '#4FB483',
      accentDim:    dark ? 'rgba(79,180,131,0.13)' : 'rgba(43,106,78,0.10)',
      danger:       dark ? '#F4736B' : '#C9342A',
      dangerDim:    dark ? 'rgba(244,115,107,0.14)' : 'rgba(201,52,42,0.10)',
      warning:      dark ? '#F5B73A' : '#C47F12',
      warningDim:   dark ? 'rgba(245,183,58,0.14)' : 'rgba(196,127,18,0.10)',
      info:         dark ? '#60A5FA' : '#2563EB',

      chartGreen:  '#4FB483',
      chartAmber:  '#F0953A',
      chartRed:    '#E04444',
      chartBlue:   '#4370F5',
    },
    shadow: Platform.select({
      ios: {
        sm: { shadowColor:'#000', shadowOffset:{width:0,height:1}, shadowOpacity:dark?.30:.08, shadowRadius:3 },
        md: { shadowColor:'#000', shadowOffset:{width:0,height:4}, shadowOpacity:dark?.45:.11, shadowRadius:10 },
        lg: { shadowColor:'#000', shadowOffset:{width:0,height:8}, shadowOpacity:dark?.55:.14, shadowRadius:20 },
      },
      android: { sm:{elevation:2}, md:{elevation:6}, lg:{elevation:12} },
      default:  { sm:{}, md:{}, lg:{} },
    }),
  };
}

export type AppTheme = ReturnType<typeof makeTheme>;

export function useTheme(): AppTheme {
  const scheme = useColorScheme();
  return makeTheme(scheme === 'dark');
}

// ════════════════════════════════════════════════════════════
//  src/services/api.ts — Full API client, identical contracts
//  to web frontend but using Axios + React Native storage
// ════════════════════════════════════════════════════════════
import axios, { AxiosInstance } from 'axios';
import { MMKV } from 'react-native-mmkv';

export const storage = new MMKV({ id: 'krishimitra' });

const BASE_URL = __DEV__
  ? 'http://10.0.2.2:8080'    // Android emulator → localhost
  : 'https://krishimitra.in';

export const api: AxiosInstance = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use(cfg => {
  const token = storage.getString('km_access');
  if (token) cfg.headers['Authorization'] = `Bearer ${token}`;
  return cfg;
});

api.interceptors.response.use(r => r, async err => {
  const orig = err.config;
  if (err.response?.status === 401 && !orig._retry) {
    orig._retry = true;
    try {
      const refresh = storage.getString('km_refresh');
      if (!refresh) throw new Error('no refresh token');
      const res = await axios.post(`${BASE_URL}/api/v1/auth/refresh`, { refreshToken: refresh });
      const { accessToken, refreshToken } = res.data.data;
      storage.set('km_access',  accessToken);
      storage.set('km_refresh', refreshToken);
      orig.headers['Authorization'] = `Bearer ${accessToken}`;
      return api(orig);
    } catch {
      storage.delete('km_access');
      storage.delete('km_refresh');
      // Navigation handled by auth store listener
    }
  }
  return Promise.reject(err);
});

const unwrap = <T>(p: Promise<any>) => p.then((r: any) => r.data.data as T);

export const authApi = {
  register: (body: object)                  => unwrap<TokenResponse>(api.post('/auth/register', body)),
  login:    (phone: string, pw: string)     => unwrap<TokenResponse>(api.post('/auth/login', { phone, password: pw })),
  refresh:  (refreshToken: string)          => unwrap<TokenResponse>(api.post('/auth/refresh', { refreshToken })),
  logout:   ()                              => api.post('/auth/logout'),
};

export const priceApi = {
  getLive:    (commodity: string, state?: string) => unwrap<LivePrice[]>(api.get('/prices/live', { params: { commodity, state } })),
  getForecast:(commodity: string, mandi: string)  => unwrap<PriceForecast>(api.get('/prices/forecast', { params: { commodity, mandi } })),
  rankMandis: (commodity: string, lat: number, lng: number) => unwrap<MandiRank[]>(api.get('/prices/mandis/rank', { params: { commodity, lat, lng, topN: 5 } })),
};

export const sellApi = {
  getAdvice: (body: object) => unwrap<SellAdvice>(api.post('/sell/advice', body)),
  simulate:  (body: object) => unwrap<ProfitSim>(api.post('/sell/simulate', body)),
};

export const farmerApi = {
  getProfile:   ()           => unwrap<FarmerProfile>(api.get('/farmer/profile')),
  updateProfile:(body:object)=> unwrap<FarmerProfile>(api.patch('/farmer/profile', body)),
  addCrop:      (body:object)=> api.post('/farmer/crops', body),
};

export const alertApi = {
  list:       (page=0) => unwrap<AlertPage>(api.get('/alerts', { params: { page, size:20 } })),
  markAllRead:()       => api.put('/alerts/read-all'),
  markRead:   (id:string) => api.put(`/alerts/${id}/read`),
};

export const chatApi = {
  send: (body: object) => unwrap<ChatResp>(api.post('/ai/chat', body)),
};

// ── Types (mirrored from web) ─────────────────────────────────
export interface TokenResponse  { accessToken:string; refreshToken:string; expiresInMs:number; farmerId:string; farmerName:string; }
export interface FarmerCropDto  { id:string; commodity:string; variety?:string; quantityQuintal?:number; expectedHarvest?:string; storageAvailable:boolean; }
export interface FarmerProfile  { id:string; name:string; phone:string; email?:string; village?:string; district:string; state:string; preferredLang:string; crops:FarmerCropDto[]; unreadAlerts:number; }
export interface LivePrice      { commodity:string; mandi:string; district:string; state:string; minPrice:number; maxPrice:number; modalPrice:number; priceDate:string; trendDirection:'UP'|'DOWN'|'FLAT'; changePct:number; }
export interface PriceForecast  { commodity:string; mandi:string; currentPrice:number; horizons:number[]; pointForecast:number[]; lower80:number[]; upper80:number[]; sellDecision:string; waitDays:number; peakDay:number; peakPrice:number; profitGain:number; confidence:number; explanation?:Record<string,unknown>; fromCache:boolean; }
export interface MandiRank      { mandiName:string; state:string; modalPrice:number; netPrice:number; distanceKm:number; recommendation:string; }
export interface SellAdvice     { sellDecision:string; waitDays:number; currentPrice:number; peakPrice:number; profitGainPerQtl:number; totalProfitGain:number; storageCost:number; netGain:number; confidence:number; reasoning:string; }
export interface ProfitSim      { commodity:string; mandi:string; quantityQuintal:number; waitDays:number; currentPrice:number; predictedPrice:number; storageCost:number; netRevenue:number; profitVsNow:number; scenarioChart:{days:number;profit:number}[]; }
export interface Alert          { id:string; type:string; severity:string; commodity?:string; mandiName?:string; title:string; body:string; isRead:boolean; createdAt:string; }
export interface AlertPage      { alerts:Alert[]; totalUnread:number; page:number; totalPages:number; }
export interface ChatResp       { sessionId:string; content:string; modelUsed:string; confidence?:number; latencyMs:number; createdAt:string; }

// ════════════════════════════════════════════════════════════
//  src/store/authStore.ts — Zustand with MMKV persistence
// ════════════════════════════════════════════════════════════
import { create } from 'zustand';
import { storage, type FarmerProfile } from '../services/api';

interface AuthState {
  farmer:   FarmerProfile | null;
  isAuth:   boolean;
  setAuth:  (access: string, refresh: string, farmer: FarmerProfile) => void;
  setFarmer:(f: FarmerProfile) => void;
  logout:   () => void;
  hydrate:  () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  farmer:  null,
  isAuth:  false,

  hydrate: () => {
    const token = storage.getString('km_access');
    const raw   = storage.getString('km_farmer');
    if (token && raw) {
      try { set({ isAuth: true, farmer: JSON.parse(raw) }); } catch { /* ignore */ }
    }
  },

  setAuth: (access, refresh, farmer) => {
    storage.set('km_access',  access);
    storage.set('km_refresh', refresh);
    storage.set('km_farmer',  JSON.stringify(farmer));
    set({ isAuth: true, farmer });
  },

  setFarmer: farmer => {
    storage.set('km_farmer', JSON.stringify(farmer));
    set({ farmer });
  },

  logout: () => {
    storage.delete('km_access');
    storage.delete('km_refresh');
    storage.delete('km_farmer');
    set({ isAuth: false, farmer: null });
  },
}));
