package com.hunt.otziv.contractor_payments.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ContractorRewardInitialMonthSyncCoordinatorTest {

    @Mock private ContractorRewardInitialMonthSyncService syncService;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void profileImportStartsOnlyAfterOuterProfileUpdateCommits() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        ContractorRewardInitialMonthSyncCoordinator coordinator =
                new ContractorRewardInitialMonthSyncCoordinator(syncService);

        coordinator.request(7L);
        verifyNoInteractions(syncService);

        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCommit()
        );
        verify(syncService).synchronizeProfile(7L);
    }
}
