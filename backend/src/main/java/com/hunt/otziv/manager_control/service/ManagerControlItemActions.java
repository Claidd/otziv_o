package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlReminderWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDateTime;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlItemActions {

    private final ManagerControlWorkerTaskWorkflow workerTaskWorkflow;

    private final ManagerControlReminderWorkflow reminderWorkflow;

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlSlaPolicy slaPolicy;

    private final ManagerControlConcretePresenter concretePresenter;

    private final ManagerControlWorkerTaskLookup workerTaskLookup;

    static final int MANUAL_FOLLOW_UP_DAYS = 2;

    static final int WORKER_TASK_FOLLOW_UP_HOURS = ManagerControlWorkerExplanationQueries.WORKER_TASK_FOLLOW_UP_HOURS;

    static final String ORDER_STATUS_TO_PAY = "Выставлен счет";

    static final String ORDER_STATUS_REMINDER = "Напоминание";

    private final ManagerPermissionService managerPermissionService;

    private final OrderService orderService;

    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    private final OrderRepository orderRepository;

    private final ManagerControlInvoiceDiagnostics invoiceDiagnostics;

    private final ManagerAutomationFailureService managerAutomationFailureService;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    private final GamificationEventService gamificationEventService;

    void notifyOwnersAboutDeferredConcreteItem(ManagerDailyControl control, ManagerDailyControlConcreteItem concreteItem, Principal principal) {
        reminderWorkflow.notifyOwnersAboutDeferredConcreteItem(control, concreteItem, principal);
    }

    @Transactional
    public void actionItem(Long itemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        if (itemId == null || itemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный пункт контроля");
        }
        ManagerDailyControlItem item = dailyControlItemRepository.findById(itemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пункт контроля не найден"));
        ManagerDailyControl control = item.getControl();
        accessPolicy.requireControlAccess(control, principal, authentication);
        rejectAggregateActionForConcreteItem(item);
        ManagerDailyControlActionType actionType = parseActionType(request == null ? null : request.actionType());
        String comment = limit(request == null ? null : request.comment(), 1000);
        requireCommentIfNeeded(item, actionType, comment);
        ManagerDailyControlItemStatus status = itemStatusForAction(actionType);
        acceptControlIfCurrentManager(control, principal, "Контроль принят первым действием");
        cardLifecycle.recordItemEpisode(item, status, false);
        item.setStatus(status);
        item.setActionType(actionType);
        item.setComment(comment);
        item.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? LocalDateTime.now() : null);
        item.setAutomaticResolution(false);
        dailyControlItemRepository.save(item);
        if (control.getStartedAt() == null) {
            control.setStartedAt(LocalDateTime.now());
        }
        control.setLastActivityAt(LocalDateTime.now());
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, item, accessPolicy.actorUserId(principal), status == ManagerDailyControlItemStatus.RESOLVED ? ManagerDailyControlEventType.ITEM_RESOLVED : ManagerDailyControlEventType.ITEM_ACTION, actionType, item.getComment());
        if (status == ManagerDailyControlItemStatus.RESOLVED) {
            recordGamificationControlAction(control, "item:" + item.getId(), item.getReasonCode(), item.getCreatedAt(), item.getResolvedAt(), item.getLabel());
        }
    }

    @Transactional
    public ManagerControlConcreteItemResponse actionConcreteItem(Long concreteItemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        accessPolicy.requireControlAccess(control, principal, authentication);
        ManagerDailyControlActionType actionType = parseActionType(request == null ? null : request.actionType());
        requireConcreteActionAllowed(concreteItem, actionType);
        if ("COMMON_INVOICE".equals(concreteItem.getEntityType()) && (actionType == ManagerDailyControlActionType.ACTION_TAKEN || actionType == ManagerDailyControlActionType.RESOLVED)) {
            invoiceDiagnostics.requireResolved(concreteItem == null ? null : concreteItem.getEntityId());
            actionType = ManagerDailyControlActionType.RESOLVED;
        }
        if (actionType == ManagerDailyControlActionType.RESOLVED && (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(concreteItem.getEntityType()) || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(concreteItem.getEntityType()))) {
            requireAutomationFailureResolved(concreteItem);
        }
        ManagerDailyControlItemStatus status = itemStatusForAction(actionType);
        String comment = limit(request == null ? null : request.comment(), 1000);
        boolean manualWorkerNotification = Boolean.TRUE.equals(request == null ? null : request.manualWorkerNotification());
        boolean specialistActionConcrete = isSpecialistActionConcrete(concreteItem);
        boolean clientChatUnansweredConcrete = ENTITY_CLIENT_CHAT_UNANSWERED.equals(concreteItem.getEntityType());
        boolean clientChatAuditConcrete = ENTITY_CLIENT_CHAT_AUDIT.equals(concreteItem.getEntityType());
        boolean keepClientChatUnansweredOpen = clientChatUnansweredConcrete && actionType == ManagerDailyControlActionType.DEFERRED;
        if (keepClientChatUnansweredOpen) {
            status = ManagerDailyControlItemStatus.OPEN;
        }
        if (manualWorkerNotification && specialistActionConcrete && safe(comment).isBlank()) {
            comment = manualWorkerNotificationComment(concreteItem);
        }
        requireCommentIfNeeded(concreteItem.getParentItem(), actionType, comment);
        LocalDateTime now = LocalDateTime.now();
        acceptControlIfCurrentManager(control, principal, "Контроль принят первым действием по карточке");
        concreteItem.setComment(comment);
        if (specialistActionConcrete && status != ManagerDailyControlItemStatus.RESOLVED && actionType == ManagerDailyControlActionType.ACTION_TAKEN && manualWorkerNotification && !notifyWorkerAboutTaskRequest(concreteItem, control)) {
            String failureReason = safe(concreteItem.getWorkerNotificationFailureReason());
            String failureAction = requiresWorkerExplanation(concreteItem) ? "Запрос специалисту не доставлен" : "Напоминание специалисту не доставлено";
            concreteItem.setComment(failureReason.isBlank() ? failureAction : failureAction + ": " + failureReason);
            concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
            concreteItem.setActionType(null);
            concreteItem.setResolvedAt(null);
            concreteItem.setAutomaticResolution(false);
            concreteItem.setFollowUpAt(null);
            concreteItem.setLastManualTouchAt(now);
            ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
            control.setLastActivityAt(now);
            dailyControlRepository.save(control);
            cardLifecycle.invalidateManagerPerformance();
            return concretePresenter.concreteItemResponse(savedConcreteItem);
        }
        if (specialistActionConcrete && status != ManagerDailyControlItemStatus.RESOLVED && actionType == ManagerDailyControlActionType.ACTION_TAKEN && !manualWorkerNotification && requiresWorkerExplanation(concreteItem) && concreteItem.getWorkerExplanationAt() == null && !canOverrideWorkerExplanation(authentication)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сначала запросите пояснение специалиста или зафиксируйте эскалацию с комментарием");
        }
        if (specialistActionConcrete && status == ManagerDailyControlItemStatus.RESOLVED && requiresWorkerExplanation(concreteItem) && concreteItem.getWorkerExplanationAt() == null && !canOverrideWorkerExplanation(authentication)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Закрыть карточку можно после ответа специалиста или администратором/владельцем");
        }
        cardLifecycle.recordConcreteEpisode(concreteItem, status, false);
        concreteItem.setStatus(status);
        concreteItem.setActionType(actionType);
        concreteItem.setResolvedAt(status == ManagerDailyControlItemStatus.RESOLVED ? now : null);
        concreteItem.setAutomaticResolution(false);
        boolean movedToReminder = false;
        if ("ORDER".equals(concreteItem.getEntityType()) && status != ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(now.plusDays(MANUAL_FOLLOW_UP_DAYS));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN) {
                movedToReminder = movePaymentOrderToReminderAfterManualSend(concreteItem);
            }
        } else if (clientChatAuditConcrete) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(null);
            clientChatMessageTrackerService.markAuditReviewed(concreteItem.getEntityId(), accessPolicy.actorUserId(principal), comment);
            concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
            concreteItem.setResolvedAt(now);
            status = ManagerDailyControlItemStatus.RESOLVED;
            actionType = ManagerDailyControlActionType.RESOLVED;
        } else if (clientChatUnansweredConcrete) {
            concreteItem.setLastManualTouchAt(now);
            clientChatMessageTrackerService.markFromManagerControl(concreteItem.getEntityId(), actionType, comment, accessPolicy.actorUserId(principal));
            if (actionType == ManagerDailyControlActionType.ACTION_TAKEN || actionType == ManagerDailyControlActionType.ACKNOWLEDGED || actionType == ManagerDailyControlActionType.RESOLVED) {
                concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
                concreteItem.setResolvedAt(now);
                concreteItem.setFollowUpAt(null);
                status = ManagerDailyControlItemStatus.RESOLVED;
            } else if (actionType == ManagerDailyControlActionType.DEFERRED) {
                concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
                concreteItem.setResolvedAt(null);
                concreteItem.setFollowUpAt(null);
                status = ManagerDailyControlItemStatus.OPEN;
            }
        } else if (specialistActionConcrete && status != ManagerDailyControlItemStatus.RESOLVED && actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            concreteItem.setLastManualTouchAt(now);
            concreteItem.setFollowUpAt(actionType == ManagerDailyControlActionType.DEFERRED || requiresWorkerExplanation(concreteItem) ? workerTaskFollowUpAt(now) : nextDayFollowUpAt(now));
        } else if (status == ManagerDailyControlItemStatus.RESOLVED) {
            concreteItem.setFollowUpAt(null);
            concreteItem.setLastManualTouchAt(now);
        }
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
        cardLifecycle.updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
        String eventComment = "Карточка: " + concreteItem.getTitle() + (movedToReminder ? ". Статус заказа переведен в Напоминание" : "") + (concreteItem.getComment() == null || concreteItem.getComment().isBlank() ? "" : ". " + concreteItem.getComment());
        cardLifecycle.saveEvent(control, savedConcreteItem.getParentItem(), accessPolicy.actorUserId(principal), status == ManagerDailyControlItemStatus.RESOLVED ? ManagerDailyControlEventType.ITEM_RESOLVED : ManagerDailyControlEventType.ITEM_ACTION, actionType, eventComment);
        if (status == ManagerDailyControlItemStatus.RESOLVED && !clientChatUnansweredConcrete) {
            recordGamificationControlAction(control, "concrete:" + savedConcreteItem.getId(), savedConcreteItem.getParentItem() == null ? null : savedConcreteItem.getParentItem().getReasonCode(), savedConcreteItem.getCreatedAt(), savedConcreteItem.getResolvedAt(), savedConcreteItem.getTitle());
        }
        if (actionType == ManagerDailyControlActionType.DEFERRED) {
            notifyOwnersAboutDeferredConcreteItem(control, savedConcreteItem, principal);
        }
        return concretePresenter.concreteItemResponse(savedConcreteItem);
    }

    void recordGamificationControlAction(ManagerDailyControl control, String uniqueKey, String reasonCode, LocalDateTime startedAt, LocalDateTime completedAt, String label) {
        Manager manager = control == null ? null : control.getManager();
        int target = slaPolicy.controlCardTargetMinutes();
        int hard = slaPolicy.controlCardHardMinutes(target);
        gamificationEventService.recordManagerControlAction(manager, uniqueKey, startedAt, completedAt, target, hard, "reason=" + safe(reasonCode) + ";label=" + safe(label));
    }

    boolean movePaymentOrderToReminderAfterManualSend(ManagerDailyControlConcreteItem concreteItem) {
        Long orderId = concreteItem.getEntityId();
        if (orderId == null) {
            return false;
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ контроля не найден"));
        String currentStatus = order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
        if (ORDER_STATUS_REMINDER.equals(currentStatus)) {
            concreteItem.setStatusLabel(ORDER_STATUS_REMINDER);
            return false;
        }
        if (!ORDER_STATUS_TO_PAY.equals(currentStatus)) {
            return false;
        }
        try {
            boolean changed = orderService.changeStatusForOrder(orderId, ORDER_STATUS_REMINDER);
            if (!changed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось перевести заказ в Напоминание");
            }
            concreteItem.setStatusLabel(ORDER_STATUS_REMINDER);
            return true;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в Напоминание", e);
        }
    }

    void requireCommentIfNeeded(ManagerDailyControlItem item, ManagerDailyControlActionType actionType, String comment) {
        if (item == null || actionType == ManagerDailyControlActionType.RESOLVED) {
            return;
        }
        boolean required = actionType == ManagerDailyControlActionType.DEFERRED;
        if (required && safe(comment).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для этого действия нужен комментарий");
        }
    }

    boolean isSpecialistActionConcrete(ManagerDailyControlConcreteItem item) {
        return workerTaskLookup.isSpecialistActionConcrete(item);
    }

    LocalDateTime workerTaskFollowUpAt(LocalDateTime now) {
        LocalDateTime base = now == null ? LocalDateTime.now() : now;
        return base.plusHours(WORKER_TASK_FOLLOW_UP_HOURS);
    }

    LocalDateTime nextDayFollowUpAt(LocalDateTime now) {
        LocalDateTime base = now == null ? LocalDateTime.now() : now;
        return base.plusDays(1);
    }

    String manualWorkerNotificationComment(ManagerDailyControlConcreteItem concreteItem) {
        if (!requiresWorkerExplanation(concreteItem)) {
            return "Специалисту отправлено напоминание: " + specialistProblemLabel(concreteItem) + ". Повторный контроль завтра.";
        }
        return "Специалисту отправлен запрос на пояснение: " + specialistProblemLabel(concreteItem) + ". Повторный контроль через " + WORKER_TASK_FOLLOW_UP_HOURS + " ч.";
    }

    boolean requiresWorkerExplanation(ManagerDailyControlConcreteItem concreteItem) {
        return workerTaskWorkflow.requiresWorkerExplanation(concreteItem);
    }

    boolean canOverrideWorkerExplanation(Authentication authentication) {
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER");
    }

    String specialistProblemLabel(ManagerDailyControlConcreteItem concreteItem) {
        return workerTaskWorkflow.specialistProblemLabel(concreteItem);
    }

    boolean notifyWorkerAboutTaskRequest(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control) {
        return workerTaskWorkflow.notifyWorkerAboutTaskRequest(concreteItem, control);
    }

    void requireAutomationFailureResolved(ManagerDailyControlConcreteItem concreteItem) {
        Manager manager = concreteItem == null || concreteItem.getControl() == null ? null : concreteItem.getControl().getManager();
        if (!managerAutomationFailureService.isStillActionable(manager, concreteItem == null ? null : concreteItem.getEntityType(), concreteItem == null ? null : concreteItem.getEntityId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Ошибка автоматизации все еще активна. Устраните причину и дождитесь успешной отправки или следующей синхронизации.");
    }

    ManagerDailyControlActionType parseActionType(String value) {
        if (value == null || value.isBlank()) {
            return ManagerDailyControlActionType.ACKNOWLEDGED;
        }
        try {
            return ManagerDailyControlActionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное действие контроля");
        }
    }

    ManagerDailyControlItemStatus itemStatusForAction(ManagerDailyControlActionType actionType) {
        return switch(actionType) {
            case ACKNOWLEDGED ->
                ManagerDailyControlItemStatus.ACKNOWLEDGED;
            case ACTION_TAKEN ->
                ManagerDailyControlItemStatus.ACTION_TAKEN;
            case DEFERRED ->
                ManagerDailyControlItemStatus.DEFERRED;
            case RESOLVED ->
                ManagerDailyControlItemStatus.RESOLVED;
        };
    }

    void requireConcreteActionAllowed(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControlActionType actionType) {
        if (actionType != ManagerDailyControlActionType.ACKNOWLEDGED) {
            return;
        }
        if (concreteItem != null && ENTITY_CLIENT_CHAT_UNANSWERED.equals(concreteItem.getEntityType())) {
            return;
        }
        ManagerDailyControlItem parentItem = concreteItem == null ? null : concreteItem.getParentItem();
        if (parentItem != null && parentItem.getGroup() == ManagerDailyControlGroup.ACTION && parentItem.getSeverity() == ManagerDailyControlSeverity.CRITICAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для красной карточки нужно выполнить действие, отложить или закрыть проблему");
        }
    }

    void rejectAggregateActionForConcreteItem(ManagerDailyControlItem item) {
        if (requiresConcreteCardAction(item)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Красный пункт нельзя закрыть целиком. Обработайте конкретные карточки внутри пункта.");
        }
    }

    boolean requiresConcreteCardAction(ManagerDailyControlItem item) {
        return dayLifecycle.requiresConcreteCardAction(item);
    }

    void acceptControlIfCurrentManager(ManagerDailyControl control, Principal principal, String comment) {
        dayLifecycle.acceptControlIfCurrentManager(control, principal, comment);
    }

    String limit(String value, int maxLength) {
        return problemExamples.limit(value, maxLength);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
