package com.hunt.otziv.payments.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerManualCardPaymentApprovalTelegramCallbackService {

    private final PaymentLinkService paymentLinkService;
    private final UserService userService;
    private final TelegramService telegramService;
    private final ManualCardPaymentReviewNotificationService notificationService;

    public Optional<String> handle(CallbackQuery callbackQuery) {
        OwnerManualCardPaymentApprovalCallbackData.Parsed parsed =
                OwnerManualCardPaymentApprovalCallbackData.parse(
                        callbackQuery == null ? null : callbackQuery.getData()
                );
        if (parsed == null) {
            return Optional.empty();
        }
        if (parsed.approvalId() == null || parsed.token().isBlank()) {
            return Optional.of("Запрос подтверждения повреждён");
        }
        if (callbackQuery.getFrom() == null
                || callbackQuery.getFrom().getId() == null
                || callbackQuery.getMessage() == null
                || callbackQuery.getMessage().getMessageId() == null) {
            return Optional.of("Не удалось определить владельца или сообщение");
        }
        long telegramUserId = callbackQuery.getFrom().getId();
        long callbackChatId = callbackQuery.getMessage().getChatId();
        if (callbackChatId != telegramUserId) {
            return Optional.of("Подтверждение доступно только в личном чате владельца");
        }
        User actor = userService.findByChatId(telegramUserId)
                .filter(User::isActive)
                .orElse(null);
        if (!canApprove(actor)) {
            return Optional.of("Подтвердить поступление может только владелец или администратор");
        }

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                actor.getUsername(),
                null,
                actor.getRoles() == null ? List.of() : List.copyOf(actor.getRoles())
        );
        SecurityContext originalContext = SecurityContextHolder.getContext();
        try {
            SecurityContext callbackContext = SecurityContextHolder.createEmptyContext();
            callbackContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(callbackContext);
            PaymentLinkService.OwnerManualCardPaymentApprovalOutcome outcome =
                    paymentLinkService.approveOwnerManualCardPayment(
                            parsed.approvalId(),
                            parsed.token(),
                            actor,
                            authentication
                    );
            try {
                notificationService.closeOwnerApprovalReminders(outcome.approvalId());
                telegramService.editMessageText(
                        callbackChatId,
                        callbackQuery.getMessage().getMessageId(),
                        completedText(outcome),
                        "HTML",
                        null
                );
            } catch (RuntimeException notificationFailure) {
                log.warn(
                        "Оплата владельцу подтверждена, но Telegram/напоминание не обновлено approvalId={}",
                        outcome.approvalId(),
                        notificationFailure
                );
            }
            return Optional.of(outcome.alreadyCompleted()
                    ? "Оплата уже была подтверждена"
                    : "Поступление владельцу подтверждено, заказ оплачен");
        } catch (ResponseStatusException exception) {
            return Optional.of(limit(message(exception), 180));
        } catch (RuntimeException exception) {
            log.warn("Не удалось подтвердить оплату владельцу approvalId={}", parsed.approvalId(), exception);
            return Optional.of("Не удалось безопасно подтвердить оплату. Повторите позже");
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    private boolean canApprove(User actor) {
        if (actor == null || actor.getId() == null || actor.getRoles() == null) {
            return false;
        }
        return actor.getRoles().stream()
                .map(Role::getName)
                .filter(name -> name != null)
                .map(name -> name.trim().toUpperCase(Locale.ROOT))
                .anyMatch(name -> "ROLE_OWNER".equals(name) || "ROLE_ADMIN".equals(name));
    }

    private String completedText(PaymentLinkService.OwnerManualCardPaymentApprovalOutcome outcome) {
        return "<b>✅ Поступление владельцу подтверждено</b>\n\n"
                + "Заказ: <b>№" + outcome.orderId() + "</b>\n"
                + "Сумма: <b>" + rubles(outcome.amountKopecks()) + " ₽</b>\n"
                + "Платёжный источник проверен и закрыт при необходимости. "
                + "Заказ отмечен оплаченным один раз.";
    }

    private String rubles(long kopecks) {
        return java.math.BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString();
    }

    private String message(ResponseStatusException exception) {
        String reason = exception.getReason();
        return reason == null || reason.isBlank()
                ? "Подтверждение отклонено: состояние оплаты изменилось"
                : reason;
    }

    private String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
