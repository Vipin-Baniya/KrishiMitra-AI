package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mandis", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "state"}))
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Mandi {
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
