package com.krishimitra.service;

import com.krishimitra.client.LlmClient;
import static com.krishimitra.client.LlmClient.LlmRequest;
import static com.krishimitra.client.LlmClient.LlmResponse;
import com.krishimitra.dto.*;
import com.krishimitra.exception.ApiException;
import com.krishimitra.mapper.FarmerMapper;
import com.krishimitra.model.entity.*;
import com.krishimitra.monitoring.KrishiMitraMetrics;
import com.krishimitra.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * AiChatService — complete implementation.
 *
 * Flow:
 *  1. Resolve or create ChatSession
 *  2. Load last N messages for context window
 *  3. Build context string (farmer profile, crops, recent prices)
 *  4. Call LLM via LlmClient (custom → OpenAI → Claude)
 *  5. Persist user + assistant messages
 *  6. Update session.lastMsgAt
 *  7. Record metrics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    private static final int CONTEXT_WINDOW_MSGS = 10;  // last 10 msgs injected

    private final ChatSessionRepository    sessionRepo;
    private final ChatMessageRepository    messageRepo;
    private final FarmerRepository         farmerRepo;
    private final MandiPriceRepository     priceRepo;
    private final LlmClient               llmClient;
    private final KrishiMitraMetrics       metrics;

    // ── Public API ────────────────────────────────────────────

    @Transactional
    public ChatResponse sendMessage(UUID farmerId, ChatRequest req) {
        long start = System.currentTimeMillis();

        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        // Resolve or create session
        ChatSession session = resolveSession(req.sessionId() != null ? req.sessionId().toString() : null, farmer);

        // Load conversation history for context
        List<ChatMessage> history = messageRepo.findLastNMessages(session.getId(), CONTEXT_WINDOW_MSGS);

        // Build system context injected before every call
        String systemContext = buildSystemContext(farmer);

        // Convert history to LLM message format
        List<LlmMessage> llmMessages = buildLlmMessages(systemContext, history, req.message(), req.language());

        // Call LLM router
        // Build messages as List<Map<String,String>> to match LlmClient.LlmRequest
        java.util.List<java.util.Map<String,String>> msgMaps = llmMessages.stream()
                .map(m -> java.util.Map.of("role", m.role(), "content", m.content()))
                .toList();

        LlmResponse llmResp = llmClient.chat(new LlmRequest(
                msgMaps,
                req.language() != null ? req.language() : farmer.getPreferredLang(),
                null,
                800,
                0.4
        ));

        long latencyMs = System.currentTimeMillis() - start;

        // Persist user message
        messageRepo.save(ChatMessage.builder()
                .session(session)
                .role("user")
                .content(req.message())
                .build());

        // Persist assistant message
        messageRepo.save(ChatMessage.builder()
                .session(session)
                .role("assistant")
                .content(llmResp.content())
                .modelUsed(llmResp.modelUsed())
                .tokensUsed(llmResp.tokensUsed())
                .latencyMs((int) latencyMs)
                .build());

        // Update session lastMsgAt
        session.setLastMsgAt(Instant.now());
        sessionRepo.save(session);

        // Metrics
        metrics.recordChatMessage(
                req.language() != null ? req.language() : farmer.getPreferredLang(),
                llmResp.modelUsed(),
                latencyMs
        );

        log.debug("Chat response: sessionId={} model={} latency={}ms",
                session.getId(), llmResp.modelUsed(), latencyMs);

        return new ChatResponse(
                session.getId(),
                llmResp.content(),
                llmResp.modelUsed(),
                llmResp.confidence(),
                latencyMs,
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> getSessions(UUID farmerId) {
        return sessionRepo.findByFarmerIdOrderByLastMsgAtDesc(farmerId)
                .stream()
                .map(s -> {
                    List<ChatMessage> msgs = messageRepo.findLastNMessages(s.getId(), 1);
                    String lastMsg = msgs.isEmpty() ? "" : msgs.get(0).getContent();
                    // Truncate for preview
                    if (lastMsg.length() > 80) lastMsg = lastMsg.substring(0, 77) + "...";
                    return ChatSessionResponse.builder()
                            .id(s.getId().toString())
                            .lastMessage(lastMsg)
                            .createdAt(s.getCreatedAt().toString())
                            .build();
                })
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────

    private ChatSession resolveSession(String sessionIdStr, Farmer farmer) {
        if (sessionIdStr != null) {
            try {
                UUID sid = UUID.fromString(sessionIdStr);
                return sessionRepo.findById(sid)
                        .filter(s -> s.getFarmer().getId().equals(farmer.getId()))
                        .orElseGet(() -> createSession(farmer));
            } catch (IllegalArgumentException e) {
                // malformed UUID — create new session
            }
        }
        return createSession(farmer);
    }

    private ChatSession createSession(Farmer farmer) {
        return sessionRepo.save(ChatSession.builder()
                .farmer(farmer)
                .language(farmer.getPreferredLang())
                .lastMsgAt(Instant.now())
                .build());
    }

    private String buildSystemContext(Farmer farmer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are KrishiMitra AI — a smart farming advisor for Indian farmers. ");
        sb.append("Answer in the language the farmer uses (Hindi or English). ");
        sb.append("Keep answers concise, practical, and use ₹/quintal for prices.\n\n");

        sb.append("FARMER PROFILE:\n");
        sb.append("Name: ").append(farmer.getName()).append("\n");
        sb.append("Location: ").append(farmer.getDistrict()).append(", ").append(farmer.getState()).append("\n");

        if (farmer.getCrops() != null && !farmer.getCrops().isEmpty()) {
            sb.append("Crops: ");
            farmer.getCrops().forEach(c ->
                    sb.append(c.getCommodity())
                      .append(" (").append(c.getQuantityQuintal()).append(" qtl), ")
            );
            sb.append("\n");
        }

        // Inject latest prices for farmer's crops
        sb.append("\nCURRENT PRICES (today):\n");
        if (farmer.getCrops() != null) {
            farmer.getCrops().stream().limit(3).forEach(crop -> {
                priceRepo.findTopByCommodityAndStateOrderByPriceDateDesc(
                        crop.getCommodity(), farmer.getState())
                        .ifPresent(p -> sb.append(crop.getCommodity())
                                .append(" @ ").append(farmer.getDistrict()).append(": ₹")
                                .append(p.getModalPrice()).append("/qtl\n"));
            });
        }
        return sb.toString();
    }

    /** Simple value object matching LlmClient message format */
    private record LlmMessage(String role, String content) {}

    private List<LlmMessage> buildLlmMessages(
            String systemContext,
            List<ChatMessage> history,
            String newUserMessage,
            String language) {

        List<LlmMessage> msgs = new ArrayList<>();

        // System message first
        msgs.add(new LlmMessage("system", systemContext));

        // Reversed history (last N, now in chronological order)
        List<ChatMessage> chronological = new ArrayList<>(history);
        Collections.reverse(chronological);
        chronological.forEach(h ->
                msgs.add(new LlmMessage(h.getRole(), h.getContent()))
        );

        // New user message
        msgs.add(new LlmMessage("user", newUserMessage));

        return msgs;
    }
}
