package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlChatRepairWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlRepairOutcome.*;
import static com.hunt.otziv.manager_control.service.ManagerControlItemActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlReminderWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.client_messages.api.ClientMessageStateDecisions;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientMessageStateSafety;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlAutomationRepairWorkflow {

    private final ManagerControlChatRepairWorkflow chatRepairWorkflow;

    private final ManagerControlRepairOutcome repairOutcome;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    private final ScheduledClientMessageService scheduledClientMessageService;

    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;

    private final ClientChatMessageReconciliationService clientChatMessageReconciliationService;

    private final ManagerAutomationFailureService managerAutomationFailureService;

    private final com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty deliveryUncertainty;

    private final ManagerControlManualDeliveryRepair manualDeliveryRepair;

    private final ManagerControlReminderRestart reminderRestart;

    static final String PROVIDER_REJECTED_MESSAGE = "Проверено: WhatsApp не доставил это сообщение. Номер менеджера, похоже, больше не состоит в группе клиента "
                            + "или ссылка-приглашение сброшена. Клиент сообщение не получил, повтор не отправлялся, новые сообщения "
                            + "в эту группу приостановлены. Попросите клиента снова добавить номер или прислать новую ссылку, "
                            + "сохраните её в карточке компании и нажмите «Починить».";

    ManagerControlConcreteItemResponse repairAutomationFailureConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        Manager manager = control.getManager();
        Optional<ManagerAutomationFailureService.AutomationFailureIssue> currentIssue = managerAutomationFailureService.findIssue(manager, concreteItem.getEntityType(), concreteItem.getEntityId());
        if (currentIssue.isEmpty()) {
            return resolveRepairedConcreteItem(concreteItem, control, "Ошибка автоматизации уже устранена, карточка перепроверена", principal, "Перепроверена устраненная ошибка автоматизации");
        }
        ManagerAutomationFailureService.AutomationFailureIssue issue = currentIssue.get();
        ScheduledClientMessageState uncertainState = scheduledClientMessageStateRepository.findById(issue.stateId()).orElse(null);
        if (ClientMessageStateDecisions.isQueuedAfterWhatsAppRefusal(uncertainState)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REFUSAL_QUEUED);
        }
        if (uncertainState != null
                && com.hunt.otziv.client_messages.service.ClientMessageStateSafety.ARCHIVE_CHECK_UNDECIDED.equals(uncertainState.getLastErrorCode())) {
            // Nothing to restart: the check cannot read the chat. The manager archives the order or keeps working.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "«Починить» здесь не поможет: автоархив не может прочитать переписку клиента. "
                            + "Откройте чат: если клиент молчит — переведите заказ в архив вручную, если общается — продолжайте работу.");
        }
        ClientMessageScenario failedScenario = uncertainState == null ? null : uncertainState.getScenario();
        boolean failedScenarioOff = failedScenario != null && scheduledClientMessageService.scenarioStoppedBySettings(failedScenario);
        // A reminder of a scenario switched off is not restarted: the autoresponder would postpone it again at once.
        var restarted = failedScenarioOff ? Optional.<ManagerControlConcreteItemResponse>empty()
                : reminderRestart.repair(concreteItem.getId(), issue.stateId(), control.managerId(), principal);
        if (restarted.isPresent()) return restarted.get();
        Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recovered = recoverUncertainBadReviewDelivery(issue, manager);
        if (recovered.isPresent()) {
            ScheduledClientMessageService.RecoveredBadReviewDeliveryResult result = recovered.get();
            return resolveRepairedConcreteItem(concreteItem, control, result.message(), principal, result.retryScheduled() ? "Подтверждена старая отправка счета и запланирована безопасная новая" : "Подтверждена и закрыта зависшая отправка счета");
        }
        if (uncertainState != null && uncertainState.getScenario() != ClientMessageScenario.BAD_REVIEW_INVOICE
                && (ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(uncertainState.getLastErrorCode())
                    || ClientMessageStateSafety.MANUAL_DELIVERY_HANDOFF_PENDING.equals(uncertainState.getLastErrorCode()))) {
            var receipt = scheduledClientMessageService.recoverUncertainOrdinaryDelivery(issue.stateId());
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.UNSENT_CONFIRMED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, REFUSAL_QUEUED);
            }
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.HISTORY_UNAVAILABLE) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Сверка временно недоступна. Система продолжит проверять сохранённую отправку после восстановления WhatsApp. Повторное сообщение заблокировано.");
            }
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.HISTORY_CHECKED_NO_RECEIPT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        UNCONFIRMED_DECIDE);
            }
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.PROVIDER_REJECTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, PROVIDER_REJECTED_MESSAGE);
            }
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.UNRESOLVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        UNCONFIRMED_DECIDE);
            }
            if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED) {
                if (managerAutomationFailureService.findIssue(manager, concreteItem.getEntityType(), concreteItem.getEntityId()).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Отправка подтверждена по квитанции, но у карточки осталась другая ошибка. Обновите список замечаний.");
                }
                return resolveRepairedConcreteItem(concreteItem, control,
                        "Предыдущая отправка подтверждена по WhatsApp; повторного сообщения клиенту не было",
                        principal, "Подтверждена зависшая отправка по квитанции WhatsApp");
            }
        }
        if (failedScenarioOff) {
            // Reconciling an earlier send above still ran; a new attempt would only be postponed as «automation_paused»
            // and the card would close with a false «перезапущена».
            throw new ResponseStatusException(HttpStatus.CONFLICT, switchedOff(
                    scheduledClientMessageService.scenarioStopReason(failedScenario), failedScenario.name()));
        }
        ensureAutomationChatReady(issue);
        ScheduledClientMessageService.ManualRetryResult retry = scheduledClientMessageService.retryNow(issue.stateId());
        Optional<ManagerAutomationFailureService.AutomationFailureIssue> remaining = managerAutomationFailureService.findIssue(manager, concreteItem.getEntityType(), concreteItem.getEntityId());
        if (remaining.isPresent()) {
            String retryError = safe(retry.errorMessage());
            if (retryError.isBlank()) {
                retryError = safe(retry.errorCode());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Повторный запуск выполнен, но источник ошибки еще активен" + (retryError.isBlank() ? "" : ": " + limit(retryError, 220)));
        }
        return resolveRepairedConcreteItem(concreteItem, control, "Задача автоматизации перезапущена и прошла повторную проверку", principal, "Повторно запущена и восстановлена клиентская автоматизация");
    }

    /** «Починить» does not switch the autoresponder on: it names the switch and the step to do by hand. */
    static String switchedOff(String reason, String scenario) {
        String what = reason == null ? "выключен этот сценарий" : reason;
        return "«Починить» не включает автоответчик: в настройках автоответчика (" + com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.SETTINGS_PAGE + ") "
                + what + ". " + com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.manualAction(scenario) + " " + com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.whoTurnsItOn(what);
    }

    /** No receipt and no text in the chat history: the manager looks at the chat and decides in the card. */
    static final String UNCONFIRMED_DECIDE = "WhatsApp не подтвердил доставку, а в доступной истории чата сообщение не найдено. "
            + "Новое сообщение не отправлялось. Откройте чат клиента и нажмите «Сверка отправки»: "
            + "«Сообщение есть в чате», «Сообщения нет — отправить снова» или «Не отправлять».";

    private static final String REFUSAL_QUEUED = "Не отправлено. Шлюз отказал до передачи сообщения. Автоответчик проверит заказ в рабочее время. Ручная отправка не нужна.";

    Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recoverUncertainBadReviewDelivery(ManagerAutomationFailureService.AutomationFailureIssue issue, Manager manager) {
        if (issue == null || issue.stateId() == null) {
            return Optional.empty();
        }
        ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(issue.stateId()).orElse(null);
        if (state == null || state.getScenario() != ClientMessageScenario.BAD_REVIEW_INVOICE || !ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())) {
            return Optional.empty();
        }
        var recovered = scheduledClientMessageService.recoverUncertainBadReviewInvoiceDelivery(state.getId(), false);
        if (recovered.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Исход отправки счёта пока не подтверждён квитанцией исходной операции. Совпадения текста недостаточно; новое сообщение не отправлялось.");
        return recovered;
    }

    boolean verifyUncertainDeliveryInWhatsAppChat(Manager manager, ScheduledClientMessageState state) {
        Company company = automationFailureCompany(state);
        if (company == null || safe(company.getGroupId()).isBlank() || safe(state.getDeliveryMessage()).isBlank()) {
            return false;
        }
        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (!isWhatsAppChat(chat)) {
            return false;
        }
        LocalDateTime from = state.getDeliveryPreparedAt() != null ? state.getDeliveryPreparedAt() : state.getLastAttemptAt();
        try {
            return clientChatMessageReconciliationService.reconcileWhatsAppGroupContainsOutgoingText(manager, company.getGroupId(), from, state.getDeliveryMessage());
        } catch (RuntimeException e) {
            log.warn("Не удалось сверить зависшую отправку счета по истории WhatsApp stateId={} companyId={}", state.getId(), company.getId(), e);
            return false;
        }
    }

    void ensureAutomationChatReady(ManagerAutomationFailureService.AutomationFailureIssue issue) {
        chatRepairWorkflow.ensureAutomationChatReady(issue);
    }

    Company automationFailureCompany(ScheduledClientMessageState state) {
        return chatRepairWorkflow.automationFailureCompany(state);
    }

    ManagerControlConcreteItemResponse repairOrderAutomationConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Order order, Principal principal) {
        var restartScenario = scheduledClientMessageService.orderAutomationScenario(order);
        // A scenario switched off in the settings is not restarted: the check below answers why.
        if (restartScenario.isPresent() && !scheduledClientMessageService.scenarioStoppedBySettings(restartScenario.get())) {
            var candidate = currentOrderAutomationState(order, restartScenario.get(), scheduledStatesByOrderId(List.of(order)));
            if (candidate != null) {
                var restarted = reminderRestart.repair(concreteItem.getId(), candidate.getId(), control.managerId(), principal);
                if (restarted.isPresent()) return restarted.get();
            }
        }
        var manual = manualDeliveryRepair.repair(concreteItem.getId(), order.getId(), control.managerId());
        if (manual.isPresent()) return manual.get();
        Optional<ClientMessageScenario> currentScenario = scheduledClientMessageService.orderAutomationScenario(order);
        if (currentScenario.isPresent()) {
            var current = currentOrderAutomationState(order, currentScenario.get(), scheduledStatesByOrderId(List.of(order)));
            if (current != null && deliveryUncertainty.requiresReconciliation(current.getId())) {
                var receipt = scheduledClientMessageService.recoverUncertainOrdinaryDelivery(current.getId());
                if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.SENT_CONFIRMED)
                    return repairOutcome.recordConfirmedContact(concreteItem, control,
                            "Предыдущая отправка подтверждена по квитанции; новое сообщение клиенту не отправлялось. Ожидаем ответ клиента, повторная проверка через 2 дня",
                            principal, "Подтверждена существующая отправка заказа", current.getTargetKey());
                if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.HISTORY_UNAVAILABLE)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Сверка временно недоступна. Система продолжит проверять сохранённую отправку после восстановления WhatsApp. Повторное сообщение заблокировано.");
                if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.PROVIDER_REJECTED)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, PROVIDER_REJECTED_MESSAGE);
                if (receipt == ScheduledClientMessageService.OrdinaryDeliveryRecoveryResult.UNSENT_CONFIRMED)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Подтверждено, что прежнее сообщение не отправлено. Исход сохранён; очередь повторно проверит эту же операцию.");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Исходная отправка пока не подтверждена. Система продолжит сверку автоматически; повторное сообщение заблокировано. Замечание останется открытым до проверяемого результата.");
            }
        }
        if (currentScenario.isPresent() && scheduledClientMessageService.scenarioStoppedBySettings(currentScenario.get())) {
            // Restarting it would report «восстановлена» while the autoresponder postpones it again a minute later.
            throw new ResponseStatusException(HttpStatus.CONFLICT, switchedOff(
                    scheduledClientMessageService.scenarioStopReason(currentScenario.get()), currentScenario.get().name()));
        }
        if (scheduledClientMessageService.orderAutomationClosedByAdministrator(order)) {
            // The administrator's decision stands: the card closes without reopening the task.
            return resolveRepairedConcreteItem(concreteItem, control,
                    "Задача автоответчика по заказу закрыта или отключена администратором в мониторинге; повторно не включается",
                    principal, "Карточка закрыта: задача закрыта администратором");
        }
        Optional<ClientMessageScenario> scenario = scheduledClientMessageService.ensureOrderAutomationForOrder(order);
        if (scenario.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Для текущего статуса заказа нет автоматической очереди, которую можно восстановить");
        }
        ScheduledClientMessageState state = currentOrderAutomationState(order, scenario.get(), scheduledStatesByOrderId(List.of(order)));
        if (!clientTextReminderIsHealthy(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автоответчик не удалось починить: " + clientTextReminderProblem(state));
        }
        return resolveRepairedConcreteItem(concreteItem, control, "Очередь " + scenario.get().name() + " восстановлена, автоответчик продолжит работу", principal, "Восстановлена очередь " + scenario.get().name());
    }

    ManagerControlConcreteItemResponse resolveRepairedConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment) {
        return repairOutcome.resolveRepairedConcreteItem(concreteItem, control, comment, principal, eventComment);
    }

    boolean isWhatsAppChat(String chat) {
        return orderAutomationDiagnostics.isWhatsAppChat(chat);
    }

    Map<Long, List<ScheduledClientMessageState>> scheduledStatesByOrderId(List<Order> orders) {
        return orderAutomationDiagnostics.scheduledStatesByOrderId(orders);
    }

    ScheduledClientMessageState currentOrderAutomationState(Order order, ClientMessageScenario scenario, Map<Long, List<ScheduledClientMessageState>> statesByOrderId) {
        return orderAutomationDiagnostics.currentOrderAutomationState(order, scenario, statesByOrderId);
    }

    boolean clientTextReminderIsHealthy(ScheduledClientMessageState state) {
        return orderAutomationDiagnostics.clientTextReminderIsHealthy(state);
    }

    String clientTextReminderProblem(ScheduledClientMessageState state) {
        return orderAutomationDiagnostics.clientTextReminderProblem(state);
    }

    String limit(String value, int maxLength) {
        return problemExamples.limit(value, maxLength);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
