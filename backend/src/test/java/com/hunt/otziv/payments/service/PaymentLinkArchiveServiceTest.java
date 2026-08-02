package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.repository.PaymentLinkArchiveRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PaymentLinkArchiveServiceTest {

    @Mock
    private PaymentLinkArchiveRepository repository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private TbankPaymentProperties properties;

    @Test
    void autoArchiveLocksParentOrdersBeforeRevalidatingAndLockingLinks() {
        List<Long> snapshotIds = List.of(10L);
        List<Long> orderIds = List.of(20L);
        when(repository.findArchiveCandidateIds(any(LocalDateTime.class), any(LocalDateTime.class), eq(50)))
                .thenReturn(snapshotIds);
        when(repository.findOrderIdsForPaymentLinkIds(snapshotIds)).thenReturn(orderIds);
        when(repository.lockOrderIdsForArchive(orderIds)).thenReturn(orderIds);
        when(repository.findArchiveCandidateIdsForUpdate(
                eq(snapshotIds),
                eq(orderIds),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(snapshotIds);
        when(repository.countArchivedIds(snapshotIds)).thenReturn(1);
        when(repository.deleteLiveIds(snapshotIds)).thenReturn(1);

        var result = service().run(false, 50);

        assertEquals(1, result.eligible());
        assertEquals(1, result.archived());
        assertEquals(1, result.deleted());
        InOrder order = inOrder(repository);
        order.verify(repository).findArchiveCandidateIds(any(), any(), eq(50));
        order.verify(repository).findOrderIdsForPaymentLinkIds(snapshotIds);
        order.verify(repository).lockOrderIdsForArchive(orderIds);
        order.verify(repository).findArchiveCandidateIdsForUpdate(
                eq(snapshotIds), eq(orderIds), any(), any()
        );
        order.verify(repository).deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(snapshotIds);
        order.verify(repository).archiveIds(
                eq(snapshotIds), any(LocalDateTime.class), eq("AUTO_CLOSED_PAYMENT_LINK"), any()
        );
        order.verify(repository).deleteLiveIds(snapshotIds);
    }

    @Test
    void archivesAndVerifiesPreparedOrderPaymentLinksBeforeDeletingThem() {
        List<Long> ids = List.of(10L, 11L);
        when(repository.findLiveIdsForPreparedOrderArchiveCandidatesForUpdate()).thenReturn(ids);
        when(repository.countArchivedIds(ids)).thenReturn(2);
        when(repository.deleteLiveIds(ids)).thenReturn(2);

        int deleted = service().archiveForPreparedOrderArchiveCandidates(77L);

        assertEquals(2, deleted);
        InOrder order = inOrder(repository);
        order.verify(repository).findLiveIdsForPreparedOrderArchiveCandidatesForUpdate();
        order.verify(repository).deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(ids);
        order.verify(repository).hasPreparedOrderArchiveBlocker();
        order.verify(repository).archiveIds(eq(ids), any(LocalDateTime.class), eq("ORDER_ARCHIVED"), eq(77L));
        order.verify(repository).deleteLiveIds(ids);
    }

    @Test
    void refusesToDeleteWhenArchiveCopyIsIncomplete() {
        List<Long> ids = List.of(10L, 11L);
        when(repository.findLiveIdsForPreparedOrderArchiveCandidatesForUpdate()).thenReturn(ids);
        when(repository.countArchivedIds(ids)).thenReturn(1);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service().archiveForPreparedOrderArchiveCandidates(77L)
        );

        assertEquals("Payment link archive verification failed: selected=2, archived=1", exception.getMessage());
        verify(repository, never()).deleteLiveIds(ids);
    }

    @Test
    void refusesToArchiveDeletedOrderWhilePaymentSideEffectIsPending() {
        when(repository.findLiveIdsByOrderIdForUpdate(42L)).thenReturn(List.of(10L));
        when(repository.hasLiveArchiveBlockerForOrder(42L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service().archiveForDeletedOrder(42L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Заказ нельзя удалить: платежная операция или уведомление еще не завершены",
                exception.getReason()
        );
        InOrder order = inOrder(repository);
        order.verify(repository).findLiveIdsByOrderIdForUpdate(42L);
        order.verify(repository).deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(List.of(10L));
        order.verify(repository).hasLiveArchiveBlockerForOrder(42L);
        verify(repository, never()).archiveIds(any(), any(), any(), any());
    }

    @Test
    void refusesPreparedOrderArchiveOnLatePaymentSideEffect() {
        when(repository.findLiveIdsForPreparedOrderArchiveCandidatesForUpdate()).thenReturn(List.of(10L));
        when(repository.hasPreparedOrderArchiveBlocker()).thenReturn(true);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service().archiveForPreparedOrderArchiveCandidates(77L)
        );

        assertEquals(
                "Order archive blocked: a payment operation or notification is still pending",
                exception.getMessage()
        );
        InOrder order = inOrder(repository);
        order.verify(repository).findLiveIdsForPreparedOrderArchiveCandidatesForUpdate();
        order.verify(repository).deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(List.of(10L));
        order.verify(repository).hasPreparedOrderArchiveBlocker();
        verify(repository, never()).archiveIds(any(), any(), any(), any());
    }

    @Test
    void refusesDeletedOrderArchiveWhenConditionalDeleteLosesConcurrencyFence() {
        List<Long> ids = List.of(10L);
        when(repository.findLiveIdsByOrderIdForUpdate(42L)).thenReturn(ids);
        when(repository.countArchivedIds(ids)).thenReturn(1);
        when(repository.deleteLiveIds(ids)).thenReturn(0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service().archiveForDeletedOrder(42L)
        );

        assertEquals(
                "Deleted order payment delete verification failed: selected=1, deleted=0",
                exception.getMessage()
        );
    }

    private PaymentLinkArchiveService service() {
        return new PaymentLinkArchiveService(repository, appSettingService, properties);
    }
}
