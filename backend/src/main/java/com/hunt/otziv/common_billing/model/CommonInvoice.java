package com.hunt.otziv.common_billing.model;

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
