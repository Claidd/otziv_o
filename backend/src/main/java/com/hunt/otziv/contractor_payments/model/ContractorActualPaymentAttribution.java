package com.hunt.otziv.contractor_payments.model;

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
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Immutable evidence of who actually received a manually confirmed payment. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contractor_actual_payment_attributions")
public class ContractorActualPaymentAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attribution_key", nullable = false, length = 160, updatable = false)
    private String attributionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 32, updatable = false)
    private ContractorActualPaymentSourceKind sourceKind;

    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    @Column(name = "evidence_id", updatable = false)
    private Long evidenceId;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "common_invoice_id", updatable = false)
    private Long commonInvoiceId;

    @Column(name = "original_allocation_id", updatable = false)
    private Long originalAllocationId;

    @Column(name = "client_facing_allocation_id", updatable = false)
    private Long clientFacingAllocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_mode", nullable = false, length = 16, updatable = false)
    private ContractorAllocationMode accountingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_cash_destination_kind", nullable = false, length = 32, updatable = false)
    private ContractorCashDestinationKind originalCashDestinationKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_recipient_type", length = 24, updatable = false)
    private ContractorRecipientType originalRecipientType;

    @Column(name = "original_recipient_profile_id", updatable = false)
    private Long originalRecipientProfileId;

    @Column(name = "original_recipient_user_id", updatable = false)
    private Long originalRecipientUserId;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "original_recipient_name_snapshot", columnDefinition = "TEXT", updatable = false)
    private String originalRecipientNameSnapshot;

    @Column(name = "original_manual_payment_task_id", updatable = false)
    private Long originalManualPaymentTaskId;

    @Column(name = "original_manual_payment_task_generation", updatable = false)
    private Long originalManualPaymentTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_manual_payment_task_target_kind", length = 32, updatable = false)
    private ManualPaymentTaskAccountingTargetKind originalManualPaymentTaskTargetKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_cash_destination_kind", nullable = false, length = 32, updatable = false)
    private ContractorCashDestinationKind actualCashDestinationKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_recipient_type", length = 24, updatable = false)
    private ContractorRecipientType actualRecipientType;

    @Column(name = "actual_recipient_profile_id", updatable = false)
    private Long actualRecipientProfileId;

    @Column(name = "actual_recipient_user_id", updatable = false)
    private Long actualRecipientUserId;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "actual_recipient_name_snapshot", columnDefinition = "TEXT", updatable = false)
    private String actualRecipientNameSnapshot;

    @Column(name = "actual_manual_payment_task_id", updatable = false)
    private Long actualManualPaymentTaskId;

    @Column(name = "actual_manual_payment_task_generation", updatable = false)
    private Long actualManualPaymentTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_manual_payment_task_target_kind", length = 32, updatable = false)
    private ManualPaymentTaskAccountingTargetKind actualManualPaymentTaskTargetKind;

    @Column(name = "current_worker_id", updatable = false)
    private Long currentWorkerId;

    @Column(name = "current_manager_id", updatable = false)
    private Long currentManagerId;

    @Column(name = "amount_kopecks", nullable = false, updatable = false)
    private long amountKopecks;

    @Column(name = "available_before_kopecks", updatable = false)
    private Long availableBeforeKopecks;

    @Column(name = "projected_overrun_kopecks", nullable = false, updatable = false)
    private long projectedOverrunKopecks;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private LocalDateTime effectiveAt;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "evidence_reference", nullable = false, length = 160, updatable = false)
    private String evidenceReference;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "receipt_url", columnDefinition = "TEXT", updatable = false)
    private String receiptUrl;

    @Column(nullable = false, length = 150, updatable = false)
    private String actor;

    @Column(name = "correction_of_id", updatable = false)
    private Long correctionOfId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ContractorActualPaymentAttribution create(
            String attributionKey,
            ContractorActualPaymentSourceKind sourceKind,
            Long sourceId,
            Long evidenceId,
            Long orderId,
            Long commonInvoiceId,
            Long originalAllocationId,
            Long clientFacingAllocationId,
            ContractorAllocationMode accountingMode,
            ContractorRecipientType originalRecipientType,
            Long originalRecipientProfileId,
            Long originalRecipientUserId,
            String originalRecipientNameSnapshot,
            ContractorRecipientType actualRecipientType,
            Long actualRecipientProfileId,
            Long actualRecipientUserId,
            String actualRecipientNameSnapshot,
            Long currentWorkerId,
            Long currentManagerId,
            long amountKopecks,
            Long availableBeforeKopecks,
            long projectedOverrunKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String evidenceReference,
            String receiptUrl,
            String actor,
            Long correctionOfId
    ) {
        ContractorActualPaymentAttribution row = new ContractorActualPaymentAttribution();
        row.attributionKey = attributionKey;
        row.sourceKind = sourceKind;
        row.sourceId = sourceId;
        row.evidenceId = evidenceId;
        row.orderId = orderId;
        row.commonInvoiceId = commonInvoiceId;
        row.originalAllocationId = originalAllocationId;
        row.clientFacingAllocationId = clientFacingAllocationId;
        row.accountingMode = accountingMode;
        row.originalCashDestinationKind = originalRecipientType == ContractorRecipientType.OWNER
                ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE;
        row.originalRecipientType = originalRecipientType;
        row.originalRecipientProfileId = originalRecipientProfileId;
        row.originalRecipientUserId = originalRecipientUserId;
        row.originalRecipientNameSnapshot = originalRecipientNameSnapshot;
        row.actualRecipientType = actualRecipientType;
        row.actualRecipientProfileId = actualRecipientProfileId;
        row.actualRecipientUserId = actualRecipientUserId;
        row.actualRecipientNameSnapshot = actualRecipientNameSnapshot;
        row.actualCashDestinationKind = actualRecipientType == ContractorRecipientType.OWNER
                ? ContractorCashDestinationKind.OWNER : ContractorCashDestinationKind.CONTRACTOR_PROFILE;
        row.currentWorkerId = currentWorkerId;
        row.currentManagerId = currentManagerId;
        row.amountKopecks = amountKopecks;
        row.availableBeforeKopecks = availableBeforeKopecks;
        row.projectedOverrunKopecks = projectedOverrunKopecks;
        row.effectiveAt = effectiveAt;
        row.reason = reason;
        row.evidenceReference = evidenceReference;
        row.receiptUrl = receiptUrl;
        row.actor = actor;
        row.correctionOfId = correctionOfId;
        row.createdAt = LocalDateTime.now();
        return row;
    }

    public static ContractorActualPaymentAttribution createWithDestinations(
            String attributionKey, ContractorActualPaymentSourceKind sourceKind, Long sourceId,
            Long evidenceId, Long orderId, Long commonInvoiceId, Long originalAllocationId,
            Long clientFacingAllocationId, ContractorAllocationMode accountingMode,
            ContractorRecipientType originalRecipientType, Long originalRecipientProfileId,
            Long originalRecipientUserId, String originalRecipientNameSnapshot,
            ContractorRecipientType actualRecipientType, Long actualRecipientProfileId,
            Long actualRecipientUserId, String actualRecipientNameSnapshot,
            Long currentWorkerId, Long currentManagerId, long amountKopecks,
            Long availableBeforeKopecks, long projectedOverrunKopecks, LocalDateTime effectiveAt,
            String reason, String evidenceReference, String receiptUrl, String actor, Long correctionOfId,
            ContractorCashDestinationKind originalCashDestinationKind,
            Long originalTaskId, Long originalTaskGeneration,
            ManualPaymentTaskAccountingTargetKind originalTaskTargetKind,
            ContractorCashDestinationKind actualCashDestinationKind,
            Long actualTaskId, Long actualTaskGeneration,
            ManualPaymentTaskAccountingTargetKind actualTaskTargetKind
    ) {
        ContractorActualPaymentAttribution row = create(
                attributionKey, sourceKind, sourceId, evidenceId, orderId, commonInvoiceId,
                originalAllocationId, clientFacingAllocationId, accountingMode,
                originalRecipientType, originalRecipientProfileId, originalRecipientUserId,
                originalRecipientNameSnapshot, actualRecipientType, actualRecipientProfileId,
                actualRecipientUserId, actualRecipientNameSnapshot, currentWorkerId, currentManagerId,
                amountKopecks, availableBeforeKopecks, projectedOverrunKopecks, effectiveAt,
                reason, evidenceReference, receiptUrl, actor, correctionOfId
        );
        row.originalCashDestinationKind = originalCashDestinationKind;
        row.originalManualPaymentTaskId = originalTaskId;
        row.originalManualPaymentTaskGeneration = originalTaskGeneration;
        row.originalManualPaymentTaskTargetKind = originalTaskTargetKind;
        row.actualCashDestinationKind = actualCashDestinationKind;
        row.actualManualPaymentTaskId = actualTaskId;
        row.actualManualPaymentTaskGeneration = actualTaskGeneration;
        row.actualManualPaymentTaskTargetKind = actualTaskTargetKind;
        return row;
    }
}
