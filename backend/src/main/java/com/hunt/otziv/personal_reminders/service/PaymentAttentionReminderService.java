package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.client_messages.api.PaymentDeadlineNoticeSource;
import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader;
import com.hunt.otziv.personal_reminders.api.SystemReminderCommands;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates an internal card from owner-owned snapshots, without changing the payment flow. */
@Service
@RequiredArgsConstructor
public class PaymentAttentionReminderService {
    private static final String SOURCE = PersonalReminderService.SOURCE_PAYMENT_ATTENTION;

    private final PaymentDeadlineNoticeSource deadlines;
    private final PaymentAttentionOrderReader orders;
    private final PersonalReminderRepository reminders;
    private final SystemReminderCommands commands;

    @Transactional
    public void remindIfDue(long stateId, LocalDateTime now) {
        var deadline = deadlines.dueWithin(stateId, now, now.plusDays(2)).orElse(null);
        if (deadline == null || reminders.existsBySourceTypeAndSourceId(SOURCE, stateId)) return;
        var contact = orders.awaitingPayment(deadline.orderId()).orElse(null);
        if (contact == null) return;

        String companyTitle = contact.companyTitle() == null ? "" : contact.companyTitle().trim();
        if (companyTitle.isEmpty()) companyTitle = "Компания";
        String chatUrl = contact.chatUrl() == null ? "" : contact.chatUrl().trim();
        String chatLine = chatUrl.startsWith("https://") || chatUrl.startsWith("http://")
                ? "\nЧат: " + chatUrl : "";
        commands.ensureOpenDueNow(new SystemReminderCommands.Reminder(
                contact.managerUserId(), limit("Проверьте оплату: " + companyTitle, 120),
                limit("Внимание! Скоро компания " + companyTitle
                        + " перейдёт в «Не оплачено».\nЗаказ #" + deadline.orderId()
                        + "\nПроверьте, получил ли клиент счёт, и свяжитесь с ним."
                        + chatLine, 1000), SOURCE, stateId, deadline.orderId()));
    }

    @Transactional
    public void closeIfNoLongerAwaitingPayment(long reminderId) {
        PersonalReminder reminder = reminders.findById(reminderId).orElse(null);
        if (reminder == null || !SOURCE.equals(reminder.getSourceType())
                || reminder.getCompletedAt() != null) return;
        if (reminder.getSourceId() == null || !deadlines.isActive(reminder.getSourceId())
                || reminder.getSourceOrderId() == null
                || orders.awaitingPayment(reminder.getSourceOrderId()).isEmpty()) {
            reminder.setCompletedAt(Instant.now());
            reminders.save(reminder);
        }
    }

    private String limit(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 1).trim() + "…";
    }
}