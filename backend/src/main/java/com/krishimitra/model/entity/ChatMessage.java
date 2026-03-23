package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Column(nullable = false, length = 15)
    private String role;           // "user" | "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "model_used", length = 40)
    private String modelUsed;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
