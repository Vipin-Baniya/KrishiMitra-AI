// ════════════════════════════════════════════════════════════
//  hooks/useDevice.ts
// ════════════════════════════════════════════════════════════
import { useState, useEffect } from 'react';

interface DeviceInfo {
  isMobile:  boolean;
  isTablet:  boolean;
  isDesktop: boolean;
  isTouch:   boolean;
  osClass:   string;
  width:     number;
}

export function useDevice(): DeviceInfo {
  const [info, setInfo] = useState<DeviceInfo>(() => detect());

  useEffect(() => {
    const handler = () => setInfo(detect());
    window.addEventListener('resize', handler, { passive: true });
    return () => window.removeEventListener('resize', handler);
  }, []);

  useEffect(() => {
    document.documentElement.className = info.osClass;
  }, [info.osClass]);

  return info;
}

function detect(): DeviceInfo {
  const ua = navigator.userAgent;
  const w  = window.innerWidth;
  let osClass = 'os-linux';
  if (/iPad|iPhone|iPod/.test(ua)) osClass = 'os-ios';
  else if (/Android/.test(ua))     osClass = 'os-android';
  else if (/Macintosh/.test(ua))   osClass = 'os-macos';
  else if (/Windows/.test(ua))     osClass = 'os-windows';
  return {
    isMobile:  w < 640,
    isTablet:  w >= 640 && w < 1024,
    isDesktop: w >= 1024,
    isTouch:   'ontouchstart' in window,
    osClass,
    width: w,
  };
}

// ════════════════════════════════════════════════════════════
//  components/layout/Layout.tsx
// ════════════════════════════════════════════════════════════
import React, { type ReactNode } from 'react';
import { useDevice }   from '../../hooks/useDevice';
import { useUIStore }  from '../../services/api';
import { useAuthStore } from '../../services/api';
import Sidebar     from './Sidebar';
import MobileNav   from './MobileNav';
import TopBar      from './TopBar';

interface Props { children: ReactNode; }

export default function Layout({ children }: Props) {
  const { isMobile } = useDevice();
  const activePage   = useUIStore(s => s.activePage);

  return (
    <div style={{ display: 'flex', minHeight: '100dvh' }}>
      {!isMobile && <Sidebar />}

      <main style={{
        flex: 1, overflow: 'auto', minWidth: 0,
        paddingBottom: isMobile ? '76px' : 0,
      }}>
        {isMobile && <TopBar />}
        <div className="fade-in" key={activePage}>
          {children}
        </div>
      </main>

      {isMobile && <MobileNav />}
    </div>
  );
}

// ════════════════════════════════════════════════════════════
//  components/layout/Sidebar.tsx
// ════════════════════════════════════════════════════════════
import React from 'react';
import { useUIStore, useAuthStore } from '../../services/api';
import ThemeSwitcher from '../ui/ThemeSwitcher';

const ITEMS = [
  { id: 'dashboard',  label: 'Dashboard',      icon: '⊞' },
  { id: 'mandi',      label: 'Mandi Prices',   icon: '📈' },
  { id: 'sell',       label: 'Sell Advisor',   icon: '💰' },
  { id: 'simulator',  label: 'Profit Sim',     icon: '🔢' },
  { id: 'crop',       label: 'Crop Advisor',   icon: '🌱' },
  { id: 'chat',       label: 'AI Assistant',   icon: '🤖' },
  { id: 'alerts',     label: 'Alerts',         icon: '🔔', badge: true },
];

export default function Sidebar() {
  const { activePage, setPage } = useUIStore();
  const farmer = useAuthStore(s => s.farmer);
  const logout = useAuthStore(s => s.logout);

  return (
    <aside style={{
      width: 'var(--sidebar-w)', flexShrink: 0,
      background: 'var(--bg-surface)', borderRight: '1px solid var(--border)',
      display: 'flex', flexDirection: 'column',
      position: 'sticky', top: 0, height: '100dvh', overflow: 'hidden',
    }}>
      {/* Logo */}
      <div style={{ padding: '20px 18px 10px' }}>
        <div className="flex items-center gap-2">
          <div style={{
            width: 34, height: 34, borderRadius: 'var(--r-md)',
            background: 'var(--accent)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', fontSize: 16,
          }}>🌾</div>
          <div>
            <div style={{ fontFamily: 'var(--font-display)', fontWeight: 800, fontSize: 15, letterSpacing: '-0.03em' }}>
              KrishiMitra
            </div>
            <div style={{ fontSize: 9, color: 'var(--text-muted)', fontWeight: 600, letterSpacing: '0.08em', textTransform: 'uppercase' }}>
              AI Platform
            </div>
          </div>
        </div>

        {farmer && (
          <div style={{
            marginTop: 14, padding: '8px 10px',
            background: 'var(--bg-elevated)', borderRadius: 'var(--r-md)',
            border: '1px solid var(--border)',
          }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>Logged in as</div>
            <div style={{ fontWeight: 600, fontSize: 13, color: 'var(--text-primary)' }}>{farmer.name}</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{farmer.district}, {farmer.state}</div>
          </div>
        )}
      </div>

      <div className="divider" style={{ margin: '4px 16px' }} />

      {/* Nav */}
      <nav style={{ flex: 1, padding: '6px 10px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ fontSize: 10, fontWeight: 600, letterSpacing: '0.07em', color: 'var(--text-muted)', padding: '4px 13px 6px', textTransform: 'uppercase' }}>
          Navigation
        </div>
        {ITEMS.map(item => (
          <button
            key={item.id}
            className={`nav-item ${activePage === item.id ? 'active' : ''}`}
            onClick={() => setPage(item.id)}
          >
            <span style={{ fontSize: 15 }}>{item.icon}</span>
            <span>{item.label}</span>
            {item.badge && (farmer?.unreadAlerts ?? 0) > 0 && (
              <span className="nav-badge">{farmer?.unreadAlerts}</span>
            )}
          </button>
        ))}
      </nav>

      {/* Bottom */}
      <div style={{ padding: '10px 12px 16px' }}>
        <div className="divider" />
        <ThemeSwitcher />
        <button
          className="nav-item"
          onClick={logout}
          style={{ marginTop: 4, color: 'var(--danger)' }}
        >
          <span style={{ fontSize: 14 }}>↩</span>
          <span>Logout</span>
        </button>
      </div>
    </aside>
  );
}

// ════════════════════════════════════════════════════════════
//  components/layout/MobileNav.tsx
// ════════════════════════════════════════════════════════════
import React from 'react';
import { useUIStore } from '../../services/api';

const ITEMS = [
  { id: 'dashboard', label: 'Home',    icon: '⊞' },
  { id: 'mandi',     label: 'Prices',  icon: '📈' },
  { id: 'sell',      label: 'Sell',    icon: '💰' },
  { id: 'chat',      label: 'AI',      icon: '🤖' },
  { id: 'alerts',    label: 'Alerts',  icon: '🔔' },
];

export default function MobileNav() {
  const { activePage, setPage } = useUIStore();
  return (
    <nav className="mobile-nav no-print">
      {ITEMS.map(item => (
        <button
          key={item.id}
          className={`mobile-nav-btn ${activePage === item.id ? 'active' : ''}`}
          onClick={() => setPage(item.id)}
        >
          <span style={{ fontSize: 20 }}>{item.icon}</span>
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  );
}

// ════════════════════════════════════════════════════════════
//  components/layout/TopBar.tsx  (mobile only)
// ════════════════════════════════════════════════════════════
import React from 'react';
import { useUIStore } from '../../services/api';
import ThemeSwitcher from '../ui/ThemeSwitcher';

export default function TopBar() {
  return (
    <header style={{
      position: 'sticky', top: 0, zIndex: 50,
      background: 'var(--bg-overlay)', backdropFilter: 'blur(18px) saturate(180%)',
      borderBottom: '1px solid var(--border)',
      padding: '10px 16px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    }}>
      <div className="flex items-center gap-2">
        <span style={{ fontSize: 20 }}>🌾</span>
        <span style={{ fontFamily: 'var(--font-display)', fontWeight: 800, fontSize: 16 }}>
          KrishiMitra
        </span>
      </div>
      <ThemeSwitcher compact />
    </header>
  );
}

// ════════════════════════════════════════════════════════════
//  components/ui/ThemeSwitcher.tsx
// ════════════════════════════════════════════════════════════
import React from 'react';
import { useUIStore } from '../../services/api';

const THEMES = [
  { id: 'light', icon: '☀️', label: 'Light' },
  { id: 'dark',  icon: '🌙', label: 'Dark'  },
  { id: 'earth', icon: '🌿', label: 'Earth' },
  { id: 'ocean', icon: '🌊', label: 'Ocean' },
];

export default function ThemeSwitcher({ compact = false }: { compact?: boolean }) {
  const { theme, setTheme } = useUIStore();

  if (compact) {
    return (
      <button
        onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
        className="btn btn-ghost btn-icon"
        style={{ fontSize: 16 }}
      >
        {theme === 'dark' ? '☀️' : '🌙'}
      </button>
    );
  }

  return (
    <div>
      <div style={{ fontSize: 10, color: 'var(--text-muted)', fontWeight: 600, letterSpacing: '0.07em', textTransform: 'uppercase', marginBottom: 8 }}>
        Theme
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 5 }}>
        {THEMES.map(t => (
          <button
            key={t.id}
            title={t.label}
            onClick={() => setTheme(t.id)}
            style={{
              padding: '6px 4px', fontSize: 14, cursor: 'pointer',
              border: `1.5px solid ${theme === t.id ? 'var(--accent)' : 'var(--border)'}`,
              borderRadius: 'var(--r-sm)',
              background: theme === t.id ? 'var(--accent-dim)' : 'var(--bg-elevated)',
              transition: 'all 0.16s',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
          >
            {t.icon}
          </button>
        ))}
      </div>
    </div>
  );
}
