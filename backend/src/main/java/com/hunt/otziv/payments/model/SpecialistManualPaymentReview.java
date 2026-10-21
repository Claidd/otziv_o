package com.hunt.otziv.payments.model;

import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "specialist_manual_payment_reviews")
@Getter
@Setter
public class SpecialistManualPaymentReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_link_id", nullable = false, unique = true)
    private Long paymentLinkId;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "recipient_profile_id", nullable = false)
    private Long recipientProfileId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "recipient_key", nullable = false, length = 160)
    private String recipientKey;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "requested_by", nullable = false, length = 150)
    private String requestedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "receipt_url", columnDefinition = "TEXT")
    private String receiptUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SpecialistManualPaymentReviewStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 150)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "resolution_kind", length = 32)
    private String resolutionKind;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
}
