package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "mandi_prices",
       uniqueConstraints = @UniqueConstraint(columnNames = {"mandi_id","commodity","variety","price_date"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MandiPrice {
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
