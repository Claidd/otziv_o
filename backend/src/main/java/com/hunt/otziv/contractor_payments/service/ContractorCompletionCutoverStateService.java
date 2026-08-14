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
        Optional<ContractorCompletionCutoverState> existing =
                repository.findById(ContractorCompletionCutoverState.SINGLETON_ID);
        if (existing.isPresent()) {
            // A signed boundary remains valid forever. In particular, normal
            // month rollover must not disable completion accounting.
            return existing
                    .map(ContractorCompletionCutoverState::getAttributionStartDate)
                    .filter(configuredStartDate::equals);
        }
        // The opening import covers the month-to-date cabinet figures, while
        // the accounting authority changes at activation. A backdated day
        // boundary would mix already-created legacy rows with completion rows.
        // Therefore the first irreversible latch is always today's business
        // date; a persisted boundary remains valid forever afterwards.
        if (!configuredStartDate.equals(businessClock.today())) {
            return Optional.empty();
        }
        if (existing.isEmpty()) {
            repository.insertSingletonIfAbsent(configuredStartDate, businessClock.now());
            existing = repository.findById(ContractorCompletionCutoverState.SINGLETON_ID);
        }
        return existing
                .map(ContractorCompletionCutoverState::getAttributionStartDate)
                .filter(configuredStartDate::equals);
    }
}
