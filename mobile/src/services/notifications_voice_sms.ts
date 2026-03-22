// ════════════════════════════════════════════════════════════
//  PUSH NOTIFICATIONS — FCM (Android) + APNs (iOS)
//  File: src/services/notifications.ts (React Native)
// ════════════════════════════════════════════════════════════
import { Platform, Alert } from 'react-native';
import messaging from '@react-native-firebase/messaging';
import PushNotification, { Importance } from 'react-native-push-notification';
import { api } from './api';

export class NotificationService {

  static async init(): Promise<void> {
    // Create notification channels (Android 8+)
    PushNotification.createChannel({
      channelId:   'krishimitra-prices',
      channelName: 'Price Alerts',
      channelDescription: 'Mandi price spikes and sell windows',
      importance:  Importance.HIGH,
      vibrate:     true,
      soundName:   'default',
    }, () => {});

    PushNotification.createChannel({
      channelId:   'krishimitra-general',
      channelName: 'General Alerts',
      channelDescription: 'KrishiMitra notifications',
      importance:  Importance.DEFAULT,
    }, () => {});

    // Request permission (iOS)
    if (Platform.OS === 'ios') {
      const status = await messaging().requestPermission();
      if (status !== messaging.AuthorizationStatus.AUTHORIZED &&
          status !== messaging.AuthorizationStatus.PROVISIONAL) {
        console.warn('Push notification permission denied');
        return;
      }
    }

    // Get + register FCM token
    await NotificationService.registerToken();

    // Foreground message handler
    messaging().onMessage(async remoteMessage => {
      const { notification, data } = remoteMessage;
      PushNotification.localNotification({
        channelId:   data?.channelId ?? 'krishimitra-general',
        title:       notification?.title ?? 'KrishiMitra',
        message:     notification?.body ?? '',
        bigText:     notification?.body,
        playSound:   true,
        soundName:   'default',
        importance:  data?.urgent === 'true' ? 'high' : 'default',
        userInfo:    data,
      });
    });

    // Background / quit state — notification opened
    messaging().onNotificationOpenedApp(remoteMessage => {
      NotificationService.handleOpen(remoteMessage.data);
    });

    // App launched from notification
    messaging().getInitialNotification().then(remoteMessage => {
      if (remoteMessage) {
        NotificationService.handleOpen(remoteMessage.data);
      }
    });

    // Token refresh
    messaging().onTokenRefresh(token => {
      NotificationService.sendTokenToServer(token);
    });
  }

  static async registerToken(): Promise<string | null> {
    try {
      await messaging().registerDeviceForRemoteMessages();
      const token = await messaging().getToken();
      await NotificationService.sendTokenToServer(token);
      return token;
    } catch (e) {
      console.error('FCM token error:', e);
      return null;
    }
  }

  static async sendTokenToServer(token: string): Promise<void> {
    try {
      await api.post('/farmer/push-token', {
        token,
        platform: Platform.OS,
        appVersion: '1.0.0',
      });
    } catch (e) {
      console.warn('Failed to register push token:', e);
    }
  }

  static handleOpen(data?: Record<string, string>): void {
    if (!data) return;
    // Navigation handled by deep link — data.screen + data.params
    // e.g. { screen: 'ForecastDetail', commodity: 'Wheat', mandi: 'Indore' }
  }
}

// ════════════════════════════════════════════════════════════
//  PUSH TOKEN ENDPOINT — Spring Boot additions
//  File: PushTokenController.java
// ════════════════════════════════════════════════════════════
package com.krishimitra.controller;

import com.krishimitra.model.entity.Farmer;
import com.krishimitra.repository.FarmerRepository;
import com.krishimitra.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/farmer")
@RequiredArgsConstructor
class PushTokenController {

    private final FarmerRepository farmerRepository;
    private final FcmService fcmService;

    @PostMapping("/push-token")
    ResponseEntity<ApiResponse<Void>> registerToken(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @RequestBody Map<String, String> body) {

        farmerRepository.findById(principal.farmerId()).ifPresent(farmer -> {
            farmer.setPushToken(body.get("token"));
            farmer.setPushPlatform(body.get("platform"));
            farmerRepository.save(farmer);
        });
        return ResponseEntity.ok(ApiResponse.success(null, "Token registered"));
    }
}

@Service
class FcmService {

    private static final String FCM_URL = "https://fcm.googleapis.com/v1/projects/{projectId}/messages:send";

    @Value("${fcm.project-id}") private String projectId;
    @Value("${fcm.service-account-path}") private String serviceAccountPath;
    private final WebClient webClient;

    public void sendToFarmer(UUID farmerId, String title, String body,
                              Map<String, String> data, String channelId) {
        farmerRepository.findById(farmerId).ifPresent(farmer -> {
            if (farmer.getPushToken() == null) return;
            sendToToken(farmer.getPushToken(), farmer.getPushPlatform(), title, body, data, channelId);
        });
    }

    public void sendToToken(String token, String platform,
                             String title, String body,
                             Map<String, String> data, String channelId) {
        Map<String, Object> message = buildMessage(token, platform, title, body, data, channelId);
        webClient.post()
                .uri(FCM_URL.replace("{projectId}", projectId))
                .header("Authorization", "Bearer " + getAccessToken())
                .bodyValue(Map.of("message", message))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                    r -> log.debug("FCM sent: {}", r),
                    e -> log.error("FCM error: {}", e.getMessage())
                );
    }

    private Map<String, Object> buildMessage(String token, String platform,
                                              String title, String body,
                                              Map<String, String> data, String channelId) {
        var msg = new java.util.LinkedHashMap<String, Object>();
        msg.put("token", token);
        msg.put("notification", Map.of("title", title, "body", body));
        if (data != null) msg.put("data", data);

        if ("android".equals(platform)) {
            msg.put("android", Map.of(
                "priority", "high",
                "notification", Map.of(
                    "channel_id", channelId != null ? channelId : "krishimitra-general",
                    "sound", "default",
                    "default_vibrate_timings", true
                )
            ));
        } else if ("ios".equals(platform)) {
            msg.put("apns", Map.of(
                "payload", Map.of(
                    "aps", Map.of(
                        "sound", "default",
                        "badge", 1,
                        "content-available", 1
                    )
                ),
                "headers", Map.of("apns-priority", "10")
            ));
        }
        return msg;
    }
}


// ════════════════════════════════════════════════════════════
//  SMS + WHATSAPP — Twilio integration
//  File: TwilioService.java (Spring Boot)
// ════════════════════════════════════════════════════════════
package com.krishimitra.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TwilioService {

    @Value("${twilio.account-sid}")    private String accountSid;
    @Value("${twilio.auth-token}")     private String authToken;
    @Value("${twilio.sms-from}")       private String smsFrom;       // +91XXXXXXXXXX
    @Value("${twilio.whatsapp-from}")  private String whatsappFrom;  // whatsapp:+14155238886

    @PostConstruct
    void init() { Twilio.init(accountSid, authToken); }

    /**
     * Send SMS price alert (for feature phones / no internet).
     * Format: "KrishiMitra: Gehun ₹2,450/qtl @ Indore. Aaj bechein! Reply HELP"
     */
    public void sendSmsAlert(String toPhone, String message) {
        try {
            Message.creator(new PhoneNumber("+91" + toPhone), new PhoneNumber(smsFrom), message)
                   .create();
            log.info("SMS sent to +91{}", toPhone);
        } catch (Exception e) {
            log.error("SMS failed to +91{}: {}", toPhone, e.getMessage());
        }
    }

    /**
     * Send WhatsApp message (preferred — free tier via sandbox).
     * Template approved messages bypass the 24h window.
     */
    public void sendWhatsApp(String toPhone, String message) {
        try {
            Message.creator(
                    new PhoneNumber("whatsapp:+91" + toPhone),
                    new PhoneNumber(whatsappFrom),
                    message
            ).create();
            log.info("WhatsApp sent to +91{}", toPhone);
        } catch (Exception e) {
            log.error("WhatsApp failed to +91{}: {}", toPhone, e.getMessage());
        }
    }

    /** Formats a sell alert into a farmer-friendly bilingual SMS. */
    public String formatSellAlert(String commodity, int price, String mandi, String decision) {
        return switch (decision) {
            case "SELL_NOW"    -> String.format("KrishiMitra: %s ₹%,d/qtl @ %s. ABHI BECHEIN! | Sell NOW at %s. Reply HELP", commodity, price, mandi, mandi);
            case "WAIT_N_DAYS" -> String.format("KrishiMitra: %s ₹%,d/qtl @ %s. Ruko — agle 7 din mein bhav badhega. | WAIT 7 days.", commodity, price, mandi);
            default            -> String.format("KrishiMitra: %s ₹%,d/qtl @ %s. Abhi mat becho. | HOLD for now.", commodity, price, mandi);
        };
    }
}


// ════════════════════════════════════════════════════════════
//  VOICE INPUT + HINDI TTS — React Native
//  File: src/services/voice.ts
// ════════════════════════════════════════════════════════════
import Voice, { SpeechResultsEvent, SpeechErrorEvent } from '@react-native-voice/voice';
import Tts from 'react-native-tts';
import { Platform } from 'react-native';

export class VoiceService {

  static async init(): Promise<void> {
    // TTS setup — prefer Hindi voice
    await Tts.setDefaultLanguage('hi-IN');
    await Tts.setDefaultRate(0.5);   // slightly slow for clarity
    await Tts.setDefaultPitch(1.0);

    // Fallback to English if Hindi not available
    const voices = await Tts.voices();
    const hindiVoice = voices.find(v => v.language === 'hi-IN' && !v.notInstalled);
    if (hindiVoice) {
      await Tts.setDefaultVoice(hindiVoice.id);
    } else {
      await Tts.setDefaultLanguage('en-IN');
    }
  }

  // ── Speech-to-Text ─────────────────────────────────────────
  static async startListening(
    onResult: (text: string) => void,
    onError:  (err: string)  => void,
    language = 'hi-IN',
  ): Promise<void> {
    try {
      Voice.onSpeechResults = (e: SpeechResultsEvent) => {
        const best = e.value?.[0];
        if (best) onResult(best);
      };
      Voice.onSpeechError = (e: SpeechErrorEvent) => {
        onError(e.error?.message ?? 'Voice error');
      };

      // Try Hindi first, fall back to English India
      await Voice.start(language);
    } catch {
      try { await Voice.start('en-IN'); }
      catch (e: any) { onError(e.message); }
    }
  }

  static async stopListening(): Promise<void> {
    try { await Voice.stop(); } catch { /* ignore */ }
  }

  static async destroy(): Promise<void> {
    try { await Voice.destroy(); } catch { /* ignore */ }
    Voice.removeAllListeners();
  }

  // ── Text-to-Speech ─────────────────────────────────────────
  static speak(text: string, lang = 'hi-IN'): void {
    Tts.stop();
    // Strip markdown / special chars before speaking
    const clean = text.replace(/₹/g,'rupaye').replace(/[*_`#]/g,'').trim();
    Tts.speak(clean);
  }

  static stop(): void { Tts.stop(); }

  /** Converts AI response to a short spoken summary. */
  static summariseForSpeech(aiResponse: string): string {
    // Take first 2 sentences
    const sentences = aiResponse.split(/[।.!?]+/).filter(s => s.trim().length > 10);
    return sentences.slice(0, 2).join('। ') + '।';
  }
}

// ── VoiceChatButton component (drop into AiChatScreen) ──────
import React, { useState } from 'react';
import { TouchableOpacity, View, Text, Animated } from 'react-native';
import { useTheme, FONT_WEIGHT } from './services/foundation';

export function VoiceChatButton({
  onTranscript,
  onError,
  language = 'hi-IN',
}: {
  onTranscript: (text: string) => void;
  onError?:     (err: string)  => void;
  language?:    string;
}) {
  const { colors } = useTheme();
  const [listening, setListening] = useState(false);
  const pulse = new Animated.Value(1);

  async function toggle() {
    if (listening) {
      await VoiceService.stopListening();
      setListening(false);
      Animated.timing(pulse, { toValue:1, duration:200, useNativeDriver:true }).start();
      return;
    }
    setListening(true);
    Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue:1.25, duration:600, useNativeDriver:true }),
        Animated.timing(pulse, { toValue:1,    duration:600, useNativeDriver:true }),
      ])
    ).start();
    await VoiceService.startListening(
      (text) => { setListening(false); pulse.stopAnimation(); pulse.setValue(1); onTranscript(text); },
      (err)  => { setListening(false); pulse.stopAnimation(); pulse.setValue(1); onError?.(err); },
      language,
    );
  }

  return (
    <Animated.View style={{ transform:[{ scale: pulse }] }}>
      <TouchableOpacity onPress={toggle} style={{
        width:44, height:44, borderRadius:22,
        backgroundColor: listening ? colors.danger : colors.elevated,
        alignItems:'center', justifyContent:'center',
        borderWidth:1.5,
        borderColor: listening ? colors.danger : colors.border,
      }}>
        <Text style={{ fontSize:20 }}>{listening ? '⏹' : '🎤'}</Text>
      </TouchableOpacity>
    </Animated.View>
  );
}
