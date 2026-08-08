package com.hunt.otziv.payments.service;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualCardPaymentReviewNotificationServiceTest {

    @Mock private UserService userService;
    @Mock private PersonalReminderService personalReminderService;
    @Mock private TelegramService telegramService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    @InjectMocks
    private ManualCardPaymentReviewNotificationService service;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

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

    @Test
    void notifiesOwnersAndAdminsAboutCommonInvoiceCardPayment() {
        User owner = user(10L, 100L, true);
        User admin = user(20L, 200L, true);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(owner));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));

        service.notifyCommonInvoiceAfterCommit(
                new ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest(
                        171L,
                        "Аделанте",
                        640_000L,
                        "manager@example.ru",
                        "Клиент перевел всю сумму менеджеру на карту",
                        List.of(23_489L, 23_490L, 23_987L),
                        List.of(4_778L, 3_967L, 4_640L)
                )
        );

        verify(personalReminderService).createSystemReminderDueNow(
                eq(owner),
                eq("Проверить ручную оплату общего счета №171"),
                contains("Сумма вручную: 6400 ₽"),
                eq(ManualCardPaymentReviewNotificationService.COMMON_INVOICE_REMINDER_SOURCE),
                eq(171L),
                eq(23_489L)
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(admin),
                eq("Проверить ручную оплату общего счета №171"),
                contains("Причина: Клиент перевел всю сумму менеджеру на карту"),
                eq(ManualCardPaymentReviewNotificationService.COMMON_INVOICE_REMINDER_SOURCE),
                eq(171L),
                eq(23_489L)
        );
        verify(telegramService).sendMessage(eq(100L), contains("Заказы: №23489, №23490, №23987"));
        verify(telegramService).sendMessage(eq(200L), contains("Закрытые одиночные инструкции"));
    }

    @Test
    void persistsNotificationInNewTransactionAfterFinancialCommit() {
        User admin = user(20L, null, true);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.notifyCommonInvoiceAfterCommit(
                new ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest(
                        171L,
                        "Аделанте",
                        640_000L,
                        "alex",
                        "Клиент оплатил переводом на карту",
                        List.of(23_489L, 23_490L, 23_987L),
                        List.of(4_778L, 3_967L, 4_640L)
                )
        );

        verify(personalReminderService, never()).createSystemReminderDueNow(
                any(), any(), any(), any(), any(), any()
        );
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(transactionStatus);
        verify(personalReminderService).createSystemReminderDueNow(
                eq(admin),
                eq("Проверить ручную оплату общего счета №171"),
                contains("Причина: Клиент оплатил переводом на карту"),
                eq(ManualCardPaymentReviewNotificationService.COMMON_INVOICE_REMINDER_SOURCE),
                eq(171L),
                eq(23_489L)
        );
    }

    private User user(Long id, Long telegramChatId, boolean active) {
        User user = new User();
        user.setId(id);
        user.setTelegramChatId(telegramChatId);
        user.setActive(active);
        return user;
    }
}
