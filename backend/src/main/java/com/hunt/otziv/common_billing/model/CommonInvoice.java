package com.hunt.otziv.common_billing.model;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "common_invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_common_invoices_token", columnNames = "token"),
                @UniqueConstraint(name = "uk_common_invoices_tbank_order_id", columnNames = "tbank_order_id")
        }
)
public class CommonInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private CommonBillingAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_invoice_id")
    private CommonInvoice supersedesInvoice;

    @Column(name = "invoice_purpose", nullable = false, length = 32)
    private String invoicePurpose = "STANDARD";

    /** Immutable route policy captured when the invoice cycle is created. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_payment_mode", nullable = false, length = 32)
    private InvoicePaymentMode invoicePaymentMode = InvoicePaymentMode.AUTO_ROUTING;

    @Column(name = "paper_invoice_issued_at")
    private LocalDateTime paperInvoiceIssuedAt;

    @Column(name = "cycle_idempotency_key", length = 160)
    private String cycleIdempotencyKey;

    @Column(nullable = false, length = 96)
    private String token;

    @Column(nullable = false, length = 180)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CommonInvoiceStatus status = CommonInvoiceStatus.COLLECTING;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "paid_kopecks", nullable = false)
    private long paidKopecks;

    @Column(name = "tbank_order_id", length = 36)
    private String tbankOrderId;

    @Column(name = "tbank_payment_id", length = 64)
    private String tbankPaymentId;

    @Column(name = "tbank_terminal_key", length = 64)
    private String tbankTerminalKey;

    @Column(name = "tbank_payment_amount_kopecks")
    private Long tbankPaymentAmountKopecks;

    @Column(name = "tbank_payment_created_at")
    private LocalDateTime tbankPaymentCreatedAt;

    @Column(name = "payment_url", length = 1024)
    private String paymentUrl;

    @Column(name = "payer_email", length = 320)
    private String payerEmail;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "last_reminder_at")
    private LocalDateTime lastReminderAt;

    @Column(name = "next_reminder_at")
    private LocalDateTime nextReminderAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 160)
    private String closedBy;

    @Column(name = "close_reason", length = 32)
    private String closeReason;

    @Column(name = "previous_status", length = 32)
    private String previousStatus;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    @Column(name = "payment_route_type", length = 32)
    private String paymentRouteType;

    @Column(name = "payment_route_profile_id")
    private Long paymentRouteProfileId;

    @Column(name = "payment_route_profile_code", length = 64)
    private String paymentRouteProfileCode;

    @Column(name = "payment_route_profile_name", length = 120)
    private String paymentRouteProfileName;

    /** LIVE contractor allocation frozen together with the common route. */
    @Column(name = "contractor_allocation_id")
    private Long contractorAllocationId;

    /** Immutable inputs for the asynchronous test route. They are captured in
     * the same transaction that freezes the public invoice route. */
    @Column(name = "shadow_route_generation", length = 36)
    private String shadowRouteGeneration;

    @Column(name = "shadow_route_worker_state", length = 24)
    private String shadowRouteWorkerState;

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

    @Column(name = "shadow_route_membership_hash", length = 64)
    private String shadowRouteMembershipHash;

    @Column(name = "shadow_route_contractor_eligible", nullable = false)
    private boolean shadowRouteContractorEligible;

    @Column(name = "shadow_route_company_routing_allowed", nullable = false)
    private boolean shadowRouteCompanyRoutingAllowed = true;

    @Column(name = "shadow_route_prepared_at")
    private LocalDateTime shadowRoutePreparedAt;

    @Column(name = "payment_route_terminal_key", length = 64)
    private String paymentRouteTerminalKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_route_manual_source", length = 32)
    private ManualPaymentSource paymentRouteManualSource;

    @Column(name = "payment_route_manual_task_id")
    private Long paymentRouteManualTaskId;

    @Column(name = "payment_route_manual_task_source_generation", length = 36)
    private String paymentRouteManualTaskSourceGeneration;

    @Column(name = "payment_route_manual_task_generation")
    private Long paymentRouteManualTaskGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_route_manual_task_accounting_mode", length = 16)
    private ContractorAllocationMode paymentRouteManualTaskAccountingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_route_manual_type", length = 32)
    private ManualPaymentType paymentRouteManualType;

    @Column(name = "payment_route_manual_phone", length = 32)
    private String paymentRouteManualPhone;

    @Column(name = "payment_route_manual_recipient", length = 160)
    private String paymentRouteManualRecipient;

    @Column(name = "payment_route_manual_bank_name", length = 120)
    private String paymentRouteManualBankName;

    @Column(name = "payment_route_manual_url", length = 512)
    private String paymentRouteManualUrl;

    @Column(name = "payment_route_manual_button", length = 80)
    private String paymentRouteManualButton;

    @Column(name = "payment_route_manual_comment", length = 255)
    private String paymentRouteManualComment;

    @Column(name = "payment_route_instruction_text", length = 1000)
    private String paymentRouteInstructionText;

    @Column(name = "payment_route_amount_kopecks")
    private Long paymentRouteAmountKopecks;

    @Column(name = "payment_route_selected_at")
    private LocalDateTime paymentRouteSelectedAt;

    /** Client statement only; this is never treated as confirmed receipt. */
    @Column(name = "client_reported_at")
    private LocalDateTime clientReportedAt;

    @Column(name = "manual_paid_by", length = 160)
    private String manualPaidBy;

    @Column(name = "manual_payment_comment", length = 1000)
    private String manualPaymentComment;

    @Column(name = "manual_payment_receipt_url", length = 1024)
    private String manualPaymentReceiptUrl;

    @Column(name = "manual_confirmed_at")
    private LocalDateTime manualConfirmedAt;

    @Column(name = "payment_success_notified_at")
    private LocalDateTime paymentSuccessNotifiedAt;

    @Column(name = "payment_success_notification_error", length = 512)
    private String paymentSuccessNotificationError;

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
