package com.hunt.otziv.p_products.worker_access.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class WorkerAssignmentMutationGuardServiceTest {

    @Mock
    private WorkerAssignmentMutationGuardRepository repository;

    @AfterEach
    void cleanupThreadState() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void transactionalWorkerMutationLocksOwnershipUntilCommit() {
        authenticateWorker("worker");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(repository.lockOwnedReview(17L, "worker"))
                .thenReturn(Optional.of(17L));

        new WorkerAssignmentMutationGuardService(repository).assertReview(17L);

        verify(repository).lockOwnedReview(17L, "worker");
        verify(repository, never()).countOwnedReview(17L, "worker");
    }

    @Test
    void controllerPrecheckUsesNonLockingFreshOwnershipQuery() {
        authenticateWorker("worker");
        when(repository.countOwnedOrder(11L, "worker")).thenReturn(1L);

        new WorkerAssignmentMutationGuardService(repository).assertOrder(11L);

        verify(repository).countOwnedOrder(11L, "worker");
        verify(repository, never()).lockOwnedOrder(11L, "worker");
    }

    @Test
    void workerCanMutateOwnedRecoveryTaskThroughFreshOwnershipQuery() {
        authenticateWorker("worker");
        when(repository.countOwnedRecoveryTask(597L, "worker")).thenReturn(1L);

        new WorkerAssignmentMutationGuardService(repository).assertRecoveryTask(597L);

        verify(repository).countOwnedRecoveryTask(597L, "worker");
        verify(repository, never()).lockOwnedRecoveryTask(597L, "worker");
    }

    @Test
    void transactionalWorkerRecoveryMutationLocksOwnershipUntilCommit() {
        authenticateWorker("worker");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(repository.lockOwnedRecoveryTask(597L, "worker"))
                .thenReturn(Optional.of(597L));

        new WorkerAssignmentMutationGuardService(repository).assertRecoveryTask(597L);

        verify(repository).lockOwnedRecoveryTask(597L, "worker");
        verify(repository, never()).countOwnedRecoveryTask(597L, "worker");
    }

    private void authenticateWorker(String username) {
        var authentication = new TestingAuthenticationToken(
                username,
                "n/a",
                "ROLE_WORKER"
        );
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
