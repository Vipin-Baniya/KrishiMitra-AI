// ════════════════════════════════════════════════════════════
//  mobile/App.tsx  — Root component
// ════════════════════════════════════════════════════════════
import React, { useEffect } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Toast from 'react-native-toast-message';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import AppNavigator from './src/navigation/AppNavigator';
import { NotificationService } from './src/services/notifications_voice_sms';
import { VoiceService } from './src/services/notifications_voice_sms';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry:               1,
      staleTime:           5 * 60 * 1000,   // 5 min
      refetchOnWindowFocus: false,
    },
  },
});

export default function App() {
  useEffect(() => {
    // Init push notifications (FCM/APNs)
    NotificationService.init().catch(err =>
      console.warn('Push notification init failed:', err)
    );
    // Init TTS for Hindi voice
    VoiceService.init().catch(err =>
      console.warn('Voice init failed:', err)
    );
  }, []);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
          <AppNavigator />
          <Toast />
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
