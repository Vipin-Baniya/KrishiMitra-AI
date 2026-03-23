package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerts")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Alert {
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
