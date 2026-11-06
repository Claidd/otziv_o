package com.hunt.otziv.contractor_payments.service;

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
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
    private volatile boolean stopping;

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        stopping = true;
    }

    // Terminal allocations re-enter immediately when their payment source changes; the periodic
    // re-check is only a safety net, so once a day instead of re-reconciling everything hourly.
    @Value("${otziv.contractor-payments.terminal-recheck-seconds:86400}")
    private long terminalRecheckSeconds;

    @Scheduled(fixedDelayString = "${otziv.contractor-payments.reconcile-delay-ms:60000}")
    public void reconcile() {
        if (stopping) return;
        // The switch gates only new SHADOW allocations. Persisted attempts
        // remain financial obligations and must always be polled to terminality.
        try {
            dispatchMode(ContractorAllocationMode.SHADOW);
            if (!stopping) dispatchMode(ContractorAllocationMode.LIVE);
        } catch (RuntimeException failure) {
            if (!stopping) throw failure;
            // An unfinished lease expires normally; shutdown must not fabricate a result.
            log.debug("Contractor reconciliation interrupted by application shutdown", failure);
        }
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
            if (stopping) return;
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
                        failure.getClass().getSimpleName(),
                        failure
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
