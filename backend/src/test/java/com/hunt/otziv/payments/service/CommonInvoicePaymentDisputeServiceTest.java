package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.api.CommonInvoiceRecipientReceipts;
import com.hunt.otziv.common_billing.api.CommonInvoiceRecipientReceipts.Receipt;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommonInvoicePaymentDisputeServiceTest {

    private static final long DELIVERY = 55L;
    private static final long RECIPIENT_USER = 15L;
    private static final long RECIPIENT_TELEGRAM = 777L;
    private static final long RECIPIENT_GROUP = -10015L;

    private final CommonInvoiceRecipientReceipts receipts = mock(CommonInvoiceRecipientReceipts.class);
    private final SpecialistPaymentReviewDirectory users = mock(SpecialistPaymentReviewDirectory.class);
    private final PaymentIssueReminderService issues = mock(PaymentIssueReminderService.class);
    private final CommonInvoicePaymentDisputeService service =
            new CommonInvoicePaymentDisputeService(receipts, users, issues);

    @Test
    void recipientDisputesOnceInOwnGroupAndStaffGetOneTask() {
        deliveredHoursAgo(2, null);
        when(receipts.markDisputed(DELIVERY, RECIPIENT_TELEGRAM)).thenReturn(true);

        String answer = service.dispute(DELIVERY, RECIPIENT_TELEGRAM, RECIPIENT_GROUP);

        assertTrue(answer.startsWith("Спор сохранён"));
        verify(receipts).markDisputed(DELIVERY, RECIPIENT_TELEGRAM);
        verify(issues).notifyOrderIssueAfterCommit(eq(4_000L), eq(CommonInvoicePaymentDisputeService.DISPUTE_ISSUE),
                eq(DELIVERY), contains("общего счёта №146"), contains("Сумма: 4250 ₽"));
    }

    @Test
    void repeatedPressNeitherMarksNorAlarmsAgain() {
        deliveredHoursAgo(2, LocalDateTime.now().minusHours(1));

        assertTrue(service.dispute(DELIVERY, RECIPIENT_TELEGRAM, RECIPIENT_GROUP).startsWith("Поступление уже оспорено"));

        verify(receipts, never()).markDisputed(anyLong(), anyLong());
        verify(issues, never()).notifyOrderIssueAfterCommit(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentSecondPressLosesTheMarkAndStaysSilent() {
        deliveredHoursAgo(2, null);
        when(receipts.markDisputed(DELIVERY, RECIPIENT_TELEGRAM)).thenReturn(false);

        assertTrue(service.dispute(DELIVERY, RECIPIENT_TELEGRAM, RECIPIENT_GROUP).startsWith("Поступление уже оспорено"));

        verify(issues, never()).notifyOrderIssueAfterCommit(any(), any(), any(), any(), any());
    }

    @Test
    void anotherMemberOfTheGroupCannotDispute() {
        deliveredHoursAgo(2, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.dispute(DELIVERY, 999L, RECIPIENT_GROUP));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(receipts, never()).markDisputed(anyLong(), anyLong());
    }

    @Test
    void buttonForwardedToAnotherChatIsRefused() {
        deliveredHoursAgo(2, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.dispute(DELIVERY, RECIPIENT_TELEGRAM, -20000L));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(receipts, never()).markDisputed(anyLong(), anyLong());
    }

    @Test
    void windowClosesDayAfterDelivery() {
        deliveredHoursAgo(25, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.dispute(DELIVERY, RECIPIENT_TELEGRAM, RECIPIENT_GROUP));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(receipts, never()).markDisputed(anyLong(), anyLong());
    }

    @Test
    void unknownNoticeIsNotFound() {
        when(receipts.find(DELIVERY)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.dispute(DELIVERY, RECIPIENT_TELEGRAM, RECIPIENT_GROUP));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private void deliveredHoursAgo(int hours, LocalDateTime disputedAt) {
        when(receipts.find(DELIVERY)).thenReturn(Optional.of(new Receipt(DELIVERY, 146L, "Пластек - общий счет", 4_000L,
                "SPECIALIST", RECIPIENT_USER, 425_000L, "vika", LocalDateTime.now().minusHours(hours), disputedAt)));
        when(users.activePersonalTelegramChatId(RECIPIENT_USER)).thenReturn(Optional.of(RECIPIENT_TELEGRAM));
        when(users.activePaymentReviewGroupChatId(RECIPIENT_USER, "SPECIALIST")).thenReturn(Optional.of(RECIPIENT_GROUP));
    }
}
