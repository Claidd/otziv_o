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
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "contractor_payment_allocation_events")
public class ContractorPaymentAllocationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allocation_id", nullable = false)
    private ContractorPaymentAllocation allocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private ContractorAllocationEventType eventType;

    @Column(name = "amount_kopecks", nullable = false)
    private long amountKopecks;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", length = 32)
    private ContractorAllocationStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", length = 32)
    private ContractorAllocationStatus statusAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "routing_decision_reason", length = 64)
    private ContractorRoutingDecisionReason routingDecisionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialist_rejection_reason", length = 64)
    private ContractorRoutingDecisionReason specialistRejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "manager_rejection_reason", length = 64)
    private ContractorRoutingDecisionReason managerRejectionReason;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(length = 255)
    private String reason;

    @Column(name = "external_ref", nullable = false, length = 160)
    private String externalRef;

    @Column(nullable = false, length = 150)
    private String actor;

    @PrePersist
    void onCreate() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
        if (effectiveAt == null) {
            effectiveAt = observedAt;
        }
    }
}
