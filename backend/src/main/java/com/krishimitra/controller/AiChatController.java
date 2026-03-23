package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.ChatRequest;
import com.krishimitra.dto.ChatResponse;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Conversational KrishiMitra AI — routes to custom LLM → OpenAI → Claude")
@SecurityRequirement(name = "bearerAuth")
public class AiChatController {

    private final AiChatService chatService;

    @PostMapping("/chat")
    @Operation(summary = "Chat with KrishiMitra AI",
               description = "Sends a message to the LLM with farmer context injected. "
                           + "Routes: custom model → OpenAI GPT-4o → Claude (fallback).")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody ChatRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.sendMessage(principal.farmerId(), req)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List the farmer's recent chat sessions")
    public ResponseEntity<ApiResponse<List<com.krishimitra.dto.ChatSessionResponse>>> getSessions(
            @AuthenticationPrincipal FarmerPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.getSessions(principal.farmerId())));
    }
}
