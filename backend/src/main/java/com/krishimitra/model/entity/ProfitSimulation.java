package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
