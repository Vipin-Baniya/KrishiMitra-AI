import React, { useState } from 'react';
import { useMutation } from 'react-query';
import toast from 'react-hot-toast';
import { authApi, farmerApi, useAuthStore, useUIStore } from '../services/api';

const STATES = [
  'Madhya Pradesh','Maharashtra','Rajasthan','Punjab','Haryana',
  'Uttar Pradesh','Gujarat','Karnataka','Telangana','Bihar',
  'West Bengal','Odisha','Tamil Nadu','Chhattisgarh','Assam',
];

const LANGUAGES = [
  { code:'hi', label:'हिंदी' },
  { code:'en', label:'English' },
  { code:'mr', label:'मराठी' },
  { code:'gu', label:'ગુજરાતી' },
  { code:'pa', label:'ਪੰਜਾਬੀ' },
];

export default function LoginPage() {
  const [mode,     setMode]     = useState<'login'|'register'>('login');
  const [phone,    setPhone]    = useState('');
  const [password, setPassword] = useState('');
  const [name,     setName]     = useState('');
  const [district, setDistrict] = useState('');
  const [state,    setState]    = useState('Madhya Pradesh');
  const [lang,     setLang]     = useState('hi');
  const [showPass, setShowPass] = useState(false);

  const setAuth  = useAuthStore(s => s.setAuth);
  const theme    = useUIStore(s => s.theme);
  const setTheme = useUIStore(s => s.setTheme);

  const loginMutation = useMutation(
    () => authApi.login(phone, password),
    {
      onSuccess: async tokens => {
        localStorage.setItem('km_access',  tokens.accessToken);
        localStorage.setItem('km_refresh', tokens.refreshToken);
        const profile = await farmerApi.getProfile();
        setAuth(tokens.accessToken, tokens.refreshToken, profile);
        toast.success(`Welcome back, ${profile.name.split(' ')[0]}! 👋`);
      },
      onError: (e: any) => toast.error(e.response?.data?.message ?? 'Login failed. Check your credentials.'),
    }
  );

  const registerMutation = useMutation(
    () => authApi.register({ phone, password, name, district, state, preferredLang: lang }),
    {
      onSuccess: async tokens => {
        localStorage.setItem('km_access',  tokens.accessToken);
        localStorage.setItem('km_refresh', tokens.refreshToken);
        const profile = await farmerApi.getProfile();
        setAuth(tokens.accessToken, tokens.refreshToken, profile);
        toast.success(`Account created! Welcome to KrishiMitra, ${tokens.farmerName}! 🌾`);
      },
      onError: (e: any) => toast.error(e.response?.data?.message ?? 'Registration failed. Try again.'),
    }
  );

  const loading   = loginMutation.isLoading || registerMutation.isLoading;
  const canSubmit = phone.length === 10 && password.length >= 8
    && (mode === 'login' || (name.trim().length >= 2 && district.trim().length >= 2));

  function handleSubmit() {
    if (!canSubmit || loading) return;
    mode === 'login' ? loginMutation.mutate() : registerMutation.mutate();
  }

  return (
    <div style={{
      minHeight: '100dvh', display: 'flex', alignItems: 'center',
      justifyContent: 'center', background: 'var(--bg-base)', padding: 16,
    }}>
      {/* Theme toggle */}
      <button
        onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
        className="btn btn-ghost btn-icon"
        style={{ position: 'fixed', top: 16, right: 16, fontSize: 18 }}
      >{theme === 'dark' ? '☀️' : '🌙'}</button>

      <div style={{ width: '100%', maxWidth: 420 }}>
        {/* Logo */}
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <div style={{
            width: 72, height: 72, borderRadius: 18, background: 'var(--accent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 36, margin: '0 auto 14px',
          }}>🌾</div>
          <h1 style={{ fontSize: 30, marginBottom: 6 }}>KrishiMitra AI</h1>
          <p style={{ fontSize: 14 }}>Smart decisions for every Indian farmer</p>
        </div>

        <div className="card card-pad">
          {/* Mode toggle */}
          <div style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4, marginBottom: 24,
            background: 'var(--bg-elevated)', borderRadius: 'var(--r-md)', padding: 4,
          }}>
            {(['login', 'register'] as const).map(m => (
              <button key={m} onClick={() => setMode(m)} style={{
                borderRadius: 'calc(var(--r-md) - 4px)', padding: '8px 12px',
                border: 'none', cursor: 'pointer',
                background: mode === m ? 'var(--bg-surface)' : 'transparent',
                color:      mode === m ? 'var(--text-primary)' : 'var(--text-muted)',
                fontWeight: mode === m ? 600 : 400, fontSize: 14,
                boxShadow:  mode === m ? 'var(--shadow-xs)' : 'none',
                transition: 'all 0.16s',
              }}>
                {m === 'login' ? 'Login' : 'Register'}
              </button>
            ))}
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {mode === 'register' && (
              <>
                <div>
                  <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Full name</label>
                  <input value={name} onChange={e => setName(e.target.value)}
                    className="input" placeholder="Ramesh Kumar" />
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <div>
                    <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>District</label>
                    <input value={district} onChange={e => setDistrict(e.target.value)}
                      className="input" placeholder="Indore" />
                  </div>
                  <div>
                    <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>State</label>
                    <select value={state} onChange={e => setState(e.target.value)} className="select">
                      {STATES.map(s => <option key={s}>{s}</option>)}
                    </select>
                  </div>
                </div>
                <div>
                  <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Preferred language</label>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {LANGUAGES.map(l => (
                      <button key={l.code} onClick={() => setLang(l.code)} style={{
                        padding: '5px 12px', borderRadius: 'var(--r-full)', border: 'none',
                        cursor: 'pointer', fontSize: 13,
                        background: lang === l.code ? 'var(--accent)' : 'var(--bg-elevated)',
                        color:      lang === l.code ? 'white' : 'var(--text-secondary)',
                        fontWeight: lang === l.code ? 600 : 400,
                        transition: 'all 0.15s',
                      }}>{l.label}</button>
                    ))}
                  </div>
                </div>
              </>
            )}

            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Mobile number</label>
              <input
                value={phone} onChange={e => setPhone(e.target.value.replace(/\D/g, '').slice(0, 10))}
                className="input" placeholder="9876543210" type="tel" inputMode="numeric"
                onKeyDown={e => e.key === 'Enter' && handleSubmit()}
              />
            </div>

            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', display: 'block', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Password</label>
              <div style={{ position: 'relative' }}>
                <input
                  value={password} onChange={e => setPassword(e.target.value)}
                  className="input" type={showPass ? 'text' : 'password'}
                  placeholder={mode === 'login' ? '••••••••' : 'Min 8 characters'}
                  style={{ paddingRight: 44 }}
                  onKeyDown={e => e.key === 'Enter' && handleSubmit()}
                />
                <button onClick={() => setShowPass(p => !p)} style={{
                  position: 'absolute', right: 12, top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', cursor: 'pointer',
                  fontSize: 16, color: 'var(--text-muted)',
                }}>{showPass ? '🙈' : '👁️'}</button>
              </div>
            </div>

            <button
              onClick={handleSubmit}
              disabled={!canSubmit || loading}
              className="btn btn-primary"
              style={{ justifyContent: 'center', padding: '12px', fontSize: 15, marginTop: 4 }}
            >
              {loading
                ? <><div style={{ width: 16, height: 16, border: '2px solid rgba(255,255,255,.4)', borderTopColor: 'white', borderRadius: '50%', animation: 'spin .7s linear infinite' }} /> Please wait…</>
                : mode === 'login' ? '🌾 Login' : '✅ Create Account'
              }
            </button>

            {mode === 'login' && (
              <p style={{ fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                New farmer?{' '}
                <button onClick={() => setMode('register')} style={{ background: 'none', border: 'none', color: 'var(--accent)', fontWeight: 600, cursor: 'pointer', fontSize: 12 }}>
                  Create account →
                </button>
              </p>
            )}
          </div>
        </div>

        <p style={{ textAlign: 'center', fontSize: 11, color: 'var(--text-muted)', marginTop: 20 }}>
          KrishiMitra AI · Prices from Agmarknet · ₹/quintal · Secure JWT auth
        </p>
      </div>
    </div>
  );
}
