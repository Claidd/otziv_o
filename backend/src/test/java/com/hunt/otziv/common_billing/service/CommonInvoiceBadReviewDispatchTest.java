package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.BackgroundDispatchBudget;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

class CommonInvoiceBadReviewDispatchTest {
    private final CommonInvoiceMessageQueue queue = mock();
    private final CommonInvoiceDeliveryService invoices = mock();
    private final ClientMessageDelivery delivery = mock();
    private final BackgroundDispatchBudget budget = mock();
    private final CommonInvoiceMessageWorker worker = new CommonInvoiceMessageWorker(queue, invoices, delivery);
    private final PreparedCommonInvoiceMessage notice = new PreparedCommonInvoiceMessage(374L, null, "sender", "group",
            "Обновлённый счёт", null, false, false, false, "bad-review-invoice:374:2", false, null, 340000);

    @ParameterizedTest
    @ValueSource(strings = {"automation_paused", "outside_business_window", "daily_limit", "rate_limited"})
    void preservesNoticeDuringHoldAndResumesThroughTheSameBudget(String reason) {
        ReflectionTestUtils.setField(worker, "dispatchBudget", budget);
        var first = new CommonInvoiceMessageQueue.Claim(notice.operationId(), 374L, "QUEUED", 0, "first");
        var second = new CommonInvoiceMessageQueue.Claim(notice.operationId(), 374L, "RETRYABLE", 0, "second");
        when(queue.claim()).thenReturn(Optional.of(first), Optional.empty(), Optional.of(second), Optional.empty());
        when(queue.snapshot(notice.operationId())).thenReturn(notice);
        when(invoices.messageDeliveryEnabled()).thenReturn(true);
        when(invoices.queuedMessageMayDispatch(notice)).thenReturn(true);
        when(budget.reserveForSender(BackgroundDispatchBudget.Purpose.BAD_REVIEW_INVOICE, "COMMON_INVOICE", 374L,
                notice.chatCompany(), "sender")).thenReturn(new BackgroundDispatchBudget.Admission(false, reason, reason, null),
                BackgroundDispatchBudget.Admission.allow());
        worker.tick();
        verify(invoices, never()).sendPreparedPaymentMessage(any());
        verify(invoices).finishQueuedMessage(eq(first), eq(notice), argThat(r -> reason.equals(r.errorCode())), eq(true));
        worker.tick();
        verify(invoices, times(1)).sendPreparedPaymentMessage(notice);
    }

    @Test
    void missingHistoryForUnknownSendNeverRepeatsPaymentNotice() {
        var claim = new CommonInvoiceMessageQueue.Claim(notice.operationId(), 374L, "UNKNOWN", 1, "recovery");
        when(queue.claim()).thenReturn(Optional.of(claim), Optional.empty());
        when(queue.snapshot(notice.operationId())).thenReturn(notice);
        when(delivery.recoverRecordedOutcomeFromChat(eq(notice.operationId()), any()))
                .thenReturn(ClientMessageSendResult.failed("receipt_history_unavailable", "История недоступна"));
        worker.tick();
        verify(invoices, never()).sendPreparedPaymentMessage(any());
        verifyNoInteractions(budget);
        verify(invoices).finishQueuedMessage(eq(claim), eq(notice), argThat(r -> !r.sent()), eq(false));
    }
}
