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

    ManagerControlConcreteItemResponse resolveRepairedConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment) {
        LocalDateTime now = LocalDateTime.now();
        cardLifecycle.recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(limit(comment, 1000));
        concreteItem.setResolvedAt(now);
        concreteItem.setAutomaticResolution(false);
        concreteItem.setFollowUpAt(null);
        concreteItem.setLastManualTouchAt(now);
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);
        cardLifecycle.updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, savedConcreteItem.getParentItem(), accessPolicy.actorUserId(principal), ManagerDailyControlEventType.ITEM_RESOLVED, ManagerDailyControlActionType.RESOLVED, eventComment + ": " + savedConcreteItem.getTitle());
        return concretePresenter.concreteItemResponse(savedConcreteItem);
    }

    String limit(String value, int maxLength) {
        return problemExamples.limit(value, maxLength);
    }
}
