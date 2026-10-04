package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.util.List;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttentionReminderServiceTest {
    @Mock ScheduledClientMessageStateRepository states;
    @Mock OrderRepository orders;
    @Mock PersonalReminderRepository reminders;
    @InjectMocks PaymentAttentionReminderService service;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void createsOneManagerCardWhenPaymentDeadlineIsTwoDaysAway() {
        ScheduledClientMessageState state = state(now.plusDays(2));
        Order order = order("Напоминание");
        when(states.findByIdForUpdate(10L)).thenReturn(Optional.of(state));
        when(orders.findById(20L)).thenReturn(Optional.of(order));

        service.remindIfDue(10L, now);

        ArgumentCaptor<PersonalReminder> saved = ArgumentCaptor.forClass(PersonalReminder.class);
        verify(reminders).saveAndFlush(saved.capture());
        PersonalReminder reminder = saved.getValue();
        assertEquals(32L, reminder.getUser().getId());
        assertEquals("PAYMENT_ATTENTION", reminder.getSourceType());
        assertEquals(10L, reminder.getSourceId());
        assertEquals(20L, reminder.getSourceOrderId());
        assertTrue(reminder.getText().contains("Проверьте, получил ли клиент счёт"));
        assertTrue(reminder.getText().contains("Чат: https://chat.example.test/company"));
        assertNotNull(reminder.getRemindAt());
    }

    @Test
    void manuallyClosedCardIsNotRecreatedForSamePaymentCycle() {
        when(states.findByIdForUpdate(10L)).thenReturn(Optional.of(state(now.plusDays(1))));
        when(reminders.existsBySourceTypeAndSourceId("PAYMENT_ATTENTION", 10L)).thenReturn(true);

        service.remindIfDue(10L, now);

        verify(orders, never()).findById(any());
        verify(reminders, never()).saveAndFlush(any());
    }

    @Test
    void paidOrderCannotCreateAlertAndClosesExistingCard() {
        when(states.findByIdForUpdate(10L)).thenReturn(Optional.of(state(now.plusDays(1))));
        when(orders.findById(20L)).thenReturn(Optional.of(order("Оплачено")));
        service.remindIfDue(10L, now);
        verify(reminders, never()).saveAndFlush(any());

        PersonalReminder existing = new PersonalReminder();
        existing.setSourceType("PAYMENT_ATTENTION");
        existing.setSourceOrderId(20L);
        when(reminders.findById(30L)).thenReturn(Optional.of(existing));
        when(orders.findById(20L)).thenReturn(Optional.of(order("Оплачено")));

        service.closeIfNoLongerAwaitingPayment(30L);

        assertNotNull(existing.getCompletedAt());
        verify(reminders).save(existing);
    }

    @Test
    void closesCardWhenItsPaymentCycleWasSuperseded() {
        PersonalReminder existing = new PersonalReminder();
        existing.setSourceType("PAYMENT_ATTENTION");
        existing.setSourceId(10L);
        existing.setSourceOrderId(20L);
        ScheduledClientMessageState obsolete = state(now.plusDays(1));
        obsolete.setStatus(ScheduledMessageStateStatus.DONE);
        when(reminders.findById(30L)).thenReturn(Optional.of(existing));
        when(states.findById(10L)).thenReturn(Optional.of(obsolete));
        when(orders.findById(20L)).thenReturn(Optional.of(order("Напоминание")));

        service.closeIfNoLongerAwaitingPayment(30L);

        assertNotNull(existing.getCompletedAt());
        verify(reminders).save(existing);
    }

    @Test
    void paidOrderEventClosesOpenCardImmediately() {
        PersonalReminder open = new PersonalReminder();
        when(reminders.findBySourceTypeAndSourceOrderIdAndCompletedAtIsNull("PAYMENT_ATTENTION", 20L))
                .thenReturn(List.of(open));

        service.closeForPaidOrder(20L);

        assertNotNull(open.getCompletedAt());
        verify(reminders).saveAll(List.of(open));
    }

    private ScheduledClientMessageState state(LocalDateTime due) {
        return ScheduledClientMessageState.builder()
                .id(10L).orderId(20L)
                .scenario(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(due).build();
    }

    private Order order(String title) {
        User user = User.builder().id(32L).active(true).build();
        Manager manager = Manager.builder().id(3L).user(user).build();
        Company company = Company.builder().id(214L).title("Калейдоскоп")
                .urlChat("https://chat.example.test/company").build();
        return Order.builder().id(20L).company(company).manager(manager)
                .status(OrderStatus.builder().title(title).build()).build();
    }
}
