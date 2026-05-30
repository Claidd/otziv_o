package com.hunt.otziv.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
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
@Table(name = "gamification_events")
public class GamificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 40)
    private String actorRole;

    @Column(name = "actor_name", length = 180)
    private String actorName;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "bad_review_task_id")
    private Long badReviewTaskId;

    @Column(name = "recovery_task_id")
    private Long recoveryTaskId;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "company_title")
    private String companyTitle;

    @Column(name = "source", length = 120)
    private String source;

    @Column(name = "unique_event_key", length = 190)
    private String uniqueEventKey;

    @Column(name = "payload", length = 1000)
    private String payload;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "actual_date")
    private LocalDate actualDate;

    @Column(name = "delay_days")
    private Integer delayDays;

    @Column(name = "timeliness_bucket", length = 32)
    private String timelinessBucket;

    @Column(name = "timeliness_multiplier")
    private BigDecimal timelinessMultiplier;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
