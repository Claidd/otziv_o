package com.hunt.otziv.contractor_payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Singleton authority for the one-way contractor-payment cutover.
 *
 * <p>The mutable application settings remain deployment gates. They are not
 * allowed to return accounting to {@link ContractorPaymentAccountingAuthority#LEGACY}
 * after the payment-accounting boundary was accepted.</p>
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "contractor_payment_rollout_state")
public class ContractorPaymentRolloutState {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_authority", nullable = false, length = 16)
    private ContractorPaymentAccountingAuthority accountingAuthority;

    @Column(name = "routing_requested", nullable = false)
    private boolean routingRequested;

    @Column(name = "attribution_start_date")
    private LocalDate attributionStartDate;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by", length = 150)
    private String activatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 150)
    private String updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean completionAccountingActive() {
        return accountingAuthority != null && accountingAuthority.paymentBased();
    }

    public void activateCompletionAccounting(LocalDate startDate, String actor, LocalDateTime now) {
        if (completionAccountingActive()) {
            if (!Objects.equals(attributionStartDate, startDate)) {
                throw new IllegalStateException("Contractor completion boundary is immutable");
            }
            return;
        }
        if (accountingAuthority != ContractorPaymentAccountingAuthority.LEGACY
                || startDate == null
                || actor == null
                || actor.isBlank()
                || now == null) {
            throw new IllegalStateException("Invalid contractor accounting activation");
        }
        accountingAuthority = ContractorPaymentAccountingAuthority.PAYMENT;
        routingRequested = false;
        attributionStartDate = startDate;
        activatedAt = now;
        activatedBy = actor;
        updatedAt = now;
        updatedBy = actor;
    }

    public void updateRoutingRequested(boolean requested, String actor, LocalDateTime now) {
        if (!completionAccountingActive() || attributionStartDate == null) {
            throw new IllegalStateException("Contractor completion accounting is not active");
        }
        if (routingRequested == requested) {
            return;
        }
        routingRequested = requested;
        updatedAt = now;
        updatedBy = actor;
    }
}
