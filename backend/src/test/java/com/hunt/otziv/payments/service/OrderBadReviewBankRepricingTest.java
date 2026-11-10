package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.api.ManualPaymentAutomationControl;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.api.UnpaidBankObservation.*;
import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderBadReviewBankRepricingTest {
    final OrderPaymentLinkWorkflow orders = mock();
    final ManualPaymentAutomationControl messages = mock();
    final PaymentLinkSettlementService settlement = mock();
    final OrderBadReviewBankRepricing worker = new OrderBadReviewBankRepricing(mock(PaymentLinkRepository.class), orders, messages, settlement);
    final PaymentLink link = new PaymentLink();
    final Order order = new Order();

    @BeforeEach void opened() {
        order.setId(21L);
        link.setId(13L);
        link.setOrder(order);
        var profile = new PaymentProfile();
        profile.setId(7L);
        profile.setProvider("T_BANK");
        link.setPaymentProfile(profile);
        link.setTbankOrderId("order-21");
        link.setTbankPaymentId("payment-13");
        link.setTbankTerminalKey("merchant");
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setAmountKopecks(120000L);
        link.setBadReviewBankCheckAt(LocalDateTime.now().minusMinutes(1));
        when(orders.lockForBadReviewBankCheck(13L)).thenReturn(link);
    }

    @Test void onlyVerifiedUnpaidClosureRearmsTheInvoiceOnce() {
        var source = worker.claim(13);
        assertThat(source).isNotNull();
        assertThat(worker.claim(13)).isNull();
        worker.finish(source, new Result(Outcome.WAITING, "FORM_SHOWED"));
        verifyNoInteractions(messages);
        verify(orders, never()).releaseClosedPaymentLink(link);
        var closed = new Result(Outcome.CLOSED, "DEADLINE_EXPIRED");
        worker.finish(source, closed);
        worker.finish(source, closed);
        assertThat(link.getStatus()).isEqualTo(PaymentLinkStatus.EXPIRED);
        assertThat(link.getBadReviewBankCheckAt()).isNull();
        verify(messages).scheduleRepricedBadReviewInvoice(21L);
        verify(orders).releaseClosedPaymentLink(link);
        verifyNoInteractions(settlement);
    }

    @Test void receiptForTheOldAmountUsesNormalBankSettlement() {
        var source = worker.claim(13);
        worker.finish(source, new Result(Outcome.FINANCIAL, "CONFIRMED"));
        verify(settlement).applyBankStatus(link, "CONFIRMED", true, "0");
        verifyNoInteractions(messages);
        verify(orders, never()).releaseClosedPaymentLink(link);
    }

    @Test void replacementOrConcurrentReceiptCannotBeClosedUsingAnOldObservation() {
        var source = worker.claim(13);
        link.setConfirmedAmountKopecks(120000L);
        worker.finish(source, new Result(Outcome.CLOSED, "REJECTED"));
        verifyNoInteractions(messages, settlement);
        verify(orders, never()).releaseClosedPaymentLink(link);
    }
}
