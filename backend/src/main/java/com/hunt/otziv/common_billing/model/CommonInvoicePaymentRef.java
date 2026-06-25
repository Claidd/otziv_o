package com.hunt.otziv.common_billing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "common_invoice_payment_refs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_common_invoice_payment_ref_order", columnNames = "tbank_order_id"),
                @UniqueConstraint(name = "uk_common_invoice_payment_ref_payment", columnNames = "tbank_payment_id")
        }
)
public class CommonInvoicePaymentRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_ref_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private CommonInvoice invoice;

    @Column(name = "tbank_order_id", length = 36)
    private String tbankOrderId;

    @Column(name = "tbank_payment_id", length = 64)
    private String tbankPaymentId;

    @Column(name = "tbank_terminal_key", length = 64)
    private String tbankTerminalKey;

    @Column(name = "amount_kopecks")
    private Long amountKopecks;

    @Column(nullable = false, length = 32)
    private String status = "ARCHIVED";

    @Column(name = "cancel_attempts", nullable = false)
    private Integer cancelAttempts = 0;

    @Column(length = 160)
    private String reason;

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
