package com.hunt.otziv.b_bots.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "review_account_pool_alert_state")
public class ReviewAccountPoolAlertState {

    @Id
    @Column(name = "state_id")
    private Integer id;

    @Column(name = "last_remaining_count")
    private Integer lastRemainingCount;

    @Column(name = "notified_threshold_mask", nullable = false)
    private int notifiedThresholdMask;

    @Column(name = "cycle_number", nullable = false)
    private long cycleNumber;

    @Column(name = "last_required_count", nullable = false)
    private int lastRequiredCount;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @Column(name = "last_low_unblocked_notified_on")
    private LocalDate lastLowUnblockedNotifiedOn;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}
