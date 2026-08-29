package com.hunt.otziv.payments.model;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "owner_manual_card_payment_approvals")
public class OwnerManualCardPaymentApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_link_id", nullable = false, unique = true)
    private Long paymentLinkId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "recipient_key", nullable = false, length = 180)
    private String recipientKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 24)
    private ContractorRecipientType recipientType;

    @Column(name = "recipient_profile_id")
    private Long recipientProfileId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "receipt_url", columnDefinition = "TEXT")
    private String receiptUrl;

    @Column(name = "requested_by", nullable = false, length = 150)
    private String requestedBy;

    @Column(name = "callback_token_hash", nullable = false, length = 64)
    private String callbackTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OwnerManualCardPaymentApprovalStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "approved_by", length = 150)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        requestedAt = requestedAt == null ? now : requestedAt;
        status = status == null ? OwnerManualCardPaymentApprovalStatus.PENDING : status;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
