package com.hunt.otziv.contractor_payments.model;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "contractor_reward_ledger")
public class ContractorRewardLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ContractorPaymentProfile profile;

    @Column(name = "source_zp_id", nullable = false)
    private Long sourceZpId;

    @Column(name = "attributed_worker_id")
    private Long attributedWorkerId;

    @Column(name = "attribution_key", nullable = false)
    private long attributionKey;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "payment_status_guard")
    private Long paymentStatusGuardId;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "work_units", nullable = false)
    private int workUnits;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "source_code", length = 64)
    private String sourceCode;

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
