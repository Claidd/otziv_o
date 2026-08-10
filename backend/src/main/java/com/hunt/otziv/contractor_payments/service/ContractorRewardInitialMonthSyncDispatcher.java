package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Repairs profiles that were already enabled before this bridge was deployed. */
@Service
@RequiredArgsConstructor
public class ContractorRewardInitialMonthSyncDispatcher {

    private static final int BATCH_SIZE = 100;

    private final ContractorPaymentProfileRepository profileRepository;
    private final ContractorPaymentBusinessClock businessClock;
    private final ContractorRewardInitialMonthSyncCoordinator coordinator;

    @Scheduled(
            initialDelayString = "${otziv.contractor-payments.initial-month-sync-initial-delay-ms:15000}",
            fixedDelayString = "${otziv.contractor-payments.initial-month-sync-delay-ms:60000}"
    )
    public void synchronizeEnabledProfiles() {
        profileRepository.findEnabledIdsRequiringCurrentMonthSync(
                        businessClock.today().withDayOfMonth(1).atStartOfDay(),
                        PageRequest.of(0, BATCH_SIZE)
                )
                .forEach(coordinator::synchronizeSafely);
    }
}
