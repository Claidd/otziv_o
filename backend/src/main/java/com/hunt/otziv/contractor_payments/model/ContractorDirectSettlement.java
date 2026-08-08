package com.hunt.otziv.contractor_payments.model;

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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only evidence for a direct contractor payment or its reversal.
 *
 * <p>The allocation reference is attached once during creation, after this
 * row has been flushed and its id can be used as the allocation source id.
 * There are deliberately no general setters or update endpoints.</p>
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "contractor_direct_settlements")
public class ContractorDirectSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ContractorPaymentProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 16)
    private ContractorDirectSettlementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContractorAllocationMode mode;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "evidence_reference", nullable = false, length = 160)
    private String evidenceReference;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(nullable = false, length = 150)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_settlement_id")
    private ContractorDirectSettlement originalSettlement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_id")
    private ContractorPaymentAllocation allocation;

    public static ContractorDirectSettlement payment(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            long amountKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String evidenceReference,
            String idempotencyKey,
            String actor
    ) {
        ContractorDirectSettlement row = new ContractorDirectSettlement();
        row.profile = profile;
        row.type = ContractorDirectSettlementType.PAYMENT;
        row.mode = mode;
        row.amountKopecks = amountKopecks;
        row.effectiveAt = effectiveAt;
        row.reason = reason;
        row.evidenceReference = evidenceReference;
        row.idempotencyKey = idempotencyKey;
        row.actor = actor;
        return row;
    }

    public static ContractorDirectSettlement reversal(
            ContractorDirectSettlement original,
            long amountKopecks,
            LocalDateTime effectiveAt,
            String reason,
            String evidenceReference,
            String idempotencyKey,
            String actor
    ) {
        ContractorDirectSettlement row = new ContractorDirectSettlement();
        row.profile = original.profile;
        row.type = ContractorDirectSettlementType.REVERSAL;
        row.mode = original.mode;
        row.amountKopecks = amountKopecks;
        row.effectiveAt = effectiveAt;
        row.reason = reason;
        row.evidenceReference = evidenceReference;
        row.idempotencyKey = idempotencyKey;
        row.actor = actor;
        row.originalSettlement = original;
        row.allocation = original.allocation;
        return row;
    }

    public void attachAllocation(ContractorPaymentAllocation allocation) {
        if (type != ContractorDirectSettlementType.PAYMENT
                || this.allocation != null
                || allocation == null) {
            throw new IllegalStateException("Direct settlement allocation can only be attached once");
        }
        this.allocation = allocation;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
