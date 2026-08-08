package com.hunt.otziv.contractor_payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Singleton, irreversible cutover state for contractor payment accounting. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "contractor_payment_accounting_phase")
public class ContractorPaymentAccountingPhase {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContractorAllocationMode phase;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 150)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public void promoteToLive(String actor, LocalDateTime now) {
        if (phase == ContractorAllocationMode.LIVE) {
            return;
        }
        if (phase != ContractorAllocationMode.SHADOW) {
            throw new IllegalStateException("Unsupported contractor accounting phase");
        }
        phase = ContractorAllocationMode.LIVE;
        updatedBy = actor;
        updatedAt = now;
    }
}
