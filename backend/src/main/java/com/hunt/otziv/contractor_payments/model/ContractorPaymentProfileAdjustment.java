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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "contractor_payment_profile_adjustments")
public class ContractorPaymentProfileAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ContractorPaymentProfile profile;

    @Column(name = "old_balance_kopecks", nullable = false)
    private long oldBalanceKopecks;

    @Column(name = "new_balance_kopecks", nullable = false)
    private long newBalanceKopecks;

    @Column(name = "delta_kopecks", nullable = false)
    private long deltaKopecks;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 160)
    private String changedBy;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (effectiveAt == null) {
            effectiveAt = now;
        }
        createdAt = now;
    }
}
