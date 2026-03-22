package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// MANDI
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "mandis", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "state"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Mandi {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 120) private String district;
    @Column(nullable = false, length = 80)  private String state;
    @Column(precision = 9, scale = 6)       private Double latitude;
    @Column(precision = 9, scale = 6)       private Double longitude;
    @Column(name = "apmc_code", length = 20) private String apmcCode;
    @Builder.Default @Column(name = "is_active") private Boolean isActive = true;
    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
}

// ─────────────────────────────────────────────────────────────
// MANDI PRICE  (high-volume time-series)
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "mandi_prices",
       uniqueConstraints = @UniqueConstraint(columnNames = {"mandi_id","commodity","variety","price_date"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class MandiPrice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mandi_id", nullable = false)
    private Mandi mandi;

    @Column(nullable = false, length = 80) private String commodity;
    @Column(nullable = false, length = 80) @Builder.Default private String variety = "Common";
    @Column(name = "price_date", nullable = false) private LocalDate priceDate;

    @Column(name = "min_price",   nullable = false, precision = 10, scale = 2) private BigDecimal minPrice;
    @Column(name = "max_price",   nullable = false, precision = 10, scale = 2) private BigDecimal maxPrice;
    @Column(name = "modal_price", nullable = false, precision = 10, scale = 2) private BigDecimal modalPrice;
    @Column(name = "arrivals_qtl", precision = 12, scale = 2)                  private BigDecimal arrivalsQtl;
    @Column(nullable = false, length = 40) @Builder.Default private String source = "agmarknet";

    /** Previous day modal price — populated by ingestion scheduler for trend calculation. */
    @Column(name = "prev_modal_price", precision = 10, scale = 2)
    private BigDecimal prevModalPrice;

    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
}

// ─────────────────────────────────────────────────────────────
// FARMER CROP
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "farmer_crops")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FarmerCrop {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @Column(nullable = false, length = 80) private String commodity;
    @Column(length = 80)                   private String variety;
    @Column(name = "quantity_quintal", precision = 10, scale = 2) private BigDecimal quantityQuintal;
    @Column(name = "planted_date")         private LocalDate plantedDate;
    @Column(name = "expected_harvest")     private LocalDate expectedHarvest;
    @Column(name = "storage_available") @Builder.Default private Boolean storageAvailable = false;
    @Column(columnDefinition = "TEXT")     private String notes;

    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at")           private Instant updatedAt;
}

// ─────────────────────────────────────────────────────────────
// PRICE PREDICTION
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "price_predictions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"mandi_id","commodity"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PricePrediction {
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

// ─────────────────────────────────────────────────────────────
// ALERT
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "alerts")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Alert {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id")   // nullable — broadcast alert when null
    private Farmer farmer;

    @Column(nullable = false, length = 40) private String type;
    @Column(nullable = false, length = 10) private String severity;
    @Column(length = 80)  private String commodity;
    @Column(name = "mandi_name", length = 120) private String mandiName;
    @Column(nullable = false, length = 255)    private String title;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(columnDefinition = "jsonb") private String metadata;
    @Column(name = "is_read") @Builder.Default private Boolean isRead = false;
    @Column(name = "expires_at") private Instant expiresAt;

    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
}

// ─────────────────────────────────────────────────────────────
// SELL RECOMMENDATION
// ─────────────────────────────────────────────────────────────
@Entity
@Table(name = "sell_recommendations")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class SellRecommendation {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farmer_crop_id")
    private FarmerCrop farmerCrop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_mandi_id")
    private Mandi recommendedMandi;

    @Column(nullable = false, length = 80) private String commodity;
    @Column(nullable = false, length = 20) private String decision;
    @Column(name = "wait_days") @Builder.Default private Integer waitDays = 0;
    @Column(name = "current_price",         precision = 10, scale = 2) private BigDecimal currentPrice;
    @Column(name = "expected_price",        precision = 10, scale = 2) private BigDecimal expectedPrice;
    @Column(name = "profit_gain_per_qtl",   precision = 10, scale = 2) private BigDecimal profitGainPerQtl;
    @Column(name = "storage_cost",          precision = 10, scale = 2) private BigDecimal storageCost;
    @Column(name = "transport_cost",        precision = 10, scale = 2) private BigDecimal transportCost;
    @Column(name = "net_gain",              precision = 10, scale = 2) private BigDecimal netGain;
    @Column(columnDefinition = "TEXT") private String reasoning;
    @Column(name = "is_acted_on") @Builder.Default private Boolean isActedOn = false;

    @CreatedDate @Column(name = "created_at", updatable = false) private Instant createdAt;
}
