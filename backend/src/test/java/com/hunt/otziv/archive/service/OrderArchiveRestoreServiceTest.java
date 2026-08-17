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
}
