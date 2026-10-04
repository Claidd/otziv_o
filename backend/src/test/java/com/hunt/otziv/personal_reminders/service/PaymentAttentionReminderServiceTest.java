package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.client_messages.api.PaymentDeadlineNoticeSource;
import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader;
import com.hunt.otziv.personal_reminders.api.SystemReminderCommands;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttentionReminderServiceTest {
    @Mock PaymentDeadlineNoticeSource deadlines;
    @Mock PaymentAttentionOrderReader orders;
    @Mock PersonalReminderRepository reminders;
    @Mock SystemReminderCommands commands;
    @InjectMocks PaymentAttentionReminderService service;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void createsOneManagerCardForDeadlineWithinTwoDays() {
        when(deadlines.dueWithin(10L, now, now.plusDays(2)))
                .thenReturn(Optional.of(new PaymentDeadlineNoticeSource.Deadline(10L, 20L)));
        when(orders.awaitingPayment(20L)).thenReturn(Optional.of(
                new PaymentAttentionOrderReader.Contact(32L, "Калейдоскоп", "https://chat.example.test/company")));

        service.remindIfDue(10L, now);

        var saved = ArgumentCaptor.forClass(SystemReminderCommands.Reminder.class);
        verify(commands).ensureOpenDueNow(saved.capture());
        assertEquals(32L, saved.getValue().recipientUserId());
        assertEquals("PAYMENT_ATTENTION", saved.getValue().sourceType());
        assertEquals(10L, saved.getValue().sourceId());
        assertEquals(20L, saved.getValue().sourceOrderId());
        assertTrue(saved.getValue().text().contains("Проверьте, получил ли клиент счёт"));
        assertTrue(saved.getValue().text().contains("Чат: https://chat.example.test/company"));
    }

    @Test
    void closedCardIsNotRecreatedForSamePaymentCycle() {
        when(deadlines.dueWithin(10L, now, now.plusDays(2)))
                .thenReturn(Optional.of(new PaymentDeadlineNoticeSource.Deadline(10L, 20L)));
        when(reminders.existsBySourceTypeAndSourceId("PAYMENT_ATTENTION", 10L)).thenReturn(true);

        service.remindIfDue(10L, now);

        verify(orders, never()).awaitingPayment(anyLong());
        verify(commands, never()).ensureOpenDueNow(any());
    }

    @Test
    void paidOrderCannotCreateAlertAndClosesExistingCard() {
        when(deadlines.dueWithin(10L, now, now.plusDays(2)))
                .thenReturn(Optional.of(new PaymentDeadlineNoticeSource.Deadline(10L, 20L)));
        service.remindIfDue(10L, now);
        verify(commands, never()).ensureOpenDueNow(any());

        PersonalReminder existing = openReminder();
        when(reminders.findById(30L)).thenReturn(Optional.of(existing));
        when(deadlines.isActive(10L)).thenReturn(true);
        service.closeIfNoLongerAwaitingPayment(30L);

        assertNotNull(existing.getCompletedAt());
        verify(reminders).save(existing);
    }

    @Test
    void closesCardWhenItsPaymentCycleWasSuperseded() {
        PersonalReminder existing = openReminder();
        when(reminders.findById(30L)).thenReturn(Optional.of(existing));

        service.closeIfNoLongerAwaitingPayment(30L);

        assertNotNull(existing.getCompletedAt());
        verify(reminders).save(existing);
    }

    private PersonalReminder openReminder() {
        PersonalReminder reminder = new PersonalReminder();
        reminder.setSourceType("PAYMENT_ATTENTION");
        reminder.setSourceId(10L);
        reminder.setSourceOrderId(20L);
        return reminder;
    }
}
