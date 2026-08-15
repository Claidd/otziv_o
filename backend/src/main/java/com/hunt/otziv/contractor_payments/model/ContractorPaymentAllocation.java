package com.hunt.otziv.contractor_payments.model;

import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "contractor_payment_allocations")
public class ContractorPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContractorAllocationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 24)
    private ContractorAllocationSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_generation_snapshot", length = 36)
    private String sourceGenerationSnapshot;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "common_invoice_id")
    private Long commonInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 24)
    private ContractorRecipientType recipientType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_profile_id")
    private ContractorPaymentProfile recipientProfile;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "current_worker_id")
    private Long currentWorkerId;

    @Column(name = "current_manager_id")
    private Long currentManagerId;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "confirmed_kopecks", nullable = false)
    private long confirmedKopecks;

    @Column(name = "returned_kopecks", nullable = false)
    private long returnedKopecks;

    @Column(name = "needs_return_amount", nullable = false)
    private boolean needsReturnAmount;

    @Column(name = "source_paid_baseline_kopecks", nullable = false)
    private long sourcePaidBaselineKopecks;

    /**
     * Cursor used by the bounded reconciliation worker. It is intentionally
     * separate from {@link #updatedAt}: advancing the polling cursor must not
     * hide a later source status change.
     */
    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @Column(name = "reconcile_claim_token", length = 36)
    private String reconcileClaimToken;

    @Column(name = "reconcile_lease_until")
    private LocalDateTime reconcileLeaseUntil;

    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(name = "reconcile_next_retry_at")
    private LocalDateTime reconcileNextRetryAt;

    @Column(name = "reconcile_last_error_code", length = 120)
    private String reconcileLastErrorCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContractorAllocationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "routing_decision_reason", length = 64)
    private ContractorRoutingDecisionReason routingDecisionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialist_rejection_reason", length = 64)
    private ContractorRoutingDecisionReason specialistRejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "manager_rejection_reason", length = 64)
    private ContractorRoutingDecisionReason managerRejectionReason;

    @Convert(converter = EncryptedCredentialConverter.class)
    // Converter ciphertext can exceed 512 characters for a valid 255-char
    // UTF-8 recipient name; TEXT avoids a strict-MySQL truncation after encryption.
    @Column(name = "recipient_name_snapshot", columnDefinition = "TEXT")
    private String recipientNameSnapshot;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "payment_phone_snapshot", length = 512)
    private String paymentPhoneSnapshot;

    @Column(name = "bank_name_snapshot", length = 120)
    private String bankNameSnapshot;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "payment_comment_snapshot", length = 2048)
    private String paymentCommentSnapshot;

    @Column(name = "available_before_kopecks")
    private Long availableBeforeKopecks;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    @Column(name = "client_reported_at")
    private LocalDateTime clientReportedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 255)
    private String releaseReason;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
