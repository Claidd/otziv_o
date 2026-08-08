package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorRewardLedgerRepairDispatcher {

    private static final int BATCH_SIZE = 250;
    private final ZpRepository zpRepository;
    private final ContractorRewardRepairClaimService claimService;
    private final ContractorRewardLedgerService ledgerService;

    @Scheduled(fixedDelayString = "${otziv.contractor-payments.reward-ledger-reconcile-delay-ms:60000}")
    public void reconcileMissingLedgerEntries() {
        LocalDateTime now = LocalDateTime.now();
        List<Zp> candidates = zpRepository.findContractorRewardsNeedingGlobalRepair(
                now,
                PageRequest.of(0, BATCH_SIZE)
        );
        for (Zp candidate : candidates) {
            Long sourceId = candidate.getId();
            Optional<String> token = claimService.tryClaim(sourceId, LocalDateTime.now());
            if (token.isEmpty()) {
                continue;
            }
            try {
                ledgerService.synchronizeSourceId(sourceId);
                claimService.succeeded(sourceId, token.get());
            } catch (RuntimeException failure) {
                claimService.failed(sourceId, token.get(), failure, LocalDateTime.now());
                log.error(
                        "Contractor reward ledger source quarantined for retry: sourceZpId={}, code={}",
                        sourceId,
                        failure.getClass().getSimpleName()
                );
            }
        }
    }
}
