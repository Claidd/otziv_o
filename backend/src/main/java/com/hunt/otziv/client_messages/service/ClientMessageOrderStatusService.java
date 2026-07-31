package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientMessageOrderStatusService {

    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String COMPANY_STATUS_STOP = "На стопе";
    private static final String COMPANY_STATUS_BAN = "Бан";
    public static final int DEFAULT_MANUAL_CONTROL_FAILURE_THRESHOLD = 3;
    public static final int DEFAULT_MANUAL_CONTROL_AFTER_MINUTES = 60;

    private final ScheduledClientMessageStateRepository stateRepository;
    private final AppSettingService appSettingService;
    private final ScheduledClientMessageService scheduledClientMessageService;

    public void enrichOrderList(List<OrderDTOList> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream()
                .map(OrderDTOList::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return;
        }

        Map<Long, List<ScheduledClientMessageState>> statesByOrder = stateRepository.findByOrderIdIn(orderIds).stream()
                .filter(state -> state.getOrderId() != null)
                .collect(Collectors.groupingBy(
                        ScheduledClientMessageState::getOrderId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        orders.forEach(order -> {
            ClientMessageOrderStatusResponse bindingStatus = missingChatBindingStatus(order);
            ClientMessageOrderStatusResponse savedStatus = toResponse(selectRelevantState(
                    order,
                    statesByOrder.get(order.getId())
            ));
            ClientMessageOrderStatusResponse missingStateStatus = savedStatus == null ? missingScheduledStateStatus(order) : null;
            if (bindingStatus == null && savedStatus == null && missingStateStatus != null) {
                savedStatus = recoverMissingScheduledState(order);
                if (savedStatus != null) {
                    missingStateStatus = null;
                }
            }
            order.setClientMessageStatus(bindingStatus != null
                    ? bindingStatus
                    : savedStatus != null ? savedStatus : missingStateStatus);
        });
    }

    private ClientMessageOrderStatusResponse recoverMissingScheduledState(OrderDTOList order) {
        if (order == null || order.getId() == null || order.getId() <= 0) {
            return null;
        }
        try {
            scheduledClientMessageService.recoverMissingClientMessageStateForOrderId(order.getId());
            return latestStatusForOrder(order);
        } catch (Exception e) {
            log.warn("Не удалось автоматически восстановить очередь автоответчика для заказа {}", order.getId(), e);
            return null;
        }
    }

    private ClientMessageOrderStatusResponse latestStatusForOrder(OrderDTOList order) {
        if (order == null || order.getId() == null || order.getId() <= 0) {
            return null;
        }
        return toResponse(selectRelevantState(
                order,
                stateRepository.findByOrderIdIn(List.of(order.getId()))
        ));
    }

    private ScheduledClientMessageState selectRelevantState(
            OrderDTOList order,
            Collection<ScheduledClientMessageState> states
    ) {
        if (states == null || states.isEmpty()) {
            return null;
        }

        return states.stream()
                .filter(Objects::nonNull)
                .filter(state -> isCurrentStateForOrder(order, state))
                .max(Comparator
                        .comparingInt(this::priority)
                        .thenComparing(latestActivity(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(state -> state.getId() == null ? 0L : state.getId()))
                .orElse(null);
    }

    private boolean isCurrentStateForOrder(OrderDTOList order, ScheduledClientMessageState state) {
        if (state.getScenario() != ClientMessageScenario.CLIENT_TEXT_REMINDER) {
            return true;
        }
        if (order == null
                || !order.isWaitingForClient()
                || order.getId() == null
                || order.getWaitingForClientChangedAt() == null) {
            return false;
        }
        String currentTargetKey = "client-text:"
                + order.getId()
                + ":"
                + order.getWaitingForClientChangedAt().withNano(0);
        return currentTargetKey.equals(state.getTargetKey());
    }

    private int priority(ScheduledClientMessageState state) {
        if (state == null) {
            return 0;
        }
        if (isReviewRecoveryHold(state)) {
            return 35;
        }
        if (requiresManualControl(state)) {
            return 40;
        }
        if (state.getLastSuccessAt() != null || state.getSentCount() > 0) {
            return 30;
        }
        if (state.getNextAttemptAt() != null && state.getStatus() == ScheduledMessageStateStatus.ACTIVE) {
            return 20;
        }
        return 10;
    }

    private Function<ScheduledClientMessageState, LocalDateTime> latestActivity() {
        return state -> {
            if (state == null) {
                return null;
            }
            if (state.getUpdatedAt() != null) {
                return state.getUpdatedAt();
            }
            if (state.getLastAttemptAt() != null) {
                return state.getLastAttemptAt();
            }
            if (state.getLastSuccessAt() != null) {
                return state.getLastSuccessAt();
            }
            return state.getNextAttemptAt();
        };
    }

    private ClientMessageOrderStatusResponse toResponse(ScheduledClientMessageState state) {
        if (state == null) {
            return null;
        }

        String statusState;
        String tone;
        String label;

        if (isReviewRecoveryHold(state)) {
            statusState = "waiting_recovery";
            tone = "wait";
            label = "Ждём восстановления отзывов";
        } else if (isRateLimitedWait(state)) {
            statusState = "scheduled";
            tone = "wait";
            label = "Ожидает отправки";
        } else if (requiresManualControl(state)) {
            statusState = "manual_control";
            tone = "danger";
            label = manualControlLabel(state.getLastErrorCode());
        } else if (isSuccessful(state)) {
            statusState = "sent";
            tone = "success";
            label = "Автоответчик отправил";
        } else if (state.getNextAttemptAt() != null && state.getStatus() == ScheduledMessageStateStatus.ACTIVE) {
            statusState = "scheduled";
            tone = "wait";
            label = "Автоответчик запланирован";
        } else {
            statusState = "none";
            tone = "muted";
            label = terminalLabel(state);
        }

        LocalDateTime nextAttemptAt = state.getNextAttemptAt();
        String errorMessage = state.getLastErrorMessage();
        if (isRateLimitedWait(state)) {
            LocalDateTime effectiveNextAttemptAt = scheduledClientMessageService.effectiveNextAttemptAt(nextAttemptAt);
            if (effectiveNextAttemptAt != null) {
                nextAttemptAt = effectiveNextAttemptAt;
            }
            errorMessage = nextAttemptAt == null
                    ? errorMessage
                    : "Следующий слот отправки: " + nextAttemptAt;
        }

        return new ClientMessageOrderStatusResponse(
                statusState,
                label,
                tone,
                state.getScenario() == null ? null : state.getScenario().name(),
                state.getLastErrorCode(),
                errorMessage,
                state.getLastAttemptAt(),
                state.getLastSuccessAt(),
                nextAttemptAt,
                state.getConsecutiveFailures(),
                state.getSentCount()
        );
    }

    private boolean isSuccessful(ScheduledClientMessageState state) {
        if (state.getSentCount() > 0) {
            return true;
        }
        if (state.getLastSuccessAt() == null) {
            return false;
        }
        return state.getLastAttemptAt() == null || !state.getLastSuccessAt().isBefore(state.getLastAttemptAt());
    }

    private boolean isReviewRecoveryHold(ScheduledClientMessageState state) {
        return state != null
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && normalize(state.getLastErrorCode()).equals("review_recovery_active");
    }

    private boolean isRateLimitedWait(ScheduledClientMessageState state) {
        return state != null
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && state.getNextAttemptAt() != null
                && normalize(state.getLastErrorCode()).equals("rate_limited");
    }

    private boolean requiresManualControl(ScheduledClientMessageState state) {
        if (state.getStatus() == ScheduledMessageStateStatus.DONE) {
            return false;
        }

        String code = normalize(state.getLastErrorCode());
        if (code.isBlank()) {
            return false;
        }

        if (isOperationalSkip(code)) {
            return false;
        }

        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED) {
            return true;
        }

        if (isManualFixError(code)) {
            return true;
        }

        return shouldEscalateFailure(state);
    }

    private boolean shouldEscalateFailure(ScheduledClientMessageState state) {
        int threshold = Math.max(1, appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_FAILURE_THRESHOLD,
                DEFAULT_MANUAL_CONTROL_FAILURE_THRESHOLD
        ));
        if (state.getConsecutiveFailures() >= threshold) {
            return true;
        }

        LocalDateTime lastAttemptAt = state.getLastAttemptAt();
        if (lastAttemptAt == null) {
            return false;
        }
        int minutes = Math.max(1, appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_AFTER_MINUTES,
                DEFAULT_MANUAL_CONTROL_AFTER_MINUTES
        ));
        return !lastAttemptAt.plusMinutes(minutes).isAfter(LocalDateTime.now());
    }

    private boolean isManualFixError(String code) {
        return code.equals("whatsapp_group_missing")
                || code.equals("telegram_group_missing")
                || code.equals("max_group_missing")
                || code.equals("chat_platform_unknown")
                || code.equals("whatsapp_client_missing")
                || code.equals("unknown_client")
                || code.equals("missing_client")
                || code.equals("empty_client_url")
                || code.equals("missing_group_id")
                || code.equals("message_empty")
                || code.equals("missing_message")
                || code.equals("company_missing");
    }

    private boolean isOperationalSkip(String code) {
        return code.contains("dry_run")
                || code.contains("rate_limited")
                || code.contains("review_recovery_active")
                || code.contains("order_status_changed")
                || code.contains("status_change")
                || code.contains("auto_archive")
                || code.contains("auto_ban")
                || code.contains("client_message_state_auto_recovered");
    }

    private String manualControlLabel(String errorCode) {
        return switch (normalize(errorCode)) {
            case "whatsapp_group_missing" -> "Контроль: WhatsApp-группа не привязана";
            case "telegram_group_missing" -> "Контроль: Telegram-группа не привязана";
            case "max_group_missing" -> "Контроль: MAX-группа не привязана";
            case "whatsapp_client_missing" -> "Контроль: WhatsApp менеджера не подключен";
            case "chat_platform_unknown" -> "Контроль: чат не распознан";
            case "company_missing" -> "Контроль: компания не найдена";
            default -> "Контроль: автоответчик не отправил";
        };
    }

    private String terminalLabel(ScheduledClientMessageState state) {
        if (state.getStatus() == ScheduledMessageStateStatus.PAUSED) {
            return "Автоответчик на паузе";
        }
        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED) {
            return "Автоответчик отключен";
        }
        return "Автоответчик без отправки";
    }

    private ClientMessageOrderStatusResponse missingChatBindingStatus(OrderDTOList order) {
        if (order == null || !hasText(order.getCompanyUrlChat())) {
            return null;
        }

        String normalizedUrl = order.getCompanyUrlChat().trim().toLowerCase(Locale.ROOT);
        if (isWhatsAppUrl(normalizedUrl) && !hasText(order.getGroupId())) {
            return bindingResponse(order, "whatsapp_group_missing", "Контроль: WhatsApp-группа не привязана");
        }
        if (isTelegramUrl(normalizedUrl) && order.getTelegramGroupChatId() == null) {
            return bindingResponse(order, "telegram_group_missing", "Контроль: Telegram-группа не привязана");
        }
        if (isMaxUrl(normalizedUrl) && order.getMaxGroupChatId() == null) {
            return bindingResponse(order, "max_group_missing", "Контроль: MAX-группа не привязана");
        }

        return null;
    }

    private boolean isTelegramUrl(String normalizedUrl) {
        return normalizedUrl.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalizedUrl.startsWith("tg://resolve?");
    }

    private boolean isWhatsAppUrl(String normalizedUrl) {
        return normalizedUrl.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+");
    }

    private boolean isMaxUrl(String normalizedUrl) {
        return normalizedUrl.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+");
    }

    private ClientMessageOrderStatusResponse bindingResponse(OrderDTOList order, String errorCode, String label) {
        if (chatBindingNotRequired(order)) {
            return new ClientMessageOrderStatusResponse(
                    "not_required",
                    "Привязка чата пока не требуется",
                    "muted",
                    null,
                    null,
                    "Компания в статусе «" + order.getCompanyStatus()
                            + "». Проверка включится автоматически после смены статуса.",
                    null,
                    null,
                    null,
                    0,
                    0
            );
        }
        return manualBindingResponse(errorCode, label);
    }

    private boolean chatBindingNotRequired(OrderDTOList order) {
        String status = normalize(order == null ? null : order.getCompanyStatus());
        return status.equals(normalize(COMPANY_STATUS_STOP)) || status.equals(normalize(COMPANY_STATUS_BAN));
    }

    private ClientMessageOrderStatusResponse manualBindingResponse(String errorCode, String label) {
        return new ClientMessageOrderStatusResponse(
                "manual_control",
                label,
                "danger",
                null,
                errorCode,
                "Сообщение не отправится, пока чат компании не привязан к боту",
                null,
                null,
                null,
                0,
                0
        );
    }

    private ClientMessageOrderStatusResponse missingScheduledStateStatus(OrderDTOList order) {
        if (order == null || !hasText(order.getStatus())) {
            return null;
        }

        String status = order.getStatus().trim();
        int unchangedDays = Math.max(0, (int) order.getDayToChangeStatusAgo());
        if (STATUS_PUBLIC.equals(status) && !order.isCommonInvoice() && unchangedDays >= 1) {
            return missingPaymentInvoiceRetryResponse();
        }

        if (reviewCheckEnabled()
                && listSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES).contains(status)
                && unchangedDays >= reviewCheckIntervalDays()) {
            return missingStateResponse("Проверка отзывов");
        }

        if (paymentReminderEnabled()
                && listSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_STATUSES).contains(status)
                && unchangedDays >= paymentReminderIntervalDays()) {
            return missingStateResponse("Оплата");
        }

        if (clientTextReminderEnabled()
                && order.isWaitingForClient()
                && listSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES).contains(status)
                && clientTextWaitingDays(order, unchangedDays) >= clientTextReminderIntervalDays()) {
            return missingStateResponse("Ожидание текста клиента");
        }

        return null;
    }

    private int clientTextWaitingDays(OrderDTOList order, int fallbackDays) {
        LocalDateTime waitingChangedAt = order == null ? null : order.getWaitingForClientChangedAt();
        if (waitingChangedAt == null) {
            return fallbackDays;
        }
        return Math.max(0, (int) ChronoUnit.DAYS.between(waitingChangedAt, LocalDateTime.now()));
    }

    private ClientMessageOrderStatusResponse missingPaymentInvoiceRetryResponse() {
        return new ClientMessageOrderStatusResponse(
                "manual_control",
                "Контроль: счет не поставлен в очередь",
                "danger",
                "Повтор счета",
                "payment_invoice_retry_missing",
                "Заказ уже опубликован, но нет записи повтора/отправки финального счета",
                null,
                null,
                null,
                0,
                0
        );
    }

    private ClientMessageOrderStatusResponse missingStateResponse(String scenarioLabel) {
        return new ClientMessageOrderStatusResponse(
                "manual_control",
                "Контроль: автоответчик не создан",
                "danger",
                scenarioLabel,
                "client_message_state_missing",
                "Заказ уже подходит под автоответчик, но записи в очереди отправки нет",
                null,
                null,
                null,
                0,
                0
        );
    }

    private boolean reviewCheckEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true);
    }

    private boolean paymentReminderEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true);
    }

    private boolean clientTextReminderEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED, true);
    }

    private int reviewCheckIntervalDays() {
        return intSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS);
    }

    private int paymentReminderIntervalDays() {
        return intSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS);
    }

    private int clientTextReminderIntervalDays() {
        return intSetting(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS);
    }

    private int intSetting(String key, int fallback) {
        return Math.max(1, appSettingService.getInt(key, fallback));
    }

    private List<String> listSetting(String key, String fallbackCsv) {
        return List.of(appSettingService.getString(key, fallbackCsv).split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
