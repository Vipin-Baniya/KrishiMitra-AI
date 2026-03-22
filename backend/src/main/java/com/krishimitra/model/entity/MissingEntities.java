package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// REFRESH TOKEN
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}

// ─────────────────────────────────────────────────────────────
// CHAT SESSION
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "chat_sessions")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String language = "hi";

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "last_msg_at", nullable = false)
    @Builder.Default
    private Instant lastMsgAt = Instant.now();
}

// ─────────────────────────────────────────────────────────────
// CHAT MESSAGE
// ─────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────
// PROFIT SIMULATION
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "profit_simulations")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProfitSimulation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @Column(nullable = false, length = 80)
    private String commodity;

    @Column(name = "mandi_name", nullable = false, length = 120)
    private String mandiName;

    @Column(name = "quantity_qtl", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityQtl;

    @Column(name = "wait_days", nullable = false)
    private Integer waitDays;

    @Column(name = "current_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "predicted_price", precision = 10, scale = 2)
    private BigDecimal predictedPrice;

    @Column(name = "storage_cost", precision = 10, scale = 2)
    private BigDecimal storageCost;

    @Column(name = "transport_cost", precision = 10, scale = 2)
    private BigDecimal transportCost;

    @Column(name = "gross_revenue", precision = 12, scale = 2)
    private BigDecimal grossRevenue;

    @Column(name = "net_revenue", precision = 12, scale = 2)
    private BigDecimal netRevenue;

    @Column(name = "profit_vs_now", precision = 12, scale = 2)
    private BigDecimal profitVsNow;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
