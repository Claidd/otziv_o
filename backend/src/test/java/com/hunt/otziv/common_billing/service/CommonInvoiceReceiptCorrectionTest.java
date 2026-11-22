package com.hunt.otziv.common_billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.api.ManualReceiptOrderDebt;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Undoing a paper invoice payment confirmed by mistake: only a manual confirmation, never a bank receipt. */
class CommonInvoiceReceiptCorrectionTest {

    private final CommonInvoiceDeliveryService delivery = mock(CommonInvoiceDeliveryService.class);
    private final CommonInvoiceOrderRepository items = mock(CommonInvoiceOrderRepository.class);
    private final ManualReceiptOrderDebt orders = mock(ManualReceiptOrderDebt.class);
    private final CommonInvoiceReceiptCorrection correction = new CommonInvoiceReceiptCorrection(delivery, items, orders);
    private final Principal owner = () -> "owner";

    @Test
    void aMistakenPaperPaymentReopensEveryOrderAndMakesTheSameInvoicePayableAgain() {
        CommonInvoice invoice = paidPaperInvoice("owner");
        List<CommonInvoiceOrder> positions = List.of(item(27123L, 140000L), item(27124L, 300000L));
        stub(invoice, positions);

        correction.reopenOwnerPaperPayment(283L, owner, "Отмечено по ошибке, оплаты не было");

        verify(orders).reopenPaperInvoiceOrder(27123L, 140000L, owner, "Отмечено по ошибке, оплаты не было");
        verify(orders).reopenPaperInvoiceOrder(27124L, 300000L, owner, "Отмечено по ошибке, оплаты не было");
        // The paper invoice was delivered and reminded about: the same invoice keeps reminding.
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.REMINDER);
        assertThat(invoice.getPaidKopecks()).isZero();
        assertThat(invoice.getPaymentMethod()).isNull();
        assertThat(invoice.getManualConfirmedAt()).isNull();
        assertThat(invoice.getPaperInvoiceIssuedAt()).isNotNull();
        assertThat(invoice.getPaymentSuccessNotificationError()).isNull();
        assertThat(positions).allSatisfy(item -> {
            assertThat(item.isPaid()).isFalse();
            // «Напоминание», not «Не оплачено».
            assertThat(item.isUnpaid()).isFalse();
            assertThat(item.getPaymentMethod()).isNull();
        });
    }

    @Test
    void aPaymentConfirmedByTheBankIsNeverUndoneHere() {
        CommonInvoice invoice = paidPaperInvoice(CommonInvoiceReceiptCorrection.PAPER_BANK_ACTOR);
        stub(invoice, List.of(item(27123L, 440000L)));

        assertThatThrownBy(() -> correction.reopenOwnerPaperPayment(283L, owner, "Отмечено по ошибке"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("без поступления по банку");
        verify(orders, never()).reopenPaperInvoiceOrder(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void anOrdinaryManualPaymentGoesThroughTheReceiptJournalNotThisPath() {
        CommonInvoice invoice = paidPaperInvoice("owner");
        invoice.setInvoicePaymentMode(InvoicePaymentMode.AUTO_ROUTING);
        invoice.setPaymentMethod("MANUAL");
        stub(invoice, List.of(item(27123L, 440000L)));

        assertThatThrownBy(() -> correction.reopenOwnerPaperPayment(283L, owner, "Отмечено по ошибке"))
                .isInstanceOf(ResponseStatusException.class);
        verify(orders, never()).reopenPaperInvoiceOrder(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void aReasonIsRequired() {
        assertThatThrownBy(() -> correction.reopenOwnerPaperPayment(283L, owner, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("причину");
        verify(delivery, never()).lockedInvoiceAfterStandalonePaymentPrelude(any());
    }

    private CommonInvoice paidPaperInvoice(String paidBy) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(283L);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        invoice.setPaymentMethod(CommonInvoiceManualPaymentWorkflow.PAYMENT_METHOD_OWNER_PAPER_INVOICE);
        invoice.setManualConfirmedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setManualPaidBy(paidBy);
        invoice.setPaidKopecks(440000L);
        invoice.setAmountKopecks(440000L);
        invoice.setSentAt(LocalDateTime.now().minusDays(33));
        invoice.setLastReminderAt(LocalDateTime.now().minusDays(2));
        invoice.setPaperInvoiceIssuedAt(LocalDateTime.now().minusDays(31));
        invoice.setPaidAt(LocalDateTime.now().minusMinutes(5));
        invoice.setClosedAt(LocalDateTime.now().minusMinutes(5));
        invoice.setPaymentSuccessNotificationError("immediate_messages_disabled");
        return invoice;
    }

    private CommonInvoiceOrder item(Long orderId, long amountKopecks) {
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        Order order = new Order();
        order.setId(orderId);
        item.setOrder(order);
        item.setAmountKopecks(amountKopecks);
        item.setPaid(true);
        item.setPaymentMethod(CommonInvoiceManualPaymentWorkflow.PAYMENT_METHOD_OWNER_PAPER_INVOICE);
        return item;
    }

    private void stub(CommonInvoice invoice, List<CommonInvoiceOrder> positions) {
        when(delivery.lockedInvoiceAfterStandalonePaymentPrelude(283L))
                .thenReturn(new CommonInvoiceInitializationService.LockedInvoicePaymentPrelude(invoice, Map.of()));
        when(delivery.lockedInvoice(283L)).thenReturn(Optional.of(invoice));
        when(items.findByInvoiceIdWithOrders(eq(283L))).thenReturn(positions);
    }
}
