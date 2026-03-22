import React, { useEffect } from 'react';
import { useAuthStore, useUIStore } from './services/api';
import Layout from './components/layout/LayoutComponents';

// ── Real page imports (not stubs) ────────────────────────────
import LoginPage        from './pages/Login';
import Dashboard        from './pages/Dashboard';
import MandiPrices      from './pages/MandiPrices';
import SellAdvisor      from './pages/SellAdvisor';      // full impl
import ProfitSimulator  from './pages/ProfitSimulator';
import CropAdvisor      from './pages/CropAdvisor';      // full impl
import AiChat           from './pages/AiChat';
import AlertsPage       from './pages/Alerts';

// ── Page registry ─────────────────────────────────────────────
const PAGES: Record<string, React.ReactElement> = {
  dashboard:  <Dashboard />,
  mandi:      <MandiPrices />,
  sell:       <SellAdvisor />,
  simulator:  <ProfitSimulator />,
  crop:       <CropAdvisor />,
  chat:       <AiChat />,
  alerts:     <AlertsPage />,
};

export default function App() {
  const isAuth     = useAuthStore(s => s.isAuth());
  const theme      = useUIStore(s => s.theme);
  const activePage = useUIStore(s => s.activePage);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  if (!isAuth) return <LoginPage />;

  return (
    <Layout>
      {PAGES[activePage] ?? <Dashboard />}
    </Layout>
  );
}
