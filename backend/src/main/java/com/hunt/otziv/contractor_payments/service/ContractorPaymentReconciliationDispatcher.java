package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorPaymentReconciliationDispatcher {

    private static final int BATCH_SIZE = 250;
    private static final Set<ContractorAllocationStatus> POLL = EnumSet.of(
            ContractorAllocationStatus.RESERVED,
            ContractorAllocationStatus.CLIENT_REPORTED,
            ContractorAllocationStatus.PARTIALLY_CONFIRMED,
            ContractorAllocationStatus.OWNER_FALLBACK
    );
    private static final Set<ContractorAllocationStatus> ALL = EnumSet.allOf(ContractorAllocationStatus.class);

    private final ContractorPaymentAllocationRepository allocationRepository;
    private final ContractorPaymentReconcileClaimService claimService;
    private final ContractorPaymentShadowService shadowService;
    private final AppSettingService appSettingService;

    @Value("${otziv.contractor-payments.terminal-recheck-seconds:3600}")
    private long terminalRecheckSeconds;

    @Scheduled(fixedDelayString = "${otziv.contractor-payments.reconcile-delay-ms:60000}")
    public void reconcile() {
        // The switch gates only new SHADOW allocations. Persisted attempts
        // remain financial obligations and must always be polled to terminality.
        dispatchMode(ContractorAllocationMode.SHADOW);
        dispatchMode(ContractorAllocationMode.LIVE);
    }

    private void dispatchMode(ContractorAllocationMode mode) {
        LocalDateTime now = databaseNow();
        LocalDateTime terminalDueBefore = now.minusSeconds(Math.max(60L, terminalRecheckSeconds));
        List<ContractorPaymentAllocation> candidates = new java.util.ArrayList<>();
        candidates.addAll(allocationRepository.findPaymentLinksForReconciliation(
                mode, ALL, POLL, now, terminalDueBefore, PageRequest.of(0, BATCH_SIZE)
        ));
        candidates.addAll(allocationRepository.findCommonInvoicesForReconciliation(
                mode, ALL, POLL, now, terminalDueBefore, PageRequest.of(0, BATCH_SIZE)
        ));
        candidates.sort(java.util.Comparator.comparing(ContractorPaymentAllocation::getId));
        for (ContractorPaymentAllocation candidate : candidates) {
            Long allocationId = candidate.getId();
            // Do not give late items in a large batch a lease that already
            // elapsed while earlier sources were reconciled.
            Optional<String> token = claimService.tryClaim(allocationId, databaseNow());
            if (token.isEmpty()) {
                continue;
            }
            try {
                shadowService.reconcileAllocationId(allocationId);
                claimService.succeeded(allocationId, token.get());
            } catch (RuntimeException failure) {
                claimService.failed(allocationId, token.get(), failure, databaseNow());
                log.error(
                        "Contractor allocation quarantined for retry: allocationId={}, code={}",
                        allocationId,
                        failure.getClass().getSimpleName()
                );
            }
        }
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = allocationRepository.currentDatabaseTime();
        if (now == null) {
            throw new IllegalStateException("Contractor reconciliation database clock is unavailable");
        }
        return now;
    }
}
