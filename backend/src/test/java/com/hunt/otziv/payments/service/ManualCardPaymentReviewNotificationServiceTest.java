package com.hunt.otziv.payments.service;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualCardPaymentReviewNotificationServiceTest {

    @Mock private UserService userService;
    @Mock private PersonalReminderService personalReminderService;
    @Mock private TelegramService telegramService;

    @InjectMocks
    private ManualCardPaymentReviewNotificationService service;

    @Test
    void notifiesActiveOwnersAndAdminsOnceWithReasonAndBankResult() {
        User owner = user(10L, 100L, true);
        User duplicateOwnerAdmin = user(20L, 200L, true);
        User inactiveAdmin = user(30L, 300L, false);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(owner, duplicateOwnerAdmin));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(duplicateOwnerAdmin, inactiveAdmin));

        service.notifyAfterCommit(new ManualCardPaymentReviewNotificationService.ReviewRequest(
                9001L,
                25047L,
                "Мастер на дом",
                100_000L,
                "manager@example.ru",
                "Клиент оплатил по номеру телефона",
                5208L,
                "8959416400",
                "CANCELED",
                "CANCELED"
        ));

        verify(personalReminderService, times(1)).createSystemReminderDueNow(
                eq(owner),
                eq("Проверить ручную оплату заказа №25047"),
                contains("Причина: Клиент оплатил по номеру телефона"),
                eq(ManualCardPaymentReviewNotificationService.REMINDER_SOURCE),
                eq(9001L),
                eq(25047L)
        );
        verify(personalReminderService, times(1)).createSystemReminderDueNow(
                eq(duplicateOwnerAdmin),
                eq("Проверить ручную оплату заказа №25047"),
                contains("T-Bank: ссылка №5208, статус CANCELED"),
                eq(ManualCardPaymentReviewNotificationService.REMINDER_SOURCE),
                eq(9001L),
                eq(25047L)
        );
        verify(telegramService).sendMessage(eq(100L), contains("PaymentId …6400"));
        verify(telegramService).sendMessage(eq(200L), contains("Проверьте поступление в выписке"));
    }

    private User user(Long id, Long telegramChatId, boolean active) {
        User user = new User();
        user.setId(id);
        user.setTelegramChatId(telegramChatId);
        user.setActive(active);
        return user;
    }
}
