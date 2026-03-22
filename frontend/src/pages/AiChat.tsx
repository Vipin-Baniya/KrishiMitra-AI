import React, { useState, useRef, useEffect } from 'react';
import { useMutation } from 'react-query';
import toast from 'react-hot-toast';
import { chatApi, useAuthStore, useChatStore } from '../services/api';
import { useDevice } from '../hooks/useDevice';
import { useVoice } from '../hooks/useVoice';
import type { ChatMessage } from '../services/api';

const QUICK_PROMPTS = [
  'गेहूं अभी बेचूं या रुकूं?',
  'Wheat price next 15 days?',
  'Best mandi near Indore for wheat?',
  'Should I store tomatoes or sell now?',
  'Which crop to plant this rabi season?',
  'Profit if I wait 2 weeks for soybean?',
];

const LANGUAGES = [
  { code:'en', label:'English', sr:'en-IN' },
  { code:'hi', label:'हिंदी',   sr:'hi-IN' },
  { code:'mr', label:'मराठी',   sr:'mr-IN' },
  { code:'gu', label:'ગુજરાતી', sr:'gu-IN' },
  { code:'pa', label:'ਪੰਜਾਬੀ',  sr:'pa-IN' },
];

const SYSTEM_GREETING: ChatMessage = {
  role: 'assistant',
  content: `Namaskar! 🌾 Main KrishiMitra AI hoon — aapka smart fasal advisor.\n\nMain aapki help kar sakta hoon:\n• Mandi price analysis & 30-day predictions\n• Best time to sell your crops\n• Which mandi gives maximum price\n• Crop planning & storage decisions\n\nKya jaanna chahte hain? Ask me anything in Hindi or English!`,
};

export default function AiChatPage() {
  const { isMobile } = useDevice();
  const farmer       = useAuthStore(s => s.farmer);
  const { sessions, activeId, addMessage, newSession } = useChatStore();

  const [messages,   setMessages]   = useState<ChatMessage[]>([SYSTEM_GREETING]);
  const [input,      setInput]      = useState('');
  const [language,   setLanguage]   = useState(farmer?.preferredLang ?? 'hi');
  const [sessionId,  setSessionId]  = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef  = useRef<HTMLTextAreaElement>(null);

  // ── Fix #5: Voice wired to real SpeechRecognition ────────────
  const srLang = LANGUAGES.find(l => l.code === language)?.sr ?? 'hi-IN';
  const { listening, supported: voiceSupported, toggle: toggleVoice } = useVoice({
    language:    srLang,
    onTranscript: (text) => {
      setInput(prev => prev ? `${prev} ${text}` : text);
      inputRef.current?.focus();
    },
    onError: (msg) => toast.error(msg),
  });

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const sendMutation = useMutation(
    async (text: string) => chatApi.send({
      sessionId,
      message:  text,
      language,
      context: farmer ? {
        location:   `${farmer.district}, ${farmer.state}`,
        crops:      farmer.crops.map(c => c.commodity),
        farmerName: farmer.name,
      } : undefined,
    }),
    {
      onSuccess: (resp) => {
        setSessionId(resp.sessionId);
        setMessages(prev => [...prev, { role:'assistant', content: resp.content }]);
        inputRef.current?.focus();
      },
      onError: () => {
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: '⚠️ Connection error. Please check your network and try again.',
        }]);
        toast.error('Connection failed');
      },
    }
  );

  function handleSend(text?: string) {
    const msg = (text ?? input).trim();
    if (!msg || sendMutation.isLoading) return;
    setInput('');
    setMessages(prev => [...prev, { role:'user', content: msg }]);
    sendMutation.mutate(msg);
  }

  function handleKey(e: React.KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div style={{
      height:        isMobile ? 'calc(100dvh - 132px)' : '100dvh',
      display:       'flex',
      flexDirection: 'column',
    }}>
      {/* Header */}
      <div style={{
        padding:        '12px 20px',
        borderBottom:   '1px solid var(--border)',
        background:     'var(--bg-surface)',
        display:        'flex',
        alignItems:     'center',
        justifyContent: 'space-between',
        flexShrink:     0,
      }}>
        <div className="flex items-center gap-3">
          <div style={{
            width:46, height:46, borderRadius:'var(--r-md)',
            background:'var(--accent)', display:'flex',
            alignItems:'center', justifyContent:'center', fontSize:22,
          }}>🤖</div>
          <div>
            <div style={{ fontFamily:'var(--font-display)', fontWeight:700, fontSize:15 }}>KrishiMitra AI</div>
            <div className="flex items-center gap-2" style={{ fontSize:11, color:'var(--text-muted)' }}>
              <span className="pulse-dot" style={{ width:6, height:6 }} />
              Custom model → GPT-4o → Claude
            </div>
          </div>
        </div>
        <div className="flex gap-2 items-center">
          <select
            value={language}
            onChange={e => setLanguage(e.target.value)}
            className="select"
            style={{ fontSize:12, padding:'5px 28px 5px 10px', width:'auto' }}
          >
            {LANGUAGES.map(l => <option key={l.code} value={l.code}>{l.label}</option>)}
          </select>
          <button className="btn btn-ghost btn-sm" onClick={() => { newSession(); setSessionId(null); setMessages([SYSTEM_GREETING]); }}>
            New Chat
          </button>
        </div>
      </div>

      {/* Messages */}
      <div style={{
        flex:1, overflow:'auto',
        padding: isMobile ? '12px 14px' : '18px 22px',
        display:'flex', flexDirection:'column', gap:12,
      }}>
        {messages.map((msg, i) => (
          <div
            key={i}
            className="fade-in"
            style={{
              display:'flex', flexDirection:'column',
              alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start',
              animationDelay: `${i * 0.04}s`,
            }}
          >
            {msg.role === 'assistant' && (
              <div className="flex items-center gap-2" style={{ marginBottom:4 }}>
                <div style={{
                  width:20, height:20, borderRadius:'50%',
                  background:'var(--accent-dim)', display:'flex',
                  alignItems:'center', justifyContent:'center', fontSize:11,
                }}>🤖</div>
                <span style={{ fontSize:11, color:'var(--text-muted)', fontWeight:500 }}>KrishiMitra</span>
              </div>
            )}
            <div className={`chat-bubble ${msg.role}`}>
              <pre style={{
                fontFamily:'var(--font-body)', fontSize:14,
                lineHeight:1.65, margin:0,
                whiteSpace:'pre-wrap', wordBreak:'break-word',
                color: msg.role === 'user' ? 'white' : 'var(--text-primary)',
              }}>
                {msg.content}
              </pre>
            </div>
          </div>
        ))}

        {/* Typing indicator */}
        {sendMutation.isLoading && (
          <div className="flex-col items-start gap-1 fade-in">
            <div className="flex items-center gap-2" style={{ marginBottom:4 }}>
              <div style={{ width:20, height:20, borderRadius:'50%', background:'var(--accent-dim)', display:'flex', alignItems:'center', justifyContent:'center', fontSize:11 }}>🤖</div>
              <span style={{ fontSize:11, color:'var(--text-muted)' }}>KrishiMitra is typing…</span>
            </div>
            <div className="chat-bubble ai" style={{ display:'flex', gap:5, alignItems:'center', padding:'12px 16px' }}>
              {[0,1,2].map(j => (
                <div key={j} style={{
                  width:7, height:7, borderRadius:'50%',
                  background:'var(--text-muted)',
                  animation:`pulse 1.4s ease-in-out ${j*0.2}s infinite`,
                }}/>
              ))}
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Quick prompts */}
      {messages.length <= 1 && (
        <div style={{ padding:'0 20px 10px', display:'flex', flexWrap:'wrap', gap:7 }}>
          {QUICK_PROMPTS.map((q,i) => (
            <button key={i} onClick={() => handleSend(q)} className="btn btn-ghost btn-sm" style={{ fontSize:12 }}>
              {q}
            </button>
          ))}
        </div>
      )}

      {/* Input area */}
      <div style={{
        padding:       isMobile ? '10px 14px' : '12px 20px',
        borderTop:     '1px solid var(--border)',
        background:    'var(--bg-surface)',
        flexShrink:    0,
      }}>
        <div className="flex gap-2 items-end">

          {/* ── Voice button — wired (Fix #5) ── */}
          <button
            onClick={toggleVoice}
            className="btn btn-icon"
            title={
              !voiceSupported ? 'Voice not supported in this browser (use Chrome/Edge)' :
              listening       ? 'Click to stop recording' :
                                `Voice input (${srLang})`
            }
            style={{
              flexShrink:      0,
              background:      listening ? 'var(--danger)' : 'var(--bg-elevated)',
              color:           listening ? 'white' : voiceSupported ? 'var(--text-secondary)' : 'var(--text-muted)',
              border:          `1px solid ${listening ? 'var(--danger)' : 'var(--border)'}`,
              animation:       listening ? 'pulse 1.2s ease-in-out infinite' : 'none',
              cursor:          voiceSupported ? 'pointer' : 'not-allowed',
              opacity:         voiceSupported ? 1 : 0.45,
              fontSize:        16,
              position:        'relative',
            }}
            disabled={!voiceSupported}
          >
            {listening ? '⏹' : '🎤'}
          </button>

          {/* Listening indicator banner */}
          {listening && (
            <div style={{
              position:'absolute', bottom:80,
              left:'50%', transform:'translateX(-50%)',
              background:'var(--danger)', color:'white',
              borderRadius:'var(--r-full)', padding:'6px 16px',
              fontSize:12, fontWeight:600,
              display:'flex', alignItems:'center', gap:6,
              zIndex:10, whiteSpace:'nowrap',
              boxShadow:'var(--shadow-md)',
            }}>
              <div style={{ width:8, height:8, borderRadius:'50%', background:'white', animation:'pulse 1s infinite' }}/>
              Listening… speak in {srLang}
            </div>
          )}

          <textarea
            ref={inputRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKey}
            placeholder={listening ? 'Listening…' : 'Ask in Hindi or English… (Enter to send)'}
            className="input"
            rows={1}
            style={{ resize:'none', maxHeight:120, overflow:'auto', lineHeight:1.5 }}
          />

          <button
            onClick={() => handleSend()}
            disabled={!input.trim() || sendMutation.isLoading}
            className="btn btn-primary btn-icon"
            style={{ flexShrink:0 }}
            title="Send"
          >
            {sendMutation.isLoading
              ? <div style={{ width:16, height:16, border:'2px solid rgba(255,255,255,0.4)', borderTopColor:'white', borderRadius:'50%', animation:'spin 0.7s linear infinite' }}/>
              : '↗'
            }
          </button>
        </div>

        <div style={{ fontSize:10, color:'var(--text-muted)', marginTop:6, textAlign:'center' }}>
          {voiceSupported
            ? `🎤 Voice input active · ${srLang} · Custom LLM → OpenAI → Claude`
            : 'Custom LLM → OpenAI GPT-4o → Claude (fallback) · Prices from Agmarknet'
          }
        </div>
      </div>
    </div>
  );
}
