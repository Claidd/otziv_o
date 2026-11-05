package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveCandidateCounts;
import com.hunt.otziv.archive.repository.OrderArchiveRestoreRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderArchiveRestoreServiceTest {

    @Test
    void publishedIsAnAllowedIntermediateStatusForArchivedPayment() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = new ArchiveCandidateCounts(1, 1, 5, 0, 0, 0, 0);
        ArchiveCandidateCounts empty = new ArchiveCandidateCounts(0, 0, 0, 0, 0, 0, 0);
        when(repository.findStatusId("Опубликовано")).thenReturn(6L);
        when(repository.countArchiveRows(22752L)).thenReturn(rows);
        when(repository.isAlreadyRestored(22752L)).thenReturn(false);
        when(repository.countLiveConflicts(22752L)).thenReturn(empty);
        when(repository.restoreOrder(22752L, 6L)).thenReturn(rows);
        when(repository.insertRestoreBatch(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(91L);

        var result = new OrderArchiveRestoreService(repository)
                .restoreOrder(22752L, "Опубликовано", "manager", true);

        assertThat(result.targetStatus()).isEqualTo("Опубликовано");
        assertThat(result.restored()).isEqualTo(rows);
        verify(repository).markArchiveOrderRestored(
                org.mockito.ArgumentMatchers.eq(22752L),
                org.mockito.ArgumentMatchers.eq(91L),
                any(),
                org.mockito.ArgumentMatchers.eq("manager")
        );
        verify(repository).insertRestoreBatch(
                org.mockito.ArgumentMatchers.eq(22752L),
                any(),
                org.mockito.ArgumentMatchers.eq("manager"),
                org.mockito.ArgumentMatchers.eq("Опубликовано"),
                org.mockito.ArgumentMatchers.eq(rows),
                contains("targetStatus=Опубликовано")
        );
    }

    @Test
    void paidStatusIsGrantedOnlyWithAnActivePaymentCheck() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubRestorable(repository, 501L, "Оплачено", 7L);
        when(repository.findPaymentState(501L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Бан", false));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OrderArchiveRestoreService(repository)
                        .restoreOrder(501L, "Оплачено", "manager", true))
                .isInstanceOf(com.hunt.otziv.archive.exception.ArchiveRestoreConflictException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).restoreOrder(any(), any());
    }

    @Test
    void paidArchivedOrderIsNotSilentlyUnpaidByAWorkStatusRestore() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubRestorable(repository, 502L, "Новый", 1L);
        when(repository.findPaymentState(502L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Оплачено", true));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OrderArchiveRestoreService(repository)
                        .restoreOrder(502L, "Новый", "admin", true))
                .isInstanceOf(com.hunt.otziv.archive.exception.ArchiveRestoreConflictException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).restoreOrder(any(), any());
    }

    @Test
    void unpaidRestoreClearsCompletionMarkersSoTheOrderCanMove() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubRestorable(repository, 503L, "Коррекция", 3L);
        when(repository.findPaymentState(503L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Архив", false));
        when(repository.restoreOrder(503L, 3L)).thenReturn(rows);

        new OrderArchiveRestoreService(repository).restoreOrder(503L, "Коррекция", "manager", true);

        verify(repository).clearCompletionMarkers(503L);
    }

    private static ArchiveCandidateCounts stubRestorable(OrderArchiveRestoreRepository repository, long orderId,
                                                         String status, long statusId) {
        ArchiveCandidateCounts rows = new ArchiveCandidateCounts(1, 1, 2, 0, 0, 0, 0);
        when(repository.findStatusId(status)).thenReturn(statusId);
        when(repository.countArchiveRows(orderId)).thenReturn(rows);
        when(repository.isAlreadyRestored(orderId)).thenReturn(false);
        when(repository.countLiveConflicts(orderId)).thenReturn(new ArchiveCandidateCounts(0, 0, 0, 0, 0, 0, 0));
        when(repository.insertRestoreBatch(any(), any(), any(), any(), any(), any())).thenReturn(1L);
        return rows;
    }
}
