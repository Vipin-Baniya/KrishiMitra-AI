package com.krishimitra.exception;

import org.springframework.http.HttpStatus;
import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException notFound(String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, msg);
    }
    public static ApiException unauthorized(String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, msg);
    }
    public static ApiException forbidden(String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, msg);
    }
    public static ApiException conflict(String msg) {
        return new ApiException(HttpStatus.CONFLICT, msg);
    }
    public static ApiException badRequest(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }
    public static ApiException serviceUnavailable(String msg) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, msg);
    }
}

// ─────────────────────────────────────────────────────────────
// AI CHAT SERVICE
// ─────────────────────────────────────────────────────────────

package com.krishimitra.service;

import com.krishimitra.client.LlmClient;
import com.krishimitra.dto.*;
import com.krishimitra.model.entity.*;
import com.krishimitra.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    private final LlmClient llmClient;
    private final FarmerRepository farmerRepo;
    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final PriceService priceService;

    @Transactional
    public ChatResponse chat(UUID farmerId, ChatRequest req) {
        long start = System.currentTimeMillis();

        // Resolve or create chat session
        ChatSession session;
        if (req.sessionId() != null) {
            session = sessionRepo.findById(req.sessionId())
                    .filter(s -> s.getFarmer().getId().equals(farmerId))
                    .orElseGet(() -> createSession(farmerId, req.language()));
        } else {
            session = createSession(farmerId, req.language());
        }

        // Build message history (last 10 turns for context window)
        List<Map<String, String>> history = messageRepo
                .findRecentBySessionId(session.getId(), 10).stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();

        // Add the new user message
        List<Map<String, String>> messages = new ArrayList<>(history);
        messages.add(Map.of("role", "user", "content", req.message()));

        // Build farmer context for the LLM
        Object context = buildContext(farmerId, req);

        // Call LLM router (custom → OpenAI → Claude)
        LlmClient.LlmRequest llmReq = new LlmClient.LlmRequest(
                messages,
                req.language() != null ? req.language() : "hi",
                context,
                512,
                0.3
        );

        LlmClient.LlmResponse llmResp = llmClient.chat(llmReq);
        if (llmResp == null) {
            llmResp = new LlmClient.LlmResponse(
                    "Sorry, the AI assistant is temporarily unavailable. Please try again.",
                    "fallback", 0L, 0.0, "all_models_down", 0);
        }

        // Persist user message
        saveMessage(session, "user",    req.message(),          null, null, null);
        // Persist assistant response
        saveMessage(session, "assistant", llmResp.content(),
                llmResp.modelUsed(), llmResp.tokensUsed(),
                (int)(System.currentTimeMillis() - start));

        // Update session last_msg_at
        session.setLastMsgAt(Instant.now());
        sessionRepo.save(session);

        return new ChatResponse(
                session.getId(),
                llmResp.content(),
                llmResp.modelUsed(),
                llmResp.confidence(),
                System.currentTimeMillis() - start,
                Instant.now()
        );
    }

    public List<AiChatController.ChatSessionDto> getSessions(UUID farmerId) {
        return sessionRepo.findByFarmerIdOrderByLastMsgAtDesc(farmerId, 10).stream()
                .map(s -> {
                    String lastMsg = messageRepo
                            .findRecentBySessionId(s.getId(), 1)
                            .stream().map(ChatMessage::getContent)
                            .findFirst().orElse("");
                    return new AiChatController.ChatSessionDto(
                            s.getId(),
                            lastMsg.length() > 80 ? lastMsg.substring(0, 80) + "…" : lastMsg,
                            s.getCreatedAt().toString());
                })
                .toList();
    }

    private ChatSession createSession(UUID farmerId, String language) {
        Farmer farmer = farmerRepo.getReferenceById(farmerId);
        return sessionRepo.save(ChatSession.builder()
                .farmer(farmer)
                .language(language != null ? language : "hi")
                .build());
    }

    private void saveMessage(ChatSession session, String role, String content,
                              String model, Integer tokens, Integer latencyMs) {
        messageRepo.save(ChatMessage.builder()
                .session(session)
                .role(role)
                .content(content)
                .modelUsed(model)
                .tokensUsed(tokens)
                .latencyMs(latencyMs)
                .build());
    }

    private Object buildContext(UUID farmerId, ChatRequest req) {
        if (req.context() != null) return req.context();
        // Auto-build context from farmer profile + live prices
        try {
            var farmer = farmerRepo.findById(farmerId).orElse(null);
            if (farmer == null) return null;
            return Map.of(
                    "location",    farmer.getDistrict() + ", " + farmer.getState(),
                    "farmerName",  farmer.getName()
            );
        } catch (Exception e) {
            return null;
        }
    }
}

// ─────────────────────────────────────────────────────────────
// CROP ADVISOR SERVICE
// ─────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
@Slf4j
public class CropAdvisorService {

    private final PriceService priceService;

    private static final Map<String, List<String>> SOIL_CROPS = Map.of(
            "black",     List.of("Soybean", "Cotton", "Wheat", "Gram"),
            "red",       List.of("Maize", "Sorghum", "Groundnut", "Tomato"),
            "alluvial",  List.of("Wheat", "Paddy", "Sugarcane", "Maize"),
            "sandy",     List.of("Groundnut", "Watermelon", "Bajra"),
            "loamy",     List.of("Wheat", "Maize", "Soybean", "Onion")
    );

    private static final Map<String, List<String>> SEASON_CROPS = Map.of(
            "rabi",   List.of("Wheat", "Gram", "Mustard", "Potato"),
            "kharif", List.of("Soybean", "Cotton", "Paddy", "Maize", "Onion"),
            "zaid",   List.of("Watermelon", "Cucumber", "Fodder", "Vegetables")
    );

    public CropRecommendationResponse recommend(UUID farmerId, CropRecommendationRequest req) {
        String soilKey   = req.soilType() != null  ? req.soilType().toLowerCase().split(" ")[0]  : "loamy";
        String seasonKey = req.season() != null     ? req.season().toLowerCase().split(" ")[0]    : "rabi";

        List<String> soilCrops   = SOIL_CROPS.getOrDefault(soilKey, SOIL_CROPS.get("loamy"));
        List<String> seasonCrops = SEASON_CROPS.getOrDefault(seasonKey, SEASON_CROPS.get("rabi"));

        // Intersection: crops that suit both soil and season
        List<String> candidates = soilCrops.stream()
                .filter(c -> seasonCrops.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(c)))
                .toList();
        if (candidates.isEmpty()) candidates = seasonCrops.subList(0, Math.min(3, seasonCrops.size()));

        List<CropRecommendationResponse.CropSuggestion> suggestions = candidates.stream()
                .map(crop -> buildSuggestion(crop, req.location(), soilKey, seasonKey))
                .sorted(Comparator.comparingInt(CropRecommendationResponse.CropSuggestion::matchScore).reversed())
                .limit(4)
                .toList();

        return new CropRecommendationResponse(req.location(), req.season(), suggestions);
    }

    private CropRecommendationResponse.CropSuggestion buildSuggestion(
            String crop, String location, String soil, String season) {

        Map<String, Integer> BASE_SCORES = Map.of(
                "Wheat", 92, "Soybean", 88, "Gram", 84, "Cotton", 80,
                "Maize", 78, "Onion", 76, "Tomato", 74, "Paddy", 82);
        int score = BASE_SCORES.getOrDefault(crop, 70);

        Map<String, String> PROFIT_RANGES = Map.of(
                "Wheat", "₹18,000–₹24,000/acre", "Soybean", "₹14,000–₹20,000/acre",
                "Gram",  "₹12,000–₹18,000/acre", "Cotton",  "₹22,000–₹35,000/acre",
                "Maize", "₹10,000–₹16,000/acre", "Onion",   "₹15,000–₹30,000/acre",
                "Tomato","₹20,000–₹50,000/acre", "Paddy",   "₹12,000–₹18,000/acre");

        Map<String, Integer> GROWTH_DAYS = Map.of(
                "Wheat", 120, "Soybean", 90, "Gram", 110, "Cotton", 180,
                "Maize", 90,  "Onion",   90, "Tomato", 75, "Paddy", 120);

        Map<String, String> ICONS = Map.of(
                "Wheat", "🌾", "Soybean", "🫘", "Gram", "🟤", "Cotton", "🌸",
                "Maize", "🌽", "Onion", "🧅", "Tomato", "🍅", "Paddy", "🌾");

        return new CropRecommendationResponse.CropSuggestion(
                crop,
                ICONS.getOrDefault(crop, "🌿"),
                score,
                PROFIT_RANGES.getOrDefault(crop, "₹10,000–₹20,000/acre"),
                score >= 85 ? "Low" : score >= 75 ? "Medium" : "High",
                GROWTH_DAYS.getOrDefault(crop, 100),
                "Indore, Ujjain",
                String.format("Ideal for %s soil in %s season. Strong mandi demand.", soil, season)
        );
    }
}

// ─────────────────────────────────────────────────────────────
// RATE LIMITING FILTER (Bucket4j + Redis)
// ─────────────────────────────────────────────────────────────

package com.krishimitra.config;

import com.krishimitra.dto.ErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${krishimitra.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${krishimitra.rate-limit.burst-capacity:20}")
    private int burstCapacity;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq = (HttpServletRequest) req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        // Skip rate limiting for public endpoints
        String path = httpReq.getRequestURI();
        if (path.startsWith("/actuator") || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")) {
            chain.doFilter(req, res);
            return;
        }

        // Use IP as key (in production: use authenticated farmerId when available)
        String clientKey = httpReq.getRemoteAddr();
        String authHeader = httpReq.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            clientKey = authHeader.substring(7, Math.min(authHeader.length(), 27));
        }

        Bucket bucket = buckets.computeIfAbsent(clientKey, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(burstCapacity)
                                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                                .build())
                        .build());

        if (bucket.tryConsume(1)) {
            httpRes.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter(req, res);
        } else {
            httpRes.setStatus(429);
            httpRes.setContentType("application/json");
            httpRes.setHeader("Retry-After", "60");
            httpRes.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Rate limit exceeded. Try again in 60 seconds.\","
                    + "\"timestamp\":\"" + Instant.now() + "\"}");
        }
    }
}
