package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyChatBindingPolicy;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read-only diagnostics for current order automation cycles, shared by cards and repair verification.
 * Existing application workflows retain transaction ownership and all authorization checks.
 */
@Service
@RequiredArgsConstructor
public class ManagerControlOrderAutomationDiagnostics {
    static final int WORKER_ORDER_UNCHANGED_DAYS = 2;
    private final OrderRepository orderRepository;
    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;

    boolean isWhatsAppChat(String chat) {
        return chat.startsWith("chat.whatsapp.com/")
                || chat.startsWith("https://chat.whatsapp.com/")
                || chat.startsWith("http://chat.whatsapp.com/");
    }

    boolean isTelegramChat(String chat) {
        if (chat.contains("startgroup=")) {
            return false;
        }
        return chat.startsWith("t.me/")
                || chat.startsWith("https://t.me/")
                || chat.startsWith("http://t.me/")
                || chat.startsWith("telegram.me/")
                || chat.startsWith("https://telegram.me/")
                || chat.startsWith("http://telegram.me/")
                || chat.startsWith("telegram.dog/")
                || chat.startsWith("https://telegram.dog/")
                || chat.startsWith("http://telegram.dog/")
                || chat.startsWith("tg://resolve?");
    }

    boolean isMaxChat(String chat) {
        return chat.startsWith("max.ru/")
                || chat.startsWith("https://max.ru/")
                || chat.startsWith("http://max.ru/")
                || chat.startsWith("web.max.ru/")
                || chat.startsWith("https://web.max.ru/")
                || chat.startsWith("http://web.max.ru/");
    }

    List<Order> workerStaleOrdersForControl(List<Long> workerIds, String status, LocalDate today) {
        return workerStaleOrderEntriesForControl(workerIds, status, today).stream()
                .map(WorkerOrderControlEntry::order)
                .toList();
    }

    List<WorkerOrderControlEntry> workerStaleOrderEntriesForControl(List<Long> workerIds, String status, LocalDate today) {
        if (workerIds.isEmpty()) {
            return List.of();
        }
        LocalDate cutoff = managerControlWorkerOrderOverdueDate(today);
        List<Order> orders = "Новый".equals(status)
                ? orderRepository.findManagerControlWorkerNewOrdersForControl(workerIds, cutoff)
                : orderRepository.findManagerControlWorkerStaleOrders(workerIds, status, cutoff);
        Map<Long, List<ScheduledClientMessageState>> statesByOrderId = scheduledStatesByOrderId(orders);
        return orders.stream()
                .map(order -> new WorkerOrderControlEntry(
                        order,
                        workerOrderClientTextDecision(order, status, today, statesByOrderId)
                ))
                .filter(entry -> entry.clientTextDecision().include())
                .toList();
    }

    WorkerClientTextDecision workerOrderClientTextDecision(
            Order order,
            String status,
            LocalDate today,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null
                || !"Новый".equals(status)
                || !order.isWaitingForClient()) {
            return WorkerClientTextDecision.includeDefault();
        }

        long days = daysSince(clientTextWaitingControlDate(order), today);
        if (days > ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS) {
            return new WorkerClientTextDecision(
                    true,
                    "Клиент не прислал текст больше "
                            + ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS
                            + " дн., но заказ все еще отмечен как «ждет клиента». Решение: снимите статус \"ждет клиента\"."
            );
        }

        String bindingProblem = clientTextChatBindingProblem(order.getCompany());
        if (!bindingProblem.isBlank()) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: "
                            + bindingProblem + ". Если доступна кнопка «Починить», сначала нажмите ее. "
                            + "Если починка недоступна или не помогла, проверьте привязку чата или отправьте запрос вручную."
            );
        }

        ScheduledClientMessageState state = currentClientTextReminderState(order, statesByOrderId);
        if (state == null) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: нет записи в очереди CLIENT_TEXT_REMINDER."
            );
        }
        if (!clientTextReminderIsHealthy(state)) {
            return new WorkerClientTextDecision(
                    true,
                    "Заказ ждет текст клиента, но автоответчик не отправляет напоминания: "
                            + clientTextReminderProblem(state) + "."
            );
        }

        return WorkerClientTextDecision.suppress();
    }

    LocalDate clientTextWaitingControlDate(Order order) {
        if (order == null) {
            return LocalDate.now();
        }
        if (order.getChanged() != null) {
            return order.getChanged();
        }
        if (order.getWaitingForClientChangedAt() != null) {
            return order.getWaitingForClientChangedAt().toLocalDate();
        }
        return LocalDate.now();
    }

    Map<Long, List<ScheduledClientMessageState>> scheduledStatesByOrderId(List<Order> orders) {
        List<Long> orderIds = orders == null
                ? List.of()
                : orders.stream()
                .filter(order -> order != null && order.getId() != null)
                .map(Order::getId)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return scheduledClientMessageStateRepository.findByOrderIdIn(orderIds).stream()
                .filter(state -> state.getOrderId() != null)
                .collect(Collectors.groupingBy(ScheduledClientMessageState::getOrderId));
    }

    ScheduledClientMessageState currentClientTextReminderState(
            Order order,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null || order.getId() == null) {
            return null;
        }
        String targetKey = clientTextWaitingTargetKey(order);
        return statesByOrderId.getOrDefault(order.getId(), List.of()).stream()
                .filter(state -> state.getScenario() == ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .filter(state -> Objects.equals(targetKey, state.getTargetKey()))
                .max(Comparator
                        .comparingInt(this::clientTextReminderStatePriority)
                        .thenComparing(this::clientTextReminderStateActivity, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(state -> state.getId() == null ? 0L : state.getId()))
                .orElse(null);
    }

    ScheduledClientMessageState currentOrderAutomationState(
            Order order,
            ClientMessageScenario scenario,
            Map<Long, List<ScheduledClientMessageState>> statesByOrderId
    ) {
        if (order == null || order.getId() == null || scenario == null) {
            return null;
        }
        String targetKey = orderTargetKey(order);
        return statesByOrderId.getOrDefault(order.getId(), List.of()).stream()
                .filter(state -> state.getScenario() == scenario)
                .filter(state -> Objects.equals(targetKey, state.getTargetKey()))
                .max(Comparator
                        .comparingInt(this::clientTextReminderStatePriority)
                        .thenComparing(this::clientTextReminderStateActivity, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(state -> state.getId() == null ? 0L : state.getId()))
                .orElse(null);
    }

    int clientTextReminderStatePriority(ScheduledClientMessageState state) {
        if (state == null) {
            return 0;
        }
        if (!safe(state.getLastErrorCode()).isBlank() && state.getConsecutiveFailures() > 0) {
            return 40;
        }
        if (state.getLastSuccessAt() != null || state.getSentCount() > 0) {
            return 30;
        }
        if (state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getNextAttemptAt() != null) {
            return 20;
        }
        return 10;
    }

    LocalDateTime clientTextReminderStateActivity(ScheduledClientMessageState state) {
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
    }

    boolean clientTextReminderIsHealthy(ScheduledClientMessageState state) {
        if (state == null) {
            return false;
        }
        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED || state.getStatus() == ScheduledMessageStateStatus.PAUSED) {
            return false;
        }
        String errorCode = safe(state.getLastErrorCode()).toLowerCase(Locale.ROOT);
        if (!errorCode.isBlank()
                && !errorCode.contains("dry_run")
                && !errorCode.contains("client_text_received")
                && !errorCode.contains("client_text_cycle_changed")
                && !errorCode.contains("order_status_changed")
                && !errorCode.contains("status_change")) {
            return false;
        }
        return state.getSentCount() > 0
                || state.getLastSuccessAt() != null
                || (state.getStatus() == ScheduledMessageStateStatus.ACTIVE && state.getNextAttemptAt() != null);
    }

    String clientTextReminderProblem(ScheduledClientMessageState state) {
        if (state == null) {
            return "нет записи в очереди CLIENT_TEXT_REMINDER";
        }
        if (!safe(state.getLastErrorMessage()).isBlank()) {
            return state.getLastErrorMessage();
        }
        if (!safe(state.getLastErrorCode()).isBlank()) {
            return "ошибка " + state.getLastErrorCode();
        }
        if (state.getStatus() == ScheduledMessageStateStatus.DISABLED) {
            return "очередь автоответчика отключена";
        }
        if (state.getStatus() == ScheduledMessageStateStatus.PAUSED) {
            return "очередь автоответчика на паузе";
        }
        return "нет активной успешной или запланированной отправки";
    }

    String clientTextWaitingTargetKey(Order order) {
        return "client-text:" + order.getId() + ":" + clientTextWaitingChangedAt(order).withNano(0);
    }

    String orderTargetKey(Order order) {
        return "order:" + order.getId() + ":" + orderStatusChangedAt(order).withNano(0);
    }

    LocalDateTime clientTextWaitingChangedAt(Order order) {
        if (order.getWaitingForClientChangedAt() != null) {
            return order.getWaitingForClientChangedAt();
        }
        if (order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt();
        }
        if (order.getChanged() != null) {
            return order.getChanged().atStartOfDay();
        }
        return LocalDateTime.now().withNano(0);
    }

    LocalDateTime orderStatusChangedAt(Order order) {
        if (order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt();
        }
        if (order.getChanged() != null) {
            return order.getChanged().atStartOfDay();
        }
        if (order.getCreated() != null) {
            return order.getCreated().atStartOfDay();
        }
        return LocalDateTime.now().withNano(0);
    }

    String clientTextChatBindingProblem(Company company) {
        if (company == null) {
            return "компания не найдена";
        }
        if (!CompanyChatBindingPolicy.isRequired(company)) {
            return "";
        }
        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (chat.isBlank()) {
            return "у компании не указан чат";
        }
        if (isWhatsAppChat(chat) && safe(company.getGroupId()).isBlank()) {
            return "WhatsApp-группа не привязана к боту";
        }
        if (isTelegramChat(chat) && company.getTelegramGroupChatId() == null) {
            return "Telegram-группа не привязана к боту";
        }
        if (isMaxChat(chat) && company.getMaxGroupChatId() == null) {
            return "MAX-группа не привязана к боту";
        }
        if (!isWhatsAppChat(chat) && !isTelegramChat(chat) && !isMaxChat(chat)) {
            return "чат компании не распознан";
        }
        return "";
    }

    LocalDate managerControlWorkerOrderOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(WORKER_ORDER_UNCHANGED_DAYS);
    }

    record WorkerClientTextDecision(
            boolean include,
            String reason
    ) {
        private static WorkerClientTextDecision includeDefault() {
            return new WorkerClientTextDecision(true, null);
        }

        private static WorkerClientTextDecision suppress() {
            return new WorkerClientTextDecision(false, null);
        }
    }

    record WorkerOrderControlEntry(
            Order order,
            WorkerClientTextDecision clientTextDecision
    ) {
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private long daysSince(LocalDate date, LocalDate today) {
        return date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(date, today));
    }
}
