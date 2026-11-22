package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveCandidateCounts;
import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.exception.ArchiveRestoreSpecialistRequiredException;
import com.hunt.otziv.archive.repository.OrderArchiveRestoreRepository;
import com.hunt.otziv.payments.api.BankPaperPaymentBindings;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderArchiveRestoreServiceTest {
    private final com.hunt.otziv.common_billing.api.RestoredOrderInvoiceMembership membership =
            mock(com.hunt.otziv.common_billing.api.RestoredOrderInvoiceMembership.class);
    private final BankPaperPaymentBindings paperDocuments = mock(BankPaperPaymentBindings.class);

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

        var result = service(repository)
                .restoreOrder(22752L, "Опубликовано", "manager", true);

        assertThat(result.targetStatus()).isEqualTo("Опубликовано");
        assertThat(result.restored()).isEqualTo(rows);
        verify(repository).markArchiveOrderRestored(
                eq(22752L),
                eq(91L),
                any(),
                eq("manager")
        );
        verify(repository).insertRestoreBatch(
                eq(22752L),
                any(),
                eq("manager"),
                eq("Опубликовано"),
                eq(rows),
                contains("targetStatus=Опубликовано")
        );
    }

    @Test
    void paidStatusIsGrantedOnlyWithAnActivePaymentCheck() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubRestorable(repository, 501L, "Оплачено", 7L);
        when(repository.findPaymentState(501L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Бан", false));

        assertThatThrownBy(() -> service(repository)
                        .restoreOrder(501L, "Оплачено", "manager", true))
                .isInstanceOf(ArchiveRestoreConflictException.class);
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void paidArchivedOrderIsNotSilentlyUnpaidByAWorkStatusRestore() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubRestorable(repository, 502L, "Новый", 1L);
        when(repository.findPaymentState(502L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Оплачено", true));

        assertThatThrownBy(() -> service(repository)
                        .restoreOrder(502L, "Новый", "admin", true))
                .isInstanceOf(ArchiveRestoreConflictException.class);
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void unpaidRestoreClearsCompletionMarkersSoTheOrderCanMove() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubRestorable(repository, 503L, "Коррекция", 3L);
        when(repository.findPaymentState(503L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Архив", false));
        when(repository.restoreOrder(503L, 3L)).thenReturn(rows);

        service(repository).restoreIndividualOrder(503L, "Коррекция", "manager", true);
        verify(membership).attachOrderByIdIfNeeded(503L);

        verify(repository).clearCompletionMarkers(503L);
    }

    @Test
    void deactivatedSpecialistBlocksReturnToWorkAndOffersActiveSpecialists() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubUnpaid(repository, 504L, "Новый", 1L);
        when(repository.findArchiveOrderWorker(504L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        List<ArchiveRecoveryWorkerOption> options = List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый специалист", true),
                new ArchiveRecoveryWorkerOption(73L, "Другой специалист", false));
        when(repository.findRestoreWorkerOptions(504L)).thenReturn(options);

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(504L, "Новый", "manager", true, null))
                .isInstanceOfSatisfying(ArchiveRestoreSpecialistRequiredException.class, exception -> {
                    assertThat(exception.getMessage())
                            .isEqualTo("Специалист заказа деактивирован — выберите действующего специалиста");
                    assertThat(exception.workers()).isEqualTo(options);
                });
        verify(repository, never()).restoreOrder(any(), any());
        verify(repository, never()).reassignRestoredOrderWorker(any(), any());
        verify(repository, never()).insertRestoreBatch(any(), any(), any(), any(), any(), any());
        verify(membership, never()).attachOrderByIdIfNeeded(any());
    }

    @Test
    void orderWithoutSpecialistAlsoAsksForOne() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubUnpaid(repository, 505L, "Коррекция", 3L);
        when(repository.findArchiveOrderWorker(505L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(null, false));
        when(repository.findRestoreWorkerOptions(505L)).thenReturn(List.of());

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(505L, "Коррекция", "manager", true, null))
                .isInstanceOf(ArchiveRestoreSpecialistRequiredException.class)
                .hasMessage("У заказа нет специалиста — выберите действующего специалиста");
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void chosenSpecialistReceivesTheRestoredOrderInTheSameRestore() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubUnpaid(repository, 506L, "На проверке", 4L);
        when(repository.restoreOrder(506L, 4L)).thenReturn(rows);
        when(repository.findArchiveOrderWorker(506L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        when(repository.findRestoreWorkerOptions(506L)).thenReturn(List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый специалист", true)));

        var result = service(repository)
                .restoreIndividualOrderWithActiveSpecialist(506L, "На проверке", "manager", true, 72L);

        assertThat(result.targetStatus()).isEqualTo("На проверке");
        InOrder order = inOrder(repository, membership);
        order.verify(repository).restoreOrder(506L, 4L);
        order.verify(repository).clearCompletionMarkers(506L);
        order.verify(repository).reassignRestoredOrderWorker(506L, 72L);
        order.verify(repository).insertRestoreBatch(
                eq(506L), any(), eq("manager"), eq("На проверке"), eq(rows), contains("newWorkerId=72"));
        order.verify(membership).attachOrderByIdIfNeeded(506L);
        verify(paperDocuments).hasDocument(506L);
        verify(repository).hasFrozenLivePaymentRoute(506L);
    }

    @Test
    void activeSpecialistStaysWithoutAQuestion() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubUnpaid(repository, 507L, "Новый", 1L);
        when(repository.restoreOrder(507L, 1L)).thenReturn(rows);
        when(repository.findArchiveOrderWorker(507L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(72L, true));

        service(repository).restoreIndividualOrderWithActiveSpecialist(507L, "Новый", "manager", true, null);

        verify(repository).restoreOrder(507L, 1L);
        verify(repository, never()).reassignRestoredOrderWorker(any(), any());
        verify(repository, never()).findRestoreWorkerOptions(any());
    }

    @Test
    void specialistNoLongerAvailableIsRefusedWithTheFreshList() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubUnpaid(repository, 508L, "Новый", 1L);
        when(repository.findArchiveOrderWorker(508L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        List<ArchiveRecoveryWorkerOption> options = List.of(new ArchiveRecoveryWorkerOption(72L, "Новый", true));
        when(repository.findRestoreWorkerOptions(508L)).thenReturn(options);

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(508L, "Новый", "manager", true, 99L))
                .isInstanceOfSatisfying(ArchiveRestoreSpecialistRequiredException.class, exception -> {
                    assertThat(exception.getMessage())
                            .isEqualTo("Выбранный специалист недоступен — выберите действующего специалиста");
                    assertThat(exception.workers()).isEqualTo(options);
                });
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void fixedPaymentRecipientKeepsTheSpecialist() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubUnpaid(repository, 509L, "Новый", 1L);
        when(repository.findArchiveOrderWorker(509L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        when(repository.findRestoreWorkerOptions(509L)).thenReturn(List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый", true)));
        when(repository.hasFrozenLivePaymentRoute(509L)).thenReturn(true);

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(509L, "Новый", "manager", true, 72L))
                .isInstanceOf(ArchiveRestoreConflictException.class)
                .isNotInstanceOf(ArchiveRestoreSpecialistRequiredException.class)
                .hasMessageContaining("получатель платежа");
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void issuedBankDocumentsKeepTheSpecialist() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubUnpaid(repository, 510L, "Новый", 1L);
        when(repository.findArchiveOrderWorker(510L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        when(repository.findRestoreWorkerOptions(510L)).thenReturn(List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый", true)));
        when(paperDocuments.hasDocument(510L)).thenReturn(true);

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(510L, "Новый", "manager", true, 72L))
                .isInstanceOf(ArchiveRestoreConflictException.class)
                .hasMessageContaining("банковские документы");
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void withoutAChoiceAFixedPaymentRecipientRestoresOntoTheArchivedSpecialist() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubUnpaid(repository, 515L, "Новый", 1L);
        when(repository.restoreOrder(515L, 1L)).thenReturn(rows);
        when(repository.findArchiveOrderWorker(515L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));
        when(repository.hasFrozenLivePaymentRoute(515L)).thenReturn(true);

        service(repository).restoreIndividualOrderWithActiveSpecialist(515L, "Новый", "manager", true, null);

        verify(repository).restoreOrder(515L, 1L);
        verify(repository, never()).reassignRestoredOrderWorker(any(), any());
        verify(repository, never()).findRestoreWorkerOptions(any());
    }

    @Test
    void paidRestoreWithDeactivatedSpecialistStaysAllowedUnchanged() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts rows = stubRestorable(repository, 511L, "Оплачено", 7L);
        when(repository.findPaymentState(511L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Оплачено", true));
        when(repository.restoreOrder(511L, 7L)).thenReturn(rows);
        when(repository.findArchiveOrderWorker(511L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));

        var result = service(repository)
                .restoreIndividualOrderWithActiveSpecialist(511L, "Оплачено", "admin", true, null);

        assertThat(result.targetStatus()).isEqualTo("Оплачено");
        verify(repository).restoreOrder(511L, 7L);
        verify(repository, never()).findArchiveOrderWorker(any());
        verify(repository, never()).reassignRestoredOrderWorker(any(), any());
    }

    @Test
    void specialistCannotBeChosenForAPaidRestore() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        stubRestorable(repository, 512L, "Оплачено", 7L);
        when(repository.findPaymentState(512L))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Оплачено", true));

        assertThatThrownBy(() -> service(repository)
                        .restoreIndividualOrderWithActiveSpecialist(512L, "Оплачено", "admin", true, 72L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).restoreOrder(any(), any());
    }

    @Test
    void automaticRestoresKeepTheirBehaviourWithoutAPersonToAsk() {
        OrderArchiveRestoreRepository repository = mock(OrderArchiveRestoreRepository.class);
        ArchiveCandidateCounts reviewCheckRows = stubUnpaid(repository, 513L, "На проверке", 4L);
        when(repository.restoreOrder(513L, 4L)).thenReturn(reviewCheckRows);
        ArchiveCandidateCounts invoiceRows = stubUnpaid(repository, 514L, "Коррекция", 3L);
        when(repository.restoreOrder(514L, 3L)).thenReturn(invoiceRows);
        when(repository.findArchiveOrderWorker(any()))
                .thenReturn(new OrderArchiveRestoreRepository.ArchiveOrderWorker(2L, false));

        // Old review-check link and archived common invoice: nobody is there to pick a specialist.
        service(repository).restoreIndividualOrder(513L, "На проверке", "client", true);
        service(repository).restoreOrder(514L, "Коррекция", "alex", true);

        verify(repository).restoreOrder(513L, 4L);
        verify(repository).restoreOrder(514L, 3L);
        verify(repository, never()).findArchiveOrderWorker(any());
        verify(repository, never()).reassignRestoredOrderWorker(any(), any());
    }

    private OrderArchiveRestoreService service(OrderArchiveRestoreRepository repository) {
        return new OrderArchiveRestoreService(repository, membership, paperDocuments);
    }

    private static ArchiveCandidateCounts stubUnpaid(OrderArchiveRestoreRepository repository, long orderId,
                                                     String status, long statusId) {
        ArchiveCandidateCounts rows = stubRestorable(repository, orderId, status, statusId);
        when(repository.findPaymentState(orderId))
                .thenReturn(new OrderArchiveRestoreRepository.ArchivePaymentState("Архив", false));
        return rows;
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
