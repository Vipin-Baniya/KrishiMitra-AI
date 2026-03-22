// ════════════════════════════════════════════════════════════
//  api.ts — Axios instance + all API calls
// ════════════════════════════════════════════════════════════
import axios, { AxiosInstance, AxiosResponse } from 'axios';

const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

export const api: AxiosInstance = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  timeout: 12_000,
  headers: { 'Content-Type': 'application/json' },
});

// Request interceptor — attach JWT
api.interceptors.request.use(cfg => {
  const token = localStorage.getItem('km_access');
  if (token) cfg.headers['Authorization'] = `Bearer ${token}`;
  return cfg;
});

// Response interceptor — handle token refresh
api.interceptors.response.use(
  r => r,
  async err => {
    const orig = err.config;
    if (err.response?.status === 401 && !orig._retry) {
      orig._retry = true;
      try {
        const refresh = localStorage.getItem('km_refresh');
        if (!refresh) throw new Error('no refresh token');
        const res = await axios.post(`${BASE_URL}/api/v1/auth/refresh`, { refreshToken: refresh });
        const { accessToken, refreshToken } = res.data.data;
        localStorage.setItem('km_access',  accessToken);
        localStorage.setItem('km_refresh', refreshToken);
        orig.headers['Authorization'] = `Bearer ${accessToken}`;
        return api(orig);
      } catch {
        localStorage.removeItem('km_access');
        localStorage.removeItem('km_refresh');
        window.location.href = '/login';
      }
    }
    return Promise.reject(err);
  }
);

const unwrap = <T>(p: Promise<AxiosResponse<{ data: T }>>) =>
  p.then(r => r.data.data);

// ── AUTH ─────────────────────────────────────────────────────
export interface TokenResponse {
  accessToken: string; refreshToken: string;
  expiresInMs: number; farmerId: string; farmerName: string;
}
export const authApi = {
  register: (body: object)       => unwrap<TokenResponse>(api.post('/auth/register', body)),
  login:    (phone: string, password: string) =>
    unwrap<TokenResponse>(api.post('/auth/login', { phone, password })),
  refresh:  (refreshToken: string) => unwrap<TokenResponse>(api.post('/auth/refresh', { refreshToken })),
  logout:   ()                   => api.post('/auth/logout'),
};

// ── FARMER ───────────────────────────────────────────────────
export interface FarmerCropDto {
  id: string; commodity: string; variety?: string;
  quantityQuintal?: number; expectedHarvest?: string; storageAvailable: boolean;
}
export interface FarmerProfile {
  id: string; name: string; phone: string; email?: string;
  village?: string; district: string; state: string;
  preferredLang: string; crops: FarmerCropDto[]; unreadAlerts: number;
}
export const farmerApi = {
  getProfile:   ()           => unwrap<FarmerProfile>(api.get('/farmer/profile')),
  updateProfile: (body: object) => unwrap<FarmerProfile>(api.patch('/farmer/profile', body)),
  addCrop:      (body: object) => api.post('/farmer/crops', body),
};

// ── PRICES ───────────────────────────────────────────────────
export interface LivePrice {
  commodity: string; mandi: string; district: string; state: string;
  minPrice: number; maxPrice: number; modalPrice: number;
  arrivalsQtl?: number; priceDate: string;
  trendDirection: 'UP'|'DOWN'|'FLAT'; changePct: number;
}
export interface ForecastPoint { days: number; price: number; }
export interface PriceForecast {
  commodity: string; mandi: string; currentPrice: number;
  forecastDate: string; horizons: number[];
  pointForecast: number[]; lower80: number[]; upper80: number[];
  lower95: number[]; upper95: number[];
  sellDecision: string; waitDays: number; peakDay: number;
  peakPrice: number; profitGain: number; confidence: number;
  explanation?: Record<string,unknown>; modelWeights?: Record<string,number>;
  fromCache: boolean; latencyMs: number;
}
export interface MandiRank {
  mandiName: string; state: string; modalPrice: number; netPrice: number;
  distanceKm: number; transportCostEst: number; demandScore: number;
  recommendation: string;
}
export const priceApi = {
  getLive:      (commodity: string, state?: string) =>
    unwrap<LivePrice[]>(api.get('/prices/live', { params: { commodity, state } })),
  getForecast:  (commodity: string, mandi: string) =>
    unwrap<PriceForecast>(api.get('/prices/forecast', { params: { commodity, mandi } })),
  rankMandis:   (commodity: string, lat: number, lng: number, topN = 5) =>
    unwrap<MandiRank[]>(api.get('/prices/mandis/rank', { params: { commodity, lat, lng, topN } })),
};

// ── SELL ADVISOR ─────────────────────────────────────────────
export interface SellAdvice {
  sellDecision: string; waitDays: number; peakDay: number;
  currentPrice: number; peakPrice: number;
  profitGainPerQtl: number; totalProfitGain: number;
  storageCost: number; transportCost: number; netGain: number;
  confidence: number; reasoning: string;
  explanation?: Record<string,unknown>;
}
export interface ScenarioPoint { days: number; profit: number; }
export interface ProfitSim {
  commodity: string; mandi: string; quantityQuintal: number; waitDays: number;
  currentPrice: number; predictedPrice: number;
  storageCost: number; transportCost: number;
  grossRevenue: number; netRevenue: number; profitVsNow: number;
  scenarioChart: ScenarioPoint[];
}
export const sellApi = {
  getAdvice: (body: object) => unwrap<SellAdvice>(api.post('/sell/advice', body)),
  simulate:  (body: object) => unwrap<ProfitSim>(api.post('/sell/simulate', body)),
};

// ── ALERTS ───────────────────────────────────────────────────
export interface Alert {
  id: string; type: string; severity: string;
  commodity?: string; mandiName?: string;
  title: string; body: string; isRead: boolean;
  expiresAt?: string; createdAt: string;
}
export interface AlertPage {
  alerts: Alert[]; totalUnread: number; page: number; totalPages: number;
}
export const alertApi = {
  list:       (page = 0, size = 20) =>
    unwrap<AlertPage>(api.get('/alerts', { params: { page, size } })),
  markAllRead:() => api.put('/alerts/read-all'),
  markRead:   (id: string) => api.put(`/alerts/${id}/read`),
};

// ── AI CHAT ──────────────────────────────────────────────────
export interface ChatMessage { role: 'user'|'assistant'; content: string; }
export interface ChatSession { id: string; lastMessage: string; createdAt: string; }
export interface ChatResp {
  sessionId: string; content: string; modelUsed: string;
  confidence?: number; latencyMs: number; createdAt: string;
}
export const chatApi = {
  send:       (body: object) => unwrap<ChatResp>(api.post('/ai/chat', body)),
  getSessions:()             => unwrap<ChatSession[]>(api.get('/ai/sessions')),
};

// ── CROPS ────────────────────────────────────────────────────
export interface CropSuggestion {
  crop: string; icon: string; matchScore: number;
  profitRange: string; riskLevel: string; growthDays: number;
  bestMandi: string; reason: string;
}
export interface CropRec { location: string; season: string; recommendations: CropSuggestion[]; }
export const cropApi = {
  recommend: (body: object) => unwrap<CropRec>(api.post('/crops/recommend', body)),
};

// ════════════════════════════════════════════════════════════
//  store.ts — Zustand global state
// ════════════════════════════════════════════════════════════
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { FarmerProfile, ChatMessage } from './api';

interface AuthState {
  accessToken:  string | null;
  refreshToken: string | null;
  farmer:       FarmerProfile | null;
  setAuth:      (access: string, refresh: string, farmer: FarmerProfile) => void;
  setFarmer:    (f: FarmerProfile) => void;
  logout:       () => void;
  isAuth:       () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken:  null,
      refreshToken: null,
      farmer:       null,
      setAuth: (access, refresh, farmer) => {
        localStorage.setItem('km_access',  access);
        localStorage.setItem('km_refresh', refresh);
        set({ accessToken: access, refreshToken: refresh, farmer });
      },
      setFarmer: farmer => set({ farmer }),
      logout: () => {
        localStorage.removeItem('km_access');
        localStorage.removeItem('km_refresh');
        set({ accessToken: null, refreshToken: null, farmer: null });
      },
      isAuth: () => !!get().accessToken,
    }),
    { name: 'km-auth', partialize: s => ({ accessToken: s.accessToken, refreshToken: s.refreshToken, farmer: s.farmer }) }
  )
);

// UI state
interface UIState {
  theme:      string;
  sidebarOpen: boolean;
  activePage:  string;
  setTheme:    (t: string) => void;
  setPage:     (p: string) => void;
  toggleSidebar:() => void;
}
export const useUIStore = create<UIState>()(
  persist(
    set => ({
      theme:       'light',
      sidebarOpen: true,
      activePage:  'dashboard',
      setTheme:    theme => {
        document.documentElement.setAttribute('data-theme', theme);
        set({ theme });
      },
      setPage:      activePage => set({ activePage }),
      toggleSidebar:() => set(s => ({ sidebarOpen: !s.sidebarOpen })),
    }),
    { name: 'km-ui', partialize: s => ({ theme: s.theme }) }
  )
);

// Chat state
interface ChatState {
  sessions:   Record<string, ChatMessage[]>;
  activeId:   string | null;
  setActive:  (id: string) => void;
  addMessage: (sessionId: string, msg: ChatMessage) => void;
  newSession: () => void;
}
export const useChatStore = create<ChatState>()(set => ({
  sessions:  {},
  activeId:  null,
  setActive: id => set({ activeId: id }),
  addMessage:(sessionId, msg) => set(s => ({
    sessions: {
      ...s.sessions,
      [sessionId]: [...(s.sessions[sessionId] ?? []), msg],
    }
  })),
  newSession:() => set({ activeId: null }),
}));
