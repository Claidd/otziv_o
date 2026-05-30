package com.hunt.otziv.payments.model;

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
                @Index(name = "idx_payment_links_tbank_payment_id", columnList = "tbank_payment_id")
        }
)
public class PaymentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
