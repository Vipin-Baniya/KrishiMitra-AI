package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sell_recommendations")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellRecommendation {
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
