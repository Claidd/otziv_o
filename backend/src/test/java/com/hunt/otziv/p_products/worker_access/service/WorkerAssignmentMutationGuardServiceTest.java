package com.hunt.otziv.p_products.worker_access.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkerAssignmentMutationGuardServiceTest {

    @Mock
    private WorkerAssignmentMutationGuardRepository repository;

    @Mock
    private ManagerAccessService managerAccessService;

    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @AfterEach
    void cleanupThreadState() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void transactionalWorkerMutationLocksOwnershipUntilCommit() {
        authenticateWorker("worker");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(repository.findOrderIdByReviewId(17L)).thenReturn(Optional.of(11L));
        when(repository.findCurrentOrderIdByReviewId(17L)).thenReturn(Optional.of(11L));
        when(repository.lockOwnedReview(17L, "worker")).thenReturn(Optional.of(17L));

        service().assertReview(17L);

        var ordered = org.mockito.Mockito.inOrder(repository, orderAggregateMutationLockService);
        ordered.verify(repository).findOrderIdByReviewId(17L);
        ordered.verify(orderAggregateMutationLockService).lock(11L);
        ordered.verify(repository).findCurrentOrderIdByReviewId(17L);
        ordered.verify(repository).lockOwnedReview(17L, "worker");
        verify(repository, never()).countOwnedReview(17L, "worker");
    }

    @Test
    void explicitWorkerActorDoesNotInheritAmbientAdministratorBypass() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","unused","ROLE_ADMIN"));
        var worker=new TestingAuthenticationToken("other-worker","unused","ROLE_WORKER");
        when(repository.countOwnedOrder(11L,"other-worker")).thenReturn(0L);
        assertThatThrownBy(() -> service().assertOrder(11L,worker)).isInstanceOf(ResponseStatusException.class);
        verify(repository).countOwnedOrder(11L,"other-worker");
    }

    @Test
    void explicitAnonymousActorIsDeniedEvenWhenLegacySystemEntryPointAllowsNoPrincipal() {
        assertThatThrownBy(() -> service().assertReview(11L,null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository,managerAccessService,orderAggregateMutationLockService);
    }

    @Test
    void controllerPrecheckUsesNonLockingFreshOwnershipQuery() {
        authenticateWorker("worker");
        when(repository.countOwnedOrder(11L, "worker")).thenReturn(1L);

        service().assertOrder(11L);

        verify(repository).countOwnedOrder(11L, "worker");
        verify(repository, never()).lockOwnedOrder(11L, "worker");
    }

    @Test
    void workerCanMutateOwnedRecoveryTaskThroughFreshOwnershipQuery() {
        authenticateWorker("worker");
        when(repository.countOwnedRecoveryTask(597L, "worker")).thenReturn(1L);

        service().assertRecoveryTask(597L);

        verify(repository).countOwnedRecoveryTask(597L, "worker");
        verify(repository, never()).lockOwnedRecoveryTask(597L, "worker");
    }

    @Test
    void transactionalWorkerRecoveryMutationLocksOwnershipUntilCommit() {
        authenticateWorker("worker");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(repository.findOrderIdByRecoveryTaskId(597L)).thenReturn(Optional.of(11L));
        when(repository.findCurrentOrderIdByRecoveryTaskId(597L)).thenReturn(Optional.of(11L));
        when(repository.lockOwnedRecoveryTask(597L, "worker")).thenReturn(Optional.of(597L));

        service().assertRecoveryTask(597L);

        var ordered = org.mockito.Mockito.inOrder(repository, orderAggregateMutationLockService);
        ordered.verify(repository).findOrderIdByRecoveryTaskId(597L);
        ordered.verify(orderAggregateMutationLockService).lock(11L);
        ordered.verify(repository).findCurrentOrderIdByRecoveryTaskId(597L);
        ordered.verify(repository).lockOwnedRecoveryTask(597L, "worker");
        verify(repository, never()).countOwnedRecoveryTask(597L, "worker");
    }

    @Test
    void rejectedMutationDoesNotClaimThatATransferDefinitelyHappened() {
        authenticateWorker("worker");

        assertThatThrownBy(() ->
                service().assertReview(17L)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("назначение или состояние объекта изменилось")
                .hasMessageNotContaining("передана другому специалисту");
    }

    @Test
    void adminAndNonStaffRolesAreNotSubjectToAssignmentScopeGuard() {
        WorkerAssignmentMutationGuardService service = service();

        for (String role : new String[]{"ROLE_CLIENT", "ROLE_ADMIN"}) {
            authenticate("actor", role);
            service.assertReview(17L);
            service.assertOrder(11L);
        }

        verifyNoInteractions(repository);
    }

    @Test
    void managerReviewMutationUsesCanonicalOrderScope() {
        var authentication = authenticate("manager", "ROLE_MANAGER");
        when(repository.findOrderIdByReviewId(17L)).thenReturn(Optional.of(11L));
        when(managerAccessService.canAccessOrder(11L, authentication)).thenReturn(true);

        service().assertReview(17L);

        verify(repository).findOrderIdByReviewId(17L);
        verify(managerAccessService).canAccessOrder(11L, authentication);
        verify(repository, never()).countOwnedReview(17L, "manager");
    }

    @Test
    void ownerCannotMutateTaskOutsideCanonicalOrderScope() {
        var authentication = authenticate("owner", "ROLE_OWNER");
        when(repository.findOrderIdByBadTaskId(597L)).thenReturn(Optional.of(11L));
        when(managerAccessService.canAccessOrder(11L, authentication)).thenReturn(false);

        assertThatThrownBy(() -> service().assertBadTask(597L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void archivedRecoveryTaskCanUseItsCanonicalManagerScope() {
        var authentication = authenticate("manager", "ROLE_MANAGER");
        when(repository.findOrderIdByRecoveryTaskId(597L)).thenReturn(Optional.empty());
        when(repository.findManagerIdByRecoveryTaskId(597L)).thenReturn(Optional.of(9L));
        when(managerAccessService.canAccessManager(9L, authentication)).thenReturn(true);

        service().assertRecoveryTask(597L);

        verify(managerAccessService).canAccessManager(9L, authentication);
    }

    @Test
    void staleRecoveryManagerSnapshotCannotOverrideLiveOrderScope() {
        var authentication = authenticate("old-manager", "ROLE_MANAGER");
        when(repository.findOrderIdByRecoveryTaskId(597L)).thenReturn(Optional.of(11L));
        when(managerAccessService.canAccessOrder(11L, authentication)).thenReturn(false);

        assertThatThrownBy(() -> service().assertRecoveryTask(597L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");

        verify(repository, never()).findManagerIdByRecoveryTaskId(597L);
    }

    @Test
    void managerialOrderMutationDelegatesToOrderScopeService() {
        var authentication = authenticate("manager", "ROLE_MANAGER");

        service().assertOrder(11L);

        verify(managerAccessService).requireOrderAccess(11L, authentication);
        verifyNoInteractions(repository);
    }

    @Test
    void transactionalManagerLocksOrderBeforeObjectScopeRecheck() {
        var authentication = authenticate("manager", "ROLE_MANAGER");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        when(repository.findCurrentManagerIdByOrderId(11L)).thenReturn(Optional.of(9L));
        when(managerAccessService.canAccessCurrentOrderManager(9L, authentication)).thenReturn(true);
        service().assertOrder(11L);

        var ordered = org.mockito.Mockito.inOrder(orderAggregateMutationLockService, repository, managerAccessService);
        ordered.verify(orderAggregateMutationLockService).lock(11L);
        ordered.verify(repository).findCurrentManagerIdByOrderId(11L);
        ordered.verify(managerAccessService).canAccessCurrentOrderManager(9L, authentication);
        verify(managerAccessService, never()).canAccessOrder(11L, authentication);
    }

    private WorkerAssignmentMutationGuardService service() {
        return new WorkerAssignmentMutationGuardService(
                repository,
                managerAccessService,
                orderAggregateMutationLockService
        );
    }

    private void authenticateWorker(String username) {
        authenticate(username, "ROLE_WORKER");
    }

    private TestingAuthenticationToken authenticate(String username, String... roles) {
        var authentication = new TestingAuthenticationToken(
                username,
                "n/a",
                roles
        );
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
