package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observes the payment schedule; it never sends client messages or changes order status. */
@Service
@RequiredArgsConstructor
public class PaymentAttentionReminderService {
    private static final Set<String> AWAITING_PAYMENT = Set.of("Выставлен счет", "Напоминание");
    private static final String SOURCE = PersonalReminderService.SOURCE_PAYMENT_ATTENTION;

    private final ScheduledClientMessageStateRepository stateRepository;
    private final OrderRepository orderRepository;
    private final PersonalReminderRepository reminderRepository;

    @Transactional
    public void remindIfDue(Long stateId, LocalDateTime now) {
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElse(null);
        if (state == null || state.getScenario() != ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
                || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || state.getNextAttemptAt() == null || !state.getNextAttemptAt().isAfter(now)
                || state.getNextAttemptAt().isAfter(now.plusDays(2))
                || state.getOrderId() == null || reminderRepository.existsBySourceTypeAndSourceId(SOURCE, stateId)) {
            return;
        }

        Order order = orderRepository.findById(state.getOrderId()).orElse(null);
        if (order == null || order.getStatus() == null
                || !AWAITING_PAYMENT.contains(order.getStatus().getTitle())) {
            return;
        }
        Company company = order.getCompany();
        Manager manager = order.getManager() != null ? order.getManager()
                : company == null ? null : company.getManager();
        User user = manager == null ? null : manager.getUser();
        if (user == null || !user.isActive()) {
            return;
        }

        String companyTitle = company == null || company.getTitle() == null
                ? "Компания" : company.getTitle().trim();
        if (companyTitle.isEmpty()) companyTitle = "Компания";
        String chatUrl = company == null || company.getUrlChat() == null ? "" : company.getUrlChat().trim();
        String chatLine = chatUrl.startsWith("https://") || chatUrl.startsWith("http://")
                ? "\nЧат: " + chatUrl : "";

        PersonalReminder reminder = new PersonalReminder();
        reminder.setUser(user);
        reminder.setTitle(limit("Проверьте оплату: " + companyTitle, 120));
        reminder.setText(limit("Внимание! Скоро компания " + companyTitle
                + " перейдёт в «Не оплачено».\nЗаказ #" + order.getId()
                + "\nПроверьте, получил ли клиент счёт, и свяжитесь с ним."
                + chatLine, 1000));
        reminder.setReminderMode("datetime");
        reminder.setRemindAt(Instant.now());
        reminder.setSourceType(SOURCE);
        reminder.setSourceId(stateId);
        reminder.setSourceOrderId(order.getId());
        reminderRepository.saveAndFlush(reminder);
    }

    @Transactional
    public void closeIfNoLongerAwaitingPayment(Long reminderId) {
        PersonalReminder reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null || !SOURCE.equals(reminder.getSourceType())
                || reminder.getCompletedAt() != null) {
            return;
        }
        Order order = reminder.getSourceOrderId() == null ? null
                : orderRepository.findById(reminder.getSourceOrderId()).orElse(null);
        ScheduledClientMessageState state = reminder.getSourceId() == null ? null
                : stateRepository.findById(reminder.getSourceId()).orElse(null);
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE
                || order == null || order.getStatus() == null
                || !AWAITING_PAYMENT.contains(order.getStatus().getTitle())) {
            reminder.setCompletedAt(Instant.now());
            reminderRepository.save(reminder);
        }
    }

    @Transactional
    public void closeForPaidOrder(Long orderId) {
        if (orderId == null) return;
        var open = reminderRepository.findBySourceTypeAndSourceOrderIdAndCompletedAtIsNull(SOURCE, orderId);
        if (open.isEmpty()) return;
        Instant now = Instant.now();
        open.forEach(reminder -> reminder.setCompletedAt(now));
        reminderRepository.saveAll(open);
    }

    private String limit(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 1).trim() + "…";
    }
}
