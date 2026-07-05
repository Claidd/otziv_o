package com.hunt.otziv.performers.model;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "performer_profiles")
public class PerformerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performer_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    private City city;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "gender", nullable = false, length = 32)
    private PerformerGender gender = PerformerGender.NOT_SPECIFIED;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private PerformerProfileStatus status = PerformerProfileStatus.NEW;

    @Builder.Default
    @Column(name = "rating", nullable = false, precision = 5, scale = 2)
    private BigDecimal rating = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "reliability_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal reliabilityScore = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "completed_count", nullable = false)
    private int completedCount = 0;

    @Builder.Default
    @Column(name = "cancelled_count", nullable = false)
    private int cancelledCount = 0;

    @Builder.Default
    @Column(name = "expired_offer_count", nullable = false)
    private int expiredOfferCount = 0;

    @Builder.Default
    @Column(name = "failed_check_count", nullable = false)
    private int failedCheckCount = 0;

    @Builder.Default
    @Column(name = "max_active_tasks", nullable = false)
    private int maxActiveTasks = 3;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "preferred_channel", nullable = false, length = 32)
    private PerformerPreferredChannel preferredChannel = PerformerPreferredChannel.TELEGRAM;

    @Column(name = "telegram_link_token", length = 128)
    private String telegramLinkToken;

    @Column(name = "telegram_linked_at")
    private LocalDateTime telegramLinkedAt;

    @Column(name = "registered_source", length = 64)
    private String registeredSource;

    @Column(name = "personal_data_accepted_at")
    private LocalDateTime personalDataAcceptedAt;

    @Column(name = "rules_accepted_at")
    private LocalDateTime rulesAcceptedAt;

    @Column(name = "honest_review_accepted_at")
    private LocalDateTime honestReviewAcceptedAt;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderated_by_user_id")
    private User moderatedBy;

    @Column(name = "block_reason", length = 1000)
    private String blockReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
