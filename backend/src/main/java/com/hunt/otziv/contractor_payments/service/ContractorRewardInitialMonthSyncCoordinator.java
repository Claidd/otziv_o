package com.hunt.otziv.contractor_payments.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs the initial import only after the profile change is durably committed. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorRewardInitialMonthSyncCoordinator {

    private final ContractorRewardInitialMonthSyncService syncService;

    public void request(Long profileId) {
        if (profileId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronizeSafely(profileId);
                }
            });
            return;
        }
        synchronizeSafely(profileId);
    }

    public void synchronizeSafely(Long profileId) {
        try {
            syncService.synchronizeProfile(profileId);
        } catch (RuntimeException failure) {
            // The scheduled dispatcher retries while trackingStartedAt still
            // shows incomplete month coverage. Profile saving remains durable.
            log.error(
                    "Initial contractor reward month sync will be retried: profileId={}, code={}",
                    profileId,
                    failure.getClass().getSimpleName()
            );
        }
    }
}
