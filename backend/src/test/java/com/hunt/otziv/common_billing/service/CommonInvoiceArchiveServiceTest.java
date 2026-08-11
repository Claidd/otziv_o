package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.archive.dto.ArchiveCandidateCounts;
import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import com.hunt.otziv.archive.service.OrderArchiveRestoreService;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveListItem;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveOrderItem;
import com.hunt.otziv.common_billing.repository.CommonInvoiceArchiveRepository;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceArchiveServiceTest {

    @Mock
    private CommonInvoiceArchiveRepository repository;
    @Mock
    private CommonBillingService commonBillingService;
    @Mock
    private OrderArchiveRestoreService orderArchiveRestoreService;
    @Mock
    private ManagerPermissionService managerPermissionService;
    @Mock
    private UserService userService;
    @Mock
    private ManagerService managerService;
    @Mock
    private Authentication authentication;

    private CommonInvoiceArchiveService service;

    @BeforeEach
    void setUp() {
        service = new CommonInvoiceArchiveService(
                repository,
                commonBillingService,
                orderArchiveRestoreService,
                managerPermissionService,
                userService,
                managerService
        );
        when(managerPermissionService.hasRole(authentication, "ADMIN")).thenReturn(true);
    }

    @Test
    void restoresPaidAndUnpaidOrdersFromBannedGroupWithoutLosingFinancialStatuses() {
        CommonInvoiceArchiveListItem invoice = archiveInvoice("BAN");
        List<CommonInvoiceArchiveOrderItem> orders = List.of(
                archiveOrder(101L, "Оплачено", "", true),
                archiveOrder(102L, "Бан", "", false)
        );
        when(repository.findOne(eq(com.hunt.otziv.archive.dto.ArchiveAccessScope.all()), eq(40L)))
                .thenReturn(Optional.of(invoice));
        when(repository.findOrders(40L, "archive")).thenReturn(orders);
        when(repository.lockAndCheckPaymentRefsRestorable(40L)).thenReturn(true);
        when(repository.archivedStatus(40L)).thenReturn("BAN");
        when(orderArchiveRestoreService.restoreOrder(eq(101L), eq("Оплачено"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(701L, 101L, "Оплачено"));
        when(orderArchiveRestoreService.restoreOrder(eq(102L), eq("Бан"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(702L, 102L, "Бан"));

        var result = service.restore(40L, true, () -> "alex", authentication);

        assertEquals("BAN", result.status());
        assertEquals(List.of(101L, 102L), result.orderIds());
        verify(repository).restoreInvoice(eq(40L), anyString(), eq("alex"), eq(701L));
        verify(repository).refreshRestoredClosedRetention(40L, "alex");
        verify(repository, never()).reopenRestoredManualInvoice(40L);
    }

    @Test
    void restoresManualGroupToRememberedReviewStatusesAndReopensParent() {
        CommonInvoiceArchiveListItem invoice = archiveInvoice("ARCHIVED");
        List<CommonInvoiceArchiveOrderItem> orders = List.of(
                archiveOrder(201L, "Архив", "На проверке", false),
                archiveOrder(202L, "Архив", "Коррекция", false)
        );
        when(repository.findOne(eq(com.hunt.otziv.archive.dto.ArchiveAccessScope.all()), eq(40L)))
                .thenReturn(Optional.of(invoice));
        when(repository.findOrders(40L, "archive")).thenReturn(orders);
        when(repository.lockAndCheckPaymentRefsRestorable(40L)).thenReturn(true);
        when(repository.archivedStatus(40L)).thenReturn("ARCHIVED");
        when(orderArchiveRestoreService.restoreOrder(eq(201L), eq("На проверке"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(801L, 201L, "На проверке"));
        when(orderArchiveRestoreService.restoreOrder(eq(202L), eq("Коррекция"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(802L, 202L, "Коррекция"));

        var result = service.restore(40L, true, () -> "alex", authentication);

        assertEquals("COLLECTING", result.status());
        verify(repository).restoreInvoice(eq(40L), anyString(), eq("alex"), eq(801L));
        verify(repository).reopenRestoredManualInvoice(40L);
        verify(repository, never()).refreshRestoredClosedRetention(40L, "alex");
    }

    @Test
    void restoresPaidGroupAsPaidAndStartsANewLiveRetentionWindow() {
        CommonInvoiceArchiveListItem invoice = archiveInvoice("PAID");
        List<CommonInvoiceArchiveOrderItem> orders = List.of(
                archiveOrder(301L, "Оплачено", "", true),
                archiveOrder(302L, "Оплачено", "", true)
        );
        when(repository.findOne(eq(com.hunt.otziv.archive.dto.ArchiveAccessScope.all()), eq(40L)))
                .thenReturn(Optional.of(invoice));
        when(repository.findOrders(40L, "archive")).thenReturn(orders);
        when(repository.lockAndCheckPaymentRefsRestorable(40L)).thenReturn(true);
        when(repository.archivedStatus(40L)).thenReturn("PAID");
        when(orderArchiveRestoreService.restoreOrder(eq(301L), eq("Оплачено"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(901L, 301L, "Оплачено"));
        when(orderArchiveRestoreService.restoreOrder(eq(302L), eq("Оплачено"), eq("alex"), eq(true)))
                .thenReturn(restoreResult(902L, 302L, "Оплачено"));

        var result = service.restore(40L, true, () -> "alex", authentication);

        assertEquals("PAID", result.status());
        verify(repository).restoreInvoice(eq(40L), anyString(), eq("alex"), eq(901L));
        verify(repository).refreshRestoredClosedRetention(40L, "alex");
        verify(repository, never()).reopenRestoredManualInvoice(40L);
    }

    @Test
    void refusesPhysicalRestoreWhenLockedPaymentRegistryRecheckFindsNonterminalRefs() {
        when(repository.findOne(eq(com.hunt.otziv.archive.dto.ArchiveAccessScope.all()), eq(40L)))
                .thenReturn(Optional.of(archiveInvoice("PAID")));
        when(repository.lockAndCheckPaymentRefsRestorable(40L)).thenReturn(false);

        var error = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.restore(40L, true, () -> "alex", authentication)
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        verify(repository, never()).findOrders(40L, "archive");
        verify(repository, never()).restoreInvoice(eq(40L), anyString(), eq("alex"), eq(null));
        verify(orderArchiveRestoreService, never())
                .restoreOrder(eq(301L), eq("Оплачено"), eq("alex"), eq(true));
    }

    private CommonInvoiceArchiveListItem archiveInvoice(String status) {
        return new CommonInvoiceArchiveListItem(
                40L,
                "Общий клиент",
                "Счет #40",
                status,
                3_200_00L,
                1_600_00L,
                2,
                LocalDateTime.of(2026, 7, 1, 10, 0),
                "alex",
                status,
                LocalDateTime.of(2026, 7, 20, 4, 15),
                "archive",
                true
        );
    }

    private CommonInvoiceArchiveOrderItem archiveOrder(
            Long orderId,
            String status,
            String sourceStatus,
            boolean paid
    ) {
        return new CommonInvoiceArchiveOrderItem(
                orderId,
                "Компания " + orderId,
                "Филиал",
                status,
                sourceStatus,
                1_600_00L,
                paid
        );
    }

    private ArchiveRestoreResult restoreResult(Long batchId, Long orderId, String status) {
        ArchiveCandidateCounts counts = new ArchiveCandidateCounts(1, 0, 0, 0, 0, 0, 0);
        return new ArchiveRestoreResult(
                batchId,
                orderId,
                LocalDateTime.now(),
                "alex",
                status,
                counts,
                counts,
                "restored"
        );
    }
}
