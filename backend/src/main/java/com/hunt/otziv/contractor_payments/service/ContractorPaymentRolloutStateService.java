package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentRolloutStateRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Canonical, one-way accounting authority used independently from rollout gates. */
@Service
@RequiredArgsConstructor
public class ContractorPaymentRolloutStateService {

    private final ContractorPaymentRolloutStateRepository repository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Snapshot freshSnapshot() {
        return snapshot(repository.findById(ContractorPaymentRolloutState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Contractor rollout state is missing")));
    }

    @Transactional(readOnly = true)
    public Snapshot currentSnapshot() {
        return snapshot(repository.findById(ContractorPaymentRolloutState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Contractor rollout state is missing")));
    }

    /**
     * Serializes activation/pause with creation of a new LIVE route.
     * Call before the accounting-phase and contractor-profile locks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorPaymentRolloutState lockCurrent() {
        return repository.findByIdForUpdate(ContractorPaymentRolloutState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Contractor rollout state is missing"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndCheckRoutingRequested() {
        ContractorPaymentRolloutState state = lockCurrent();
        return state.completionAccountingActive()
                && state.getAttributionStartDate() != null
                && state.isRoutingRequested();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorPaymentAccountingAuthority lockAccountingAuthority() {
        return lockCurrent().getAccountingAuthority();
    }

    private Snapshot snapshot(ContractorPaymentRolloutState state) {
        return new Snapshot(
                state.getAccountingAuthority(),
                state.isRoutingRequested(),
                state.getAttributionStartDate(),
                state.getActivatedAt(),
                state.getActivatedBy(),
                state.getUpdatedAt(),
                state.getUpdatedBy(),
                state.getRowVersion()
        );
    }

    public record Snapshot(
            ContractorPaymentAccountingAuthority accountingAuthority,
            boolean routingRequested,
            LocalDate attributionStartDate,
            LocalDateTime activatedAt,
            String activatedBy,
            LocalDateTime updatedAt,
            String updatedBy,
            long revision
    ) {
        public boolean completionAccountingActive() {
            return accountingAuthority != null && accountingAuthority.paymentBased();
        }

        public boolean legacyAccountingActive() {
            return accountingAuthority == ContractorPaymentAccountingAuthority.LEGACY;
        }
    }
}
