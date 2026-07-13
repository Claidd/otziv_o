package com.hunt.otziv.r_review.bot.model;

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
@IdClass(ReviewBotAssignmentExclusionId.class)
@Table(name = "review_bot_assignment_exclusions")
public class ReviewBotAssignmentExclusion {

    @Id
    @Column(name = "review_id", nullable = false)
    private Long reviewId;

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
