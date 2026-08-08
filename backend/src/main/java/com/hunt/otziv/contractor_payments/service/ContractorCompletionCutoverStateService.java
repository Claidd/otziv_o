package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorCompletionCutoverState;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverStateRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and verifies the one-way completion-attribution boundary latch. */
@Service
@RequiredArgsConstructor
public class ContractorCompletionCutoverStateService {

    private final ContractorCompletionCutoverStateRepository repository;
    private final ContractorPaymentBusinessClock businessClock;

    @Transactional(readOnly = true)
    public Optional<LocalDate> lockedStartDate() {
        return repository.findById(ContractorCompletionCutoverState.SINGLETON_ID)
                .map(ContractorCompletionCutoverState::getAttributionStartDate);
    }

    @Transactional
    public Optional<LocalDate> lockOrVerify(LocalDate configuredStartDate) {
        if (configuredStartDate == null) {
            return Optional.empty();
        }
        // Opening balances are imported by calendar month. A mid-month or
        // future cutover would split one reporting month without a dated
        // opening-balance import and can therefore double-count obligations.
        if (configuredStartDate.getDayOfMonth() != 1
                || configuredStartDate.isAfter(businessClock.today())) {
            return Optional.empty();
        }
        Optional<ContractorCompletionCutoverState> existing =
                repository.findById(ContractorCompletionCutoverState.SINGLETON_ID);
        if (existing.isEmpty()) {
            repository.insertSingletonIfAbsent(configuredStartDate, businessClock.now());
            existing = repository.findById(ContractorCompletionCutoverState.SINGLETON_ID);
        }
        return existing
                .map(ContractorCompletionCutoverState::getAttributionStartDate)
                .filter(configuredStartDate::equals);
    }
}
