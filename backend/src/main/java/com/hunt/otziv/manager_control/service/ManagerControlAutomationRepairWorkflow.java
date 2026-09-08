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

    ManagerControlConcreteItemResponse repairAutomationFailureConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        Manager manager = control.getManager();
        Optional<ManagerAutomationFailureService.AutomationFailureIssue> currentIssue = managerAutomationFailureService.findIssue(manager, concreteItem.getEntityType(), concreteItem.getEntityId());
        if (currentIssue.isEmpty()) {
            return resolveRepairedConcreteItem(concreteItem, control, "Ошибка автоматизации уже устранена, карточка перепроверена", principal, "Перепроверена устраненная ошибка автоматизации");
        }
        ManagerAutomationFailureService.AutomationFailureIssue issue = currentIssue.get();
        ensureAutomationChatReady(issue);
        Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recovered = recoverUncertainBadReviewDelivery(issue, manager);
        if (recovered.isPresent()) {
            ScheduledClientMessageService.RecoveredBadReviewDeliveryResult result = recovered.get();
            return resolveRepairedConcreteItem(concreteItem, control, result.message(), principal, result.retryScheduled() ? "Подтверждена старая отправка счета и запланирована безопасная новая" : "Подтверждена и закрыта зависшая отправка счета");
        }
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

    Optional<ScheduledClientMessageService.RecoveredBadReviewDeliveryResult> recoverUncertainBadReviewDelivery(ManagerAutomationFailureService.AutomationFailureIssue issue, Manager manager) {
        if (issue == null || issue.stateId() == null) {
            return Optional.empty();
        }
        ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(issue.stateId()).orElse(null);
        if (state == null || state.getScenario() != ClientMessageScenario.BAD_REVIEW_INVOICE || !ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(state.getLastErrorCode())) {
            return Optional.empty();
        }
        boolean chatVerified = verifyUncertainDeliveryInWhatsAppChat(manager, state);
        return scheduledClientMessageService.recoverUncertainBadReviewInvoiceDelivery(state.getId(), chatVerified);
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
