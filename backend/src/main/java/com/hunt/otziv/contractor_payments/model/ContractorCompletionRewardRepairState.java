package com.hunt.otziv.contractor_payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable retry/backoff state so one malformed historical order cannot starve the repair tail. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "contractor_completion_reward_repair_state")
public class ContractorCompletionRewardRepairState {

    @Id
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 160)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
