package com.hunt.otziv.review_recovery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@IdClass(ReviewRecoveryBotExclusionId.class)
@Table(name = "review_recovery_bot_exclusions")
public class ReviewRecoveryBotExclusion {

    @Id
    @Column(name = "review_recovery_task_id", nullable = false)
    private Long taskId;

    @Id
    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
