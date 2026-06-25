package com.hunt.otziv.p_products.next_order;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NextOrderFailureNotifier {

    private static final String SOURCE_NEXT_ORDER_FAILED = "NEXT_ORDER_FAILED";

    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;

    public void notifyManager(Order sourceOrder, Manager fallbackManager, String context, Throwable error) {
        User user = notificationUser(sourceOrder, fallbackManager);
        if (user == null || user.getId() == null) {
            return;
        }

        Long orderId = sourceOrder == null ? null : sourceOrder.getId();
        boolean alreadyOpen = false;
        String title = limit("Следующий заказ не создался: " + companyTitle(sourceOrder), 120);
        String text = limit(
                "После оплаты не создался следующий заказ."
                        + "\nКомпания: " + companyTitle(sourceOrder)
                        + "\nЗаказ #" + (orderId == null ? "-" : orderId)
                        + contextLine(context)
                        + "\nОшибка: " + concise(error),
                1000
        );

        try {
            alreadyOpen = personalReminderService.hasOpenSystemReminder(user, SOURCE_NEXT_ORDER_FAILED, orderId);
            personalReminderService.deleteSystemReminderBySource(user, SOURCE_NEXT_ORDER_FAILED, orderId);
            personalReminderService.createSystemReminderDueNow(
                    user,
                    title,
                    text,
                    SOURCE_NEXT_ORDER_FAILED,
                    orderId,
                    orderId
            );
        } catch (RuntimeException e) {
            log.warn("Не удалось создать напоминание менеджеру о сбое следующего заказа {}", orderId, e);
            alreadyOpen = true;
        }

        Long chatId = user.getTelegramChatId();
        if (chatId != null && !alreadyOpen) {
            try {
                telegramService.sendMessage(chatId, text);
            } catch (RuntimeException e) {
                log.warn("Не удалось отправить Telegram менеджеру о сбое следующего заказа {}", orderId, e);
            }
        }
    }

    private User notificationUser(Order order, Manager fallbackManager) {
        Manager manager = order == null ? null : order.getManager();
        if (manager != null && manager.getUser() != null) {
            return manager.getUser();
        }

        Company company = order == null ? null : order.getCompany();
        Manager companyManager = company == null ? null : company.getManager();
        if (companyManager != null && companyManager.getUser() != null) {
            return companyManager.getUser();
        }

        return fallbackManager == null ? null : fallbackManager.getUser();
    }

    private String contextLine(String context) {
        String clean = trim(context);
        return clean.isBlank() ? "" : "\nКонтекст: " + clean;
    }

    private String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        String title = company == null ? "" : trim(company.getTitle());
        return title.isBlank() ? "компания не указана" : title;
    }

    private String concise(Throwable throwable) {
        if (throwable == null) {
            return "неизвестная ошибка";
        }
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        String message = trim(last.getMessage());
        return message.isBlank() ? last.getClass().getSimpleName() : last.getClass().getSimpleName() + ": " + message;
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
