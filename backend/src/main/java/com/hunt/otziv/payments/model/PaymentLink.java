package com.hunt.otziv.payments.model;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.security.credentials.EncryptedCredentialConverter;
import jakarta.persistence.Convert;
import com.hunt.otziv.p_products.model.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "payment_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_links_token", columnNames = "token"),
                @UniqueConstraint(name = "uk_payment_links_tbank_order_id", columnNames = "tbank_order_id")
        },
        indexes = {
                @Index(name = "idx_payment_links_order", columnList = "order_id"),
                @Index(name = "idx_payment_links_tbank_payment_id", columnList = "tbank_payment_id"),
                @Index(
                        name = "idx_payment_links_bank_reconciliation_due",
                        columnList = "status, bank_reconciliation_attempted_at, updated_at, id"
                )
        }
)
public class PaymentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(nullable = false, length = 96)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "reserved_amount_kopecks")
    private Long reservedAmountKopecks;

    @Column(name = "confirmed_amount_kopecks")
    private Long confirmedAmountKopecks;

    @Column(nullable = false, length = 140)
    private String description;

    @Column(name = "payer_email", length = 320)
    private String payerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentLinkStatus status = PaymentLinkStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 32)
    private PaymentMethod paymentMethod = PaymentMethod.BANK_FORM;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_source", length = 32)
    private ManualPaymentSource manualSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_task_id")
    private ManualPaymentTask manualPaymentTask;

    @Column(name = "manual_task_source_generation", length = 36)
    private String manualTaskSourceGeneration;

    @Column(name = "manual_task_generation")
    private Long manualTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_payment_type", length = 32)
    private ManualPaymentType manualPaymentType;

    @Column(name = "tbank_payment_id", length = 64)
    private String tbankPaymentId;

    @Column(name = "tbank_order_id", length = 36)
    private String tbankOrderId;

    @Column(name = "tbank_terminal_key", length = 64)
    private String tbankTerminalKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_profile_id")
    private PaymentProfile paymentProfile;

    @Column(name = "payment_profile_code", length = 64)
    private String paymentProfileCode;

    @Column(name = "payment_profile_name", length = 120)
    private String paymentProfileName;

    /** LIVE contractor allocation whose immutable snapshots produced the
     * client-facing requisites. Null while routing remains in shadow mode. */
    @Column(name = "contractor_allocation_id")
    private Long contractorAllocationId;

    /** Immutable test-route inputs captured while this payment source is
     * prepared. The after-commit worker must never reconstruct them from the
     * mutable Order assignment. */
    @Column(name = "shadow_route_generation", length = 36)
    private String shadowRouteGeneration;

    @Column(name = "shadow_route_order_id")
    private Long shadowRouteOrderId;

    @Column(name = "shadow_route_worker_id")
    private Long shadowRouteWorkerId;

    @Column(name = "shadow_route_worker_user_id")
    private Long shadowRouteWorkerUserId;

    @Column(name = "shadow_route_manager_id")
    private Long shadowRouteManagerId;

    @Column(name = "shadow_route_manager_user_id")
    private Long shadowRouteManagerUserId;

    @Column(name = "shadow_route_amount_kopecks")
    private Long shadowRouteAmountKopecks;

    @Column(name = "shadow_route_company_routing_allowed", nullable = false)
    private boolean shadowRouteCompanyRoutingAllowed = true;

    @Column(name = "shadow_route_prepared_at")
    private LocalDateTime shadowRoutePreparedAt;

    /** Durable outbox-style relation for a separate manual-card evidence row.
     * It keeps both rows out of archive until every contractor allocation has
     * recorded the evidence event. */
    @Column(name = "contractor_evidence_original_link_id")
    private Long contractorEvidenceOriginalLinkId;

    /** Durable accounting mode fixed when a typed task route is issued and
     * reused when recipient intent is frozen before any remote bank cancel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_accounting_mode", length = 16)
    private ContractorAllocationMode manualActualAccountingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_original_cash_destination_kind", length = 32)
    private ContractorCashDestinationKind manualActualOriginalCashDestinationKind;

    @Column(name = "manual_actual_original_allocation_id")
    private Long manualActualOriginalAllocationId;

    @Column(name = "manual_actual_client_facing_allocation_id")
    private Long manualActualClientFacingAllocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_original_recipient_type", length = 24)
    private ContractorRecipientType manualActualOriginalRecipientType;

    @Column(name = "manual_actual_original_recipient_profile_id")
    private Long manualActualOriginalRecipientProfileId;

    @Column(name = "manual_actual_original_recipient_user_id")
    private Long manualActualOriginalRecipientUserId;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_actual_original_recipient_name_snapshot", columnDefinition = "TEXT")
    private String manualActualOriginalRecipientNameSnapshot;

    @Column(name = "manual_actual_original_task_id")
    private Long manualActualOriginalTaskId;

    @Column(name = "manual_actual_original_task_generation")
    private Long manualActualOriginalTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_original_task_target_kind", length = 32)
    private ManualPaymentTaskAccountingTargetKind manualActualOriginalTaskTargetKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_cash_destination_kind", length = 32)
    private ContractorCashDestinationKind manualActualCashDestinationKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_recipient_type", length = 24)
    private ContractorRecipientType manualActualRecipientType;

    @Column(name = "manual_actual_recipient_profile_id")
    private Long manualActualRecipientProfileId;

    @Column(name = "manual_actual_recipient_user_id")
    private Long manualActualRecipientUserId;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_actual_recipient_name_snapshot", columnDefinition = "TEXT")
    private String manualActualRecipientNameSnapshot;

    @Column(name = "manual_actual_task_id")
    private Long manualActualTaskId;

    @Column(name = "manual_actual_task_generation")
    private Long manualActualTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_actual_task_target_kind", length = 32)
    private ManualPaymentTaskAccountingTargetKind manualActualTaskTargetKind;

    @Column(name = "manual_actual_current_worker_id")
    private Long manualActualCurrentWorkerId;

    @Column(name = "manual_actual_current_manager_id")
    private Long manualActualCurrentManagerId;

    @Column(name = "manual_actual_reason", length = 500)
    private String manualActualReason;

    @Convert(converter = EncryptedCredentialConverter.class)
    @Column(name = "manual_actual_receipt_url", columnDefinition = "TEXT")
    private String manualActualReceiptUrl;

    @Column(name = "manual_actual_actor", length = 150)
    private String manualActualActor;

    @Column(name = "manual_actual_recipient_frozen_at")
    private LocalDateTime manualActualRecipientFrozenAt;

    @Column(name = "payment_url", length = 1024)
    private String paymentUrl;

    @Column(name = "sbp_qr_payload", length = 2048)
    private String sbpQrPayload;

    @Column(name = "sbp_qr_image", columnDefinition = "MEDIUMTEXT")
    private String sbpQrImage;

    @Column(name = "sbp_qr_data_type", length = 16)
    private String sbpQrDataType;

    @Column(name = "sbp_qr_created_at")
    private LocalDateTime sbpQrCreatedAt;

    @Column(name = "manual_phone", length = 32)
    private String manualPhone;

    @Column(name = "manual_recipient_name", length = 160)
    private String manualRecipientName;

    @Column(name = "manual_bank_name", length = 120)
    private String manualBankName;

    @Column(name = "manual_payment_url", length = 512)
    private String manualPaymentUrl;

    @Column(name = "manual_payment_button_label", length = 80)
    private String manualPaymentButtonLabel;

    @Column(name = "manual_comment", length = 255)
    private String manualComment;

    @Column(name = "manual_reported_at")
    private LocalDateTime manualReportedAt;

    @Column(name = "manual_confirmed_by", length = 160)
    private String manualConfirmedBy;

    @Column(name = "manual_confirmed_at")
    private LocalDateTime manualConfirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_status", length = 32)
    private PaymentReceiptStatus receiptStatus;

    @Column(name = "payment_success_notified_at")
    private LocalDateTime paymentSuccessNotifiedAt;

    @Column(name = "payment_success_notification_error", length = 512)
    private String paymentSuccessNotificationError;

    @Column(name = "payment_success_notification_retry_eligible", nullable = false)
    private boolean paymentSuccessNotificationRetryEligible;

    @Column(name = "last_error", length = 512)
    private String lastError;

    /** Last authoritative terminal state reported by the payment provider. */
    @Column(name = "provider_terminal_status", length = 32)
    private String providerTerminalStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "bank_reconciliation_attempted_at")
    private LocalDateTime bankReconciliationAttemptedAt;

    /**
     * Multi-instance reservation for the non-transactional T-Bank Init/GetQr
     * exchange. The nonce prevents a delayed provider response from an older
     * request (ABA) from overwriting a newer payment state.
     */
    @Column(name = "bank_init_nonce", length = 36)
    private String bankInitNonce;

    @Column(name = "bank_init_lease_until")
    private LocalDateTime bankInitLeaseUntil;

    /**
     * Durable marker for an in-flight T-Bank Cancel request. The payment is
     * kept in reconciliation quarantine until an explicit provider result is
     * applied or a later GetState observation resolves the ambiguity.
     */
    @Column(name = "bank_cancel_nonce", length = 36)
    private String bankCancelNonce;

    @Column(name = "bank_cancel_lease_until")
    private LocalDateTime bankCancelLeaseUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_cancel_origin_status", length = 32)
    private PaymentLinkStatus bankCancelOriginStatus;

    /**
     * Business error/marker that existed before the payment entered Cancel
     * quarantine. In particular, this preserves the prepayment marker while
     * an ambiguous provider outcome is reconciled.
     */
    @Column(name = "bank_cancel_origin_error", length = 512)
    private String bankCancelOriginError;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "initiated_at")
    private LocalDateTime initiatedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "offer_consent_at")
    private LocalDateTime offerConsentAt;

    @Column(name = "privacy_consent_at")
    private LocalDateTime privacyConsentAt;

    @Column(name = "receipt_consent_at")
    private LocalDateTime receiptConsentAt;

    @Column(name = "consent_ip", length = 128)
    private String consentIp;

    @Column(name = "consent_user_agent", length = 512)
    private String consentUserAgent;

    @Column(name = "offer_document_url", length = 512)
    private String offerDocumentUrl;

    @Column(name = "privacy_document_url", length = 512)
    private String privacyDocumentUrl;

    @Column(name = "receipt_consent_document_url", length = 512)
    private String receiptConsentDocumentUrl;

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
