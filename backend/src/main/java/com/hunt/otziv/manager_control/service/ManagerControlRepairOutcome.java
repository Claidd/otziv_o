package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlItemActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlReminderWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.security.Principal;
import java.time.LocalDateTime;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlRepairOutcome {

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlConcretePresenter concretePresenter;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    private final ManagerControlOrderDeliveryState deliveryState;

    ManagerControlConcreteItemResponse resolveRepairedConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment) {
        return finish(concreteItem, control, comment, principal, eventComment, false);
    }

    ManagerControlConcreteItemResponse recordConfirmedContact(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment, String expectedTarget) {
        var locked = dailyControlConcreteItemRepository.findByIdForUpdate(concreteItem.getId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        deliveryState.requireNoManagerDeliveryPending(locked, expectedTarget);
        return finish(locked, locked.getControl(), comment, principal, eventComment, true);
    }

    ManagerControlConcreteItemResponse recordReminderScheduled(ManagerDailyControlConcreteItem card, String note,
            Principal principal, LocalDateTime next) {
        // The automation fault is repaired, but the order still awaits the customer's action.
        boolean waiting = "ORDER".equals(card.getEntityType());
        return finish(card, card.getControl(), note, principal, "Восстановлен следующий срок напоминания", waiting,
                waiting ? next.plusHours(2) : null);
    }

    ManagerControlConcreteItemResponse appendArchiveDecision(ManagerDailyControlConcreteItem card, String reason) {
        card.setComment(limit((card.getComment() == null ? "" : card.getComment() + " ") + reason,1000));
        return concretePresenter.concreteItemResponse(dailyControlConcreteItemRepository.save(card));
    }

    private ManagerControlConcreteItemResponse finish(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control,
            String comment, Principal principal, String eventComment, boolean awaitingClient) {
        return finish(concreteItem, control, comment, principal, eventComment, awaitingClient,
                awaitingClient ? LocalDateTime.now().plusDays(MANUAL_FOLLOW_UP_DAYS) : null);
    }

    private ManagerControlConcreteItemResponse finish(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control,
            String comment, Principal principal, String eventComment, boolean awaitingClient, LocalDateTime followUp) {
        LocalDateTime now = LocalDateTime.now();
        var status = awaitingClient ? ManagerDailyControlItemStatus.ACTION_TAKEN : ManagerDailyControlItemStatus.RESOLVED;
        var action = awaitingClient ? ManagerDailyControlActionType.ACTION_TAKEN : ManagerDailyControlActionType.RESOLVED;
        cardLifecycle.recordConcreteEpisode(concreteItem, status, false);
        concreteItem.setStatus(status);
        concreteItem.setActionType(action);
        concreteItem.setComment(limit(comment, 1000));
        concreteItem.setResolvedAt(awaitingClient ? null : now);
        concreteItem.setAutomaticResolution(false);
        concreteItem.setFollowUpAt(followUp);
        concreteItem.setLastManualTouchAt(now);
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
        cardLifecycle.updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, savedConcreteItem.getParentItem(), accessPolicy.actorUserId(principal),
                awaitingClient ? ManagerDailyControlEventType.ITEM_ACTION : ManagerDailyControlEventType.ITEM_RESOLVED,
                action, eventComment + ": " + savedConcreteItem.getTitle());
        return concretePresenter.concreteItemResponse(savedConcreteItem);
    }

    String limit(String value, int maxLength) {
        return problemExamples.limit(value, maxLength);
    }
}
