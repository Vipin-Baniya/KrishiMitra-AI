package com.krishimitra.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "farmers")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Farmer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 15)
    private String phone;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 200)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(length = 120)
    private String village;

    @Column(length = 120)
    private String district;

    @Column(nullable = false, length = 80)
    @Builder.Default
    private String state = "Madhya Pradesh";

    @Column(precision = 9, scale = 6)
    private Double latitude;

    @Column(precision = 9, scale = 6)
    private Double longitude;

    @Column(name = "preferred_lang", length = 10)
    @Builder.Default
    private String preferredLang = "hi";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // ── Push notification fields (Fix #1) ─────────────────────

    /** FCM token (Android) or APNs token (iOS). Null = no push. */
    @Column(name = "push_token", length = 512)
    private String pushToken;

    /** "android" | "ios" — determines FCM vs APNs payload format. */
    @Column(name = "push_platform", length = 10)
    private String pushPlatform;

    /** Whether farmer has opted in to SMS/WhatsApp price alerts. */
    @Column(name = "sms_opt_in", nullable = false)
    @Builder.Default
    private Boolean smsOptIn = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Relationships ─────────────────────────────────────────

    @OneToMany(mappedBy = "farmer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<FarmerCrop> crops = new ArrayList<>();

    @OneToMany(mappedBy = "farmer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Alert> alerts = new ArrayList<>();
}
