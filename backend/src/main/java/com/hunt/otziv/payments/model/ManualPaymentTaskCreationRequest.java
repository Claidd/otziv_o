package com.hunt.otziv.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable exactly-once claim for creating one manual payment task. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "manual_payment_task_creation_requests")
public class ManualPaymentTaskCreationRequest {

    @Id
    @Column(name = "operation_key", length = 160, nullable = false)
    private String operationKey;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
