package com.hunt.otziv.common_billing.model;

import com.hunt.otziv.p_products.model.Order;
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
        name = "common_invoice_orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_common_invoice_order", columnNames = "order_id")
)
public class CommonInvoiceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_order_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private CommonInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "original_order_status_title", length = 64)
    private String originalOrderStatusTitle;

    @Column(name = "archive_source_order_status_title", length = 64)
    private String archiveSourceOrderStatusTitle;

    @Column(nullable = false)
    private boolean ready;

    @Column(nullable = false)
    private boolean paid;

    @Column(nullable = false)
    private boolean unpaid;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    @Column(name = "manual_paid_by", length = 160)
    private String manualPaidBy;

    @Column(name = "manual_payment_comment", length = 1000)
    private String manualPaymentComment;

    @Column(name = "manual_payment_receipt_url", length = 1024)
    private String manualPaymentReceiptUrl;

    /**
     * Moment when this position first became a pre-publication outlier while at
     * least one sibling position had already reached publication. The value is
     * deliberately independent from the order creation/status timestamps: a
     * transition between pre-publication statuses must not restart the timer.
     */
    @Column(name = "publication_blocker_since")
    private LocalDateTime publicationBlockerSince;

    /** Timestamp of the latest attachment to the current common invoice. */
    @Column(name = "invoice_linked_at")
    private LocalDateTime invoiceLinkedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (invoiceLinkedAt == null) {
            invoiceLinkedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
