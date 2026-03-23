package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "farmer_crops")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FarmerCrop {
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
