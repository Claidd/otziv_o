package com.hunt.otziv.payments.model;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Append-only accounting evidence for one manual-payment-task operation. */
@Entity
@Getter
@Setter
@Table(
        name = "manual_payment_task_ledger_entries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_manual_task_ledger_operation_sequence",
                        columnNames = {"operation_key", "operation_sequence"}
                ),
                @UniqueConstraint(
                        name = "uk_manual_task_ledger_reservation_key",
                        columnNames = "reservation_key"
                )
        }
)
public class ManualPaymentTaskLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ManualPaymentTask task;

    @Column(name = "task_generation", nullable = false)
    private long taskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 32)
    private ManualPaymentTaskLedgerSourceKind sourceKind;

    @Column(name = "source_id", nullable = false)
    private long sourceId;

    @Column(name = "source_generation", nullable = false, length = 36)
    private String sourceGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private ManualPaymentTaskLedgerEventType eventType;

    @Column(name = "operation_key", nullable = false, length = 160)
    private String operationKey;

    @Column(name = "operation_sequence", nullable = false)
    private int operationSequence;

    /** Populated only by RESERVED rows, making a source generation single-use. */
    @Column(name = "reservation_key", length = 160)
    private String reservationKey;

    /** Signed delta of currently pending money. */
    @Column(name = "reserved_delta_kopecks", nullable = false)
    private long reservedDeltaKopecks;

    /** Signed delta of money attributed to this task. */
    @Column(name = "confirmed_delta_kopecks", nullable = false)
    private long confirmedDeltaKopecks;

    @Column(name = "redirected_amount_kopecks", nullable = false)
    private long redirectedAmountKopecks;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_target_kind", nullable = false, length = 32)
    private ManualPaymentTaskAccountingTargetKind accountingTargetKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_target_profile_id")
    private ContractorPaymentProfile accountingTargetProfile;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "accounting_target_label_snapshot", columnDefinition = "TEXT")
    private String accountingTargetLabelSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_payment_type", nullable = false, length = 32)
    private ManualPaymentType manualPaymentType;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_phone_snapshot", columnDefinition = "TEXT")
    private String manualPhoneSnapshot;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "bank_recipient_name_snapshot", columnDefinition = "TEXT")
    private String bankRecipientNameSnapshot;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_bank_name_snapshot", columnDefinition = "TEXT")
    private String manualBankNameSnapshot;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_payment_url_snapshot", columnDefinition = "TEXT")
    private String manualPaymentUrlSnapshot;

    @Column(name = "manual_payment_button_snapshot", length = 80)
    private String manualPaymentButtonSnapshot;

    @Column(name = "selected_recipient_key", length = 160)
    private String selectedRecipientKey;

    @Column(name = "target_overrun_acknowledged_at")
    private LocalDateTime targetOverrunAcknowledgedAt;

    @Column(name = "target_overrun_acknowledged_by", length = 160)
    private String targetOverrunAcknowledgedBy;

    /** False for migrated evidence whose actual recipient was never verified. */
    @Column(nullable = false)
    private boolean verified;

    @Column(name = "actor", nullable = false, length = 160)
    private String actor;

    @Column(name = "reason", length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "correction_of_id")
    private ManualPaymentTaskLedgerEntry correctionOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
