// src/hooks/useVoice.ts
// Browser Web Speech API hook — Chrome, Edge, Safari 15+
// Falls back gracefully when not supported

import { useState, useRef, useCallback } from 'react';

interface UseVoiceOptions {
  language?:    string;   // e.g. 'hi-IN' | 'en-IN'
  onTranscript: (text: string) => void;
  onError?:     (msg: string) => void;
}

interface UseVoiceReturn {
  listening:    boolean;
  supported:    boolean;
  start:        () => void;
  stop:         () => void;
  toggle:       () => void;
}

// Vendor-prefixed SpeechRecognition (Safari uses webkit prefix)
const SpeechRecognition =
  (window as any).SpeechRecognition ||
  (window as any).webkitSpeechRecognition ||
  null;

export function useVoice({
  language = 'hi-IN',
  onTranscript,
  onError,
}: UseVoiceOptions): UseVoiceReturn {
  const [listening, setListening] = useState(false);
  const recogRef = useRef<any>(null);
  const supported = SpeechRecognition !== null;

  const stop = useCallback(() => {
    if (recogRef.current) {
      recogRef.current.stop();
      recogRef.current = null;
    }
    setListening(false);
  }, []);

  const start = useCallback(() => {
    if (!supported) {
      onError?.('Voice input is not supported in this browser. Try Chrome or Edge.');
      return;
    }
    // Stop any existing session
    stop();

    const recog = new SpeechRecognition();
    recog.lang              = language;
    recog.interimResults    = false;
    recog.maxAlternatives   = 1;
    recog.continuous        = false;

    recog.onstart  = () => setListening(true);
    recog.onend    = () => setListening(false);
    recog.onerror  = (e: any) => {
      setListening(false);
      const msg = e.error === 'not-allowed'
        ? 'Microphone permission denied. Please allow microphone access.'
        : e.error === 'no-speech'
        ? 'No speech detected. Please try again.'
        : `Voice error: ${e.error}`;
      onError?.(msg);
    };
    recog.onresult = (e: any) => {
      const transcript = e.results[0]?.[0]?.transcript;
      if (transcript) onTranscript(transcript.trim());
    };

    recogRef.current = recog;
    recog.start();
  }, [supported, language, onTranscript, onError, stop]);

  const toggle = useCallback(() => {
    listening ? stop() : start();
  }, [listening, start, stop]);

  return { listening, supported, start, stop, toggle };
}
