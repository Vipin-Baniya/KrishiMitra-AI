package com.krishimitra.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class LlmClient {

    private final WebClient webClient;

    public LlmClient(
            @Value("${krishimitra.llm-engine.base-url}") String baseUrl,
            @Value("${krishimitra.llm-engine.read-timeout-ms}") int readTimeout) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public LlmResponse chat(LlmRequest request) {
        log.debug("LLM chat request, lang={}", request.language());

        return webClient.post()
                .uri("/v1/chat")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new RuntimeException("LLM engine returned " + resp.statusCode())))
                .bodyToMono(LlmResponse.class)
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(1)))
                .timeout(Duration.ofSeconds(12))
                .doOnError(e -> log.error("LLM client error: {}", e.getMessage()))
                .block();
    }

    public record LlmRequest(
            List<Map<String, String>> messages,
            String language,
            Object context,
            int maxTokens,
            double temperature
    ) {}

    public record LlmResponse(
            String content,
            String modelUsed,
            Long latencyMs,
            Double confidence,
            String fallbackReason,
            Integer tokensUsed
    ) {}
}
