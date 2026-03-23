package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_predictions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"mandi_id","commodity"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PricePrediction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mandi_id", nullable = false)
    private Mandi mandi;

    @Column(nullable = false, length = 80) private String commodity;
    @Column(name = "generated_at", nullable = false) @Builder.Default private Instant generatedAt = Instant.now();
    @Column(name = "current_price", nullable = false, precision = 10, scale = 2) private BigDecimal currentPrice;

    @Column(name = "point_forecast", columnDefinition = "jsonb") private String pointForecast;
    @Column(name = "lower_80",       columnDefinition = "jsonb") private String lower80;
    @Column(name = "upper_80",       columnDefinition = "jsonb") private String upper80;
    @Column(name = "lower_95",       columnDefinition = "jsonb") private String lower95;
    @Column(name = "upper_95",       columnDefinition = "jsonb") private String upper95;

    @Column(name = "sell_decision", nullable = false, length = 20) private String sellDecision;
    @Column(name = "wait_days") @Builder.Default private Integer waitDays = 0;
    @Column(name = "peak_day")   private Integer peakDay;
    @Column(name = "peak_price", precision = 10, scale = 2) private BigDecimal peakPrice;
    @Column(name = "profit_gain", precision = 10, scale = 2) private BigDecimal profitGain;
    @Column(precision = 5, scale = 4) private BigDecimal confidence;
    @Column(name = "model_weights",  columnDefinition = "jsonb") private String modelWeights;
    @Column(columnDefinition = "jsonb") private String explanation;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
}
