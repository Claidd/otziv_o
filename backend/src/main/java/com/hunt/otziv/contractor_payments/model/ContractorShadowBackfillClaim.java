package com.hunt.otziv.contractor_payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "contractor_shadow_backfill_claims")
public class ContractorShadowBackfillClaim {

    @Id
    @Column(name = "claim_key", length = 96)
    private String claimKey;

    @Column(name = "queue_type", nullable = false, length = 32)
    private String queueType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "retry_attempts", nullable = false)
    private int retryAttempts;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error_code", length = 120)
    private String lastErrorCode;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
