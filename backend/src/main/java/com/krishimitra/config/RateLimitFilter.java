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
