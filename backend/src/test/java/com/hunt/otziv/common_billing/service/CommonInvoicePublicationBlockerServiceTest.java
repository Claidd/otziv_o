package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommonInvoicePublicationBlockerServiceTest {

    @Mock
    private CommonInvoiceRepository invoiceRepository;
    @Mock
    private CommonInvoiceOrderRepository invoiceOrderRepository;
    @InjectMocks
    private CommonInvoicePublicationBlockerService service;

    @Test
    void startsTimerAsSoonAsOneSiblingReachesPublication() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        CommonInvoice invoice = invoice();
        CommonInvoiceOrder advanced = item(1L, "Публикация", now.minusHours(3), now.minusDays(5));
        CommonInvoiceOrder blocker = item(2L, "На проверке", now.minusDays(6), now.minusDays(5));

        assertEquals(1, service.reconcile(invoice, List.of(advanced, blocker), now));

        assertEquals(now.minusHours(3), blocker.getPublicationBlockerSince());
        verify(invoiceOrderRepository).save(blocker);
    }

    @Test
    void newlyAddedBlockerGetsItsOwnTimerAndDoesNotInheritOldInvoiceAge() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        CommonInvoice invoice = invoice();
        CommonInvoiceOrder advanced = item(1L, "Ожидает общего счета", now.minusDays(20), now.minusDays(30));
        CommonInvoiceOrder blocker = item(2L, "Новый", now.minusHours(1), now.minusHours(1));

        service.reconcile(invoice, List.of(advanced, blocker), now);

        assertEquals(now.minusHours(1), blocker.getPublicationBlockerSince());
    }

    @Test
    void transitionBetweenPrePublicationStatusesDoesNotResetTimer() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        LocalDateTime startedAt = now.minusDays(3);
        CommonInvoice invoice = invoice();
        CommonInvoiceOrder advanced = item(1L, "Публикация", now.minusDays(4), now.minusDays(5));
        CommonInvoiceOrder blocker = item(2L, "Коррекция", now.minusHours(1), now.minusDays(5));
        blocker.setPublicationBlockerSince(startedAt);

        assertEquals(0, service.reconcile(invoice, List.of(advanced, blocker), now));

        assertEquals(startedAt, blocker.getPublicationBlockerSince());
        verify(invoiceOrderRepository, never()).save(blocker);
    }

    @Test
    void clearsTimerWhenBlockerReachesPublication() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        CommonInvoice invoice = invoice();
        CommonInvoiceOrder first = item(1L, "Публикация", now.minusDays(4), now.minusDays(5));
        CommonInvoiceOrder resolved = item(2L, "Публикация", now, now.minusDays(5));
        resolved.setPublicationBlockerSince(now.minusDays(3));

        assertEquals(1, service.reconcile(invoice, List.of(first, resolved), now));

        assertNull(resolved.getPublicationBlockerSince());
        verify(invoiceOrderRepository).save(resolved);
    }

    @Test
    void exposesBlockerOnlyAfterFortyEightHours() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 12, 0);
        CommonInvoiceOrder recent = item(1L, "Новый", now, now);
        recent.setPublicationBlockerSince(now.minusHours(47));
        CommonInvoiceOrder overdue = item(2L, "На проверке", now, now);
        overdue.setPublicationBlockerSince(now.minusHours(49));

        assertFalse(service.hasOverdueBlockers(List.of(recent), now));
        assertTrue(service.hasOverdueBlockers(List.of(recent, overdue), now));
        assertEquals(List.of(overdue), service.overdueBlockers(List.of(recent, overdue), now));
    }

    private CommonInvoice invoice() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(10L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        return invoice;
    }

    private CommonInvoiceOrder item(
            Long id,
            String statusTitle,
            LocalDateTime statusChangedAt,
            LocalDateTime linkedAt
    ) {
        OrderStatus status = new OrderStatus();
        status.setTitle(statusTitle);
        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        order.setStatusChangedAt(statusChangedAt);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setId(id);
        item.setInvoice(invoice());
        item.setOrder(order);
        item.setCreatedAt(linkedAt);
        item.setUpdatedAt(linkedAt);
        return item;
    }
}
