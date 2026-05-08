package com.hunt.otziv.p_products.worker_flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerFlowLockServiceTest {

    @Mock
    private WorkerFlowLockRepository repository;

    @Test
    void syncPublicationLockClearsLockWhenFlowOrdersAreDone() {
        WorkerFlowLockService service = service();

        boolean locked = service.syncPublicationLock("worker:88", 88L, false, false);

        assertFalse(locked);
        verify(repository).deleteByLockKey("worker:88");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void syncPublicationLockCreatesPersistentLockForStaleOrders() {
        WorkerFlowLockService service = service();
        ArgumentCaptor<WorkerFlowLock> lockCaptor = ArgumentCaptor.forClass(WorkerFlowLock.class);

        when(repository.findById("worker:88")).thenReturn(Optional.empty());

        boolean locked = service.syncPublicationLock("worker:88", 88L, true, true);

        assertTrue(locked);
        verify(repository).save(lockCaptor.capture());
        assertEquals("worker:88", lockCaptor.getValue().getLockKey());
        assertEquals(88L, lockCaptor.getValue().getWorkerId());
    }

    @Test
    void syncPublicationLockKeepsExistingLockUntilFlowOrdersAreDone() {
        WorkerFlowLockService service = service();

        when(repository.existsById("worker:88")).thenReturn(true);

        boolean locked = service.syncPublicationLock("worker:88", 88L, true, false);

        assertTrue(locked);
        verify(repository).existsById("worker:88");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).deleteByLockKey(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void syncPublicationLockLeavesFreshFlowUnlockedWithoutExistingLock() {
        WorkerFlowLockService service = service();

        when(repository.existsById("worker:88")).thenReturn(false);

        boolean locked = service.syncPublicationLock("worker:88", 88L, true, false);

        assertFalse(locked);
        verify(repository).existsById("worker:88");
    }

    @Test
    void syncPublicationLockUpdatesExistingLockWorkerId() {
        WorkerFlowLockService service = service();
        WorkerFlowLock existingLock = WorkerFlowLock.builder()
                .lockKey("worker:88")
                .workerId(77L)
                .build();

        when(repository.findById("worker:88")).thenReturn(Optional.of(existingLock));

        boolean locked = service.syncPublicationLock("worker:88", 88L, true, true);

        assertTrue(locked);
        assertEquals(88L, existingLock.getWorkerId());
        verify(repository).save(existingLock);
    }

    private WorkerFlowLockService service() {
        return new WorkerFlowLockService(repository);
    }
}
