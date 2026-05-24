package com.hunt.otziv.payments;

import com.hunt.otziv.p_products.model.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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

    @Column(nullable = false, length = 140)
    private String description;

    @Column(name = "payer_email", length = 320)
    private String payerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentLinkStatus status = PaymentLinkStatus.CREATED;

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
