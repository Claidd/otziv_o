package com.hunt.otziv.client_messages.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "scheduled_client_message_attempts")
public class ScheduledClientMessageAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_id")
    private Long id;

    @Column(name = "state_id")
    private Long stateId;

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
    @Column(name = "attempt_status", nullable = false, length = 30)
    private ScheduledMessageAttemptStatus status;

    @Column(name = "channel", length = 40)
    private String channel;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "message_preview", length = 500)
    private String messagePreview;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
