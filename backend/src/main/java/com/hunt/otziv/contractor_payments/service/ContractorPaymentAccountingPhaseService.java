package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingPhase;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAccountingPhaseRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractorPaymentAccountingPhaseService {

    private final ContractorPaymentAccountingPhaseRepository repository;

    /**
     * Financial mutex shared by direct settlement and live routing.
     * Call before locking any contractor profile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorAllocationMode lockCurrent() {
        return lockState().getPhase();
    }

    /**
     * Called only by a runtime-enabled LIVE route, after its source mutex and
     * before profile mutexes. The transition is deliberately irreversible.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractorAllocationMode lockAndPromoteForLiveRoute() {
        ContractorPaymentAccountingPhase state = lockState();
        if (state.getPhase() == ContractorAllocationMode.SHADOW) {
            state.promoteToLive(currentActor(), LocalDateTime.now());
            repository.saveAndFlush(state);
        }
        return state.getPhase();
    }

    @Transactional(readOnly = true)
    public ContractorAllocationMode current() {
        return repository.findById(ContractorPaymentAccountingPhase.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Contractor accounting phase is missing"))
                .getPhase();
    }

    private ContractorPaymentAccountingPhase lockState() {
        return repository.findByIdForUpdate(ContractorPaymentAccountingPhase.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Contractor accounting phase is missing"));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "system";
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }
}
