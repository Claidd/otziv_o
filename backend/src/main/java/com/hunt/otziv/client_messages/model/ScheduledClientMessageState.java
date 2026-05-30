package com.hunt.otziv.client_messages.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "scheduled_client_message_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_scheduled_message_scenario_target",
                columnNames = {"scenario", "target_key"}
        )
)
public class ScheduledClientMessageState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "state_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", nullable = false, length = 60)
    private ClientMessageScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 40)
    private ClientMessageTargetType targetType;

    @Column(name = "target_key", nullable = false, length = 100)
    private String targetKey;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "archive_order_id")
    private Long archiveOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_status", nullable = false, length = 30)
    private ScheduledMessageStateStatus status;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Builder.Default
    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (status == null) {
            status = ScheduledMessageStateStatus.ACTIVE;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
