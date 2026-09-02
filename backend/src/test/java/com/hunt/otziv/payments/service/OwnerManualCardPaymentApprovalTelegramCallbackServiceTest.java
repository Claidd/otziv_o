package com.hunt.otziv.payments.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerManualCardPaymentApprovalTelegramCallbackServiceTest {

    @Mock private PaymentLinkService paymentLinkService;
    @Mock private UserService userService;
    @Mock private TelegramService telegramService;
    @Mock private ManualCardPaymentReviewNotificationService notificationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ignoresForeignCallbacks() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();

        assertTrue(service.handle(callback("other:action", 100L, 100L)).isEmpty());

        verifyNoInteractions(paymentLinkService, userService, telegramService, notificationService);
    }

    @Test
    void rejectsPrivateButtonWithoutReadingApproval() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, 100L));

        assertEquals(
                "Подтверждение доступно только в чате назначенного компании менеджера",
                result.orElseThrow()
        );
        verifyNoInteractions(paymentLinkService, userService, telegramService, notificationService);
    }

    @Test
    void activeOwnerCanConfirmAndMessageBecomesTerminal() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, "owner@example.ru", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));
        when(paymentLinkService.approveOwnerManualCardPayment(
                eq(91L), eq("token"), eq(-500L), eq(owner), any(Authentication.class)
        )).thenReturn(new PaymentLinkService.OwnerManualCardPaymentApprovalOutcome(
                91L, 25270L, 5370L, 200_000L, false
        ));
        when(telegramService.editMessageText(
                eq(-500L), eq(17), any(), eq("HTML"), any()
        )).thenReturn(true);

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, -500L));

        assertEquals("Поступление владельцу подтверждено, заказ оплачен", result.orElseThrow());
        verify(notificationService).closeOwnerApprovalReminders(91L);
        verify(telegramService).editMessageText(
                eq(-500L), eq(17),
                org.mockito.ArgumentMatchers.contains("Поступление владельцу подтверждено"),
                eq("HTML"),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void managerCannotUseOwnerButton() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User manager = user(8L, "manager@example.ru", 100L, "ROLE_MANAGER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(manager));

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, -500L));

        assertEquals("Подтвердить поступление может только владелец или администратор", result.orElseThrow());
        verify(paymentLinkService, never()).approveOwnerManualCardPayment(any(), any(), any(), any(), any());
        verifyNoInteractions(telegramService, notificationService);
    }

    @Test
    void ownerWithoutUsernameIsRejectedBeforeAuthenticationIsCreated() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, " ", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, -500L));

        assertEquals("Подтвердить поступление может только владелец или администратор", result.orElseThrow());
        verify(paymentLinkService, never()).approveOwnerManualCardPayment(any(), any(), any(), any(), any());
        verifyNoInteractions(telegramService, notificationService);
    }

    @Test
    void successfulConfirmationSendsTerminalMessageWhenEditReturnsFalse() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, "owner@example.ru", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));
        when(paymentLinkService.approveOwnerManualCardPayment(
                eq(91L), eq("token"), eq(-500L), eq(owner), any(Authentication.class)
        )).thenReturn(new PaymentLinkService.OwnerManualCardPaymentApprovalOutcome(
                91L, 25270L, 5370L, 200_000L, false
        ));
        when(telegramService.editMessageText(
                eq(-500L), eq(17), any(), eq("HTML"), any()
        )).thenReturn(false);

        service.handle(callback("ompa:a:91:token", 100L, -500L));

        verify(telegramService).sendMessage(
                eq(-500L),
                org.mockito.ArgumentMatchers.contains("Поступление владельцу подтверждено"),
                eq("HTML")
        );
    }

    @Test
    void conflictBecomesPermanentGroupMessageWithoutCallbackToken() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, "owner@example.ru", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));
        when(paymentLinkService.approveOwnerManualCardPayment(
                eq(91L), eq("secret-token"), eq(-500L), eq(owner), any(Authentication.class)
        )).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Счёт изменился"));

        Optional<String> result = service.handle(callback("ompa:a:91:secret-token", 100L, -500L));

        assertEquals("Счёт изменился", result.orElseThrow());
        verify(telegramService).sendMessage(
                eq(-500L),
                org.mockito.ArgumentMatchers.argThat(text ->
                        text.contains("Счёт изменился") && !text.contains("secret-token"))
        );
    }

    @Test
    void forbiddenWrongGroupStaysCallbackToastOnly() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, "owner@example.ru", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));
        when(paymentLinkService.approveOwnerManualCardPayment(
                eq(91L), eq("token"), eq(-999L), eq(owner), any(Authentication.class)
        )).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Неверный чат"));

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, -999L));

        assertEquals("Неверный чат", result.orElseThrow());
        verifyNoInteractions(telegramService, notificationService);
    }

    @Test
    void unexpectedFailureStaysCallbackToastOnly() {
        OwnerManualCardPaymentApprovalTelegramCallbackService service = service();
        User owner = user(7L, "owner@example.ru", 100L, "ROLE_OWNER");
        when(userService.findByChatId(100L)).thenReturn(Optional.of(owner));
        when(paymentLinkService.approveOwnerManualCardPayment(
                eq(91L), eq("token"), eq(-500L), eq(owner), any(Authentication.class)
        )).thenThrow(new IllegalStateException("synthetic failure"));

        Optional<String> result = service.handle(callback("ompa:a:91:token", 100L, -500L));

        assertEquals("Не удалось безопасно подтвердить оплату. Повторите позже", result.orElseThrow());
        verifyNoInteractions(telegramService, notificationService);
    }

    private OwnerManualCardPaymentApprovalTelegramCallbackService service() {
        return new OwnerManualCardPaymentApprovalTelegramCallbackService(
                paymentLinkService,
                userService,
                telegramService,
                notificationService
        );
    }

    private CallbackQuery callback(String data, long actorId, long chatId) {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType(chatId > 0 ? "private" : "supergroup");
        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(17);
        org.telegram.telegrambots.meta.api.objects.User from =
                new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(actorId);
        CallbackQuery callback = new CallbackQuery();
        callback.setMessage(message);
        callback.setFrom(from);
        callback.setData(data);
        return callback;
    }

    private User user(Long id, String username, Long chatId, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setTelegramChatId(chatId);
        user.setActive(true);
        user.setRoles(List.of(role));
        return user;
    }
}
