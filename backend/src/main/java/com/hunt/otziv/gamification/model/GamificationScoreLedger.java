package com.hunt.otziv.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gamification_score_ledger")
public class GamificationScoreLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 40)
    private String actorRole;

    @Column(name = "actor_name", length = 180)
    private String actorName;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "rule_points", nullable = false)
    private int rulePoints;

    @Column(name = "base_points")
    private Integer basePoints;

    @Column(name = "timeliness_multiplier")
    private BigDecimal timelinessMultiplier;

    @Column(name = "delay_days")
    private Integer delayDays;

    @Column(name = "timeliness_bucket", length = 32)
    private String timelinessBucket;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "unique_score_key", nullable = false, length = 190)
    private String uniqueScoreKey;

    @Column(name = "source_event_created_at")
    private LocalDateTime sourceEventCreatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
