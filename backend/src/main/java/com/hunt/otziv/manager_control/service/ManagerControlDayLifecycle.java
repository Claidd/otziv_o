package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlSeverity;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlDayLifecycle {

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlQualityQueries qualityQueries;

    static final LocalTime MORNING_STAGE_START = LocalTime.of(5, 0);

    static final LocalTime START_DAY_DEADLINE = LocalTime.of(14, 0);

    static final LocalTime FINAL_STAGE_START = LocalTime.of(20, 0);

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    List<String> closeBlockers(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        List<String> blockers = new ArrayList<>();
        if (hasActionItems(items) && control.getMorningCompletedAt() == null) {
            blockers.add("Контроль не принят в работу");
        }
        blockers.addAll(problemBlockers(items));
        return blockers;
    }

    void closeControl(ManagerDailyControl control, List<ManagerDailyControlItem> items, LocalDateTime now, Long actorUserId, String comment) {
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        if (control.getFinalCheckedAt() == null) {
            control.setFinalCheckedAt(now);
        }
        control.setClosedAt(now);
        control.setClosedByUserId(actorUserId);
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(items));
        updateQuality(control, items);
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, null, actorUserId, ManagerDailyControlEventType.CONTROL_CLOSED, null, comment);
    }

    boolean reopenClosedControlIfNeeded(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        if (control == null || control.getClosedAt() == null) {
            return false;
        }
        if (items == null || items.stream().noneMatch(cardLifecycle::isOpenActionItem)) {
            return false;
        }
        control.setClosedAt(null);
        control.setClosedByUserId(null);
        control.setFinalCheckedAt(null);
        control.setLastActivityAt(LocalDateTime.now());
        cardLifecycle.saveEvent(control, null, null, ManagerDailyControlEventType.CONTROL_REOPENED, null, "Контроль снова открыт: появились открытые пункты");
        return true;
    }

    boolean hasActionItems(List<ManagerDailyControlItem> items) {
        return items != null && items.stream().anyMatch(item -> item.getGroup() == ManagerDailyControlGroup.ACTION && item.getCount() > 0);
    }

    void rejectStageCompletionIfProblemsOpen(ManagerDailyControl control, String stageLabel) {
        List<String> blockers = problemBlockers(dailyControlItemRepository.findByControl(control));
        if (!blockers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, stageLabel + " нельзя завершить: " + String.join("; ", blockers));
        }
    }

    void rejectIfPreviousStageMissing(LocalDateTime completedAt, String message) {
        if (completedAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    void rejectIfOutsideStageWindow(String stageLabel, LocalTime time) {
        boolean allowed = switch(stageLabel) {
            case "Начало дня" ->
                !time.isBefore(MORNING_STAGE_START) && time.isBefore(FINAL_STAGE_START);
            case "Дневной контроль" ->
                !time.isBefore(START_DAY_DEADLINE) && time.isBefore(FINAL_STAGE_START);
            case "Конец дня" ->
                !time.isBefore(FINAL_STAGE_START) || time.isBefore(MORNING_STAGE_START);
            default ->
                true;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, stageLabel + " можно завершить только в свое окно: начало дня 05:00-20:00, конец дня 20:00-04:59");
        }
    }

    List<String> problemBlockers(List<ManagerDailyControlItem> items) {
        List<String> blockers = new ArrayList<>();
        List<ManagerDailyControlItem> openActionItems = items.stream().filter(cardLifecycle::isOpenActionItem).toList();
        if (!openActionItems.isEmpty()) {
            blockers.add("Остались открытые пункты: " + openActionItems.stream().limit(5).map(item -> item.getLabel() + " " + item.getCount()).collect(Collectors.joining(", ")));
        }
        List<ManagerDailyControlItem> concreteParents = items.stream().filter(this::requiresConcreteCardAction).toList();
        if (!concreteParents.isEmpty()) {
            Map<Long, List<ManagerDailyControlConcreteItem>> concreteByParentId = dailyControlConcreteItemRepository.findByParentItemIn(concreteParents).stream().filter(item -> item.getParentItem() != null && item.getParentItem().getId() != null).collect(Collectors.groupingBy(item -> item.getParentItem().getId()));
            List<ManagerDailyControlConcreteItem> openConcreteItems = concreteByParentId.values().stream().flatMap(List::stream).filter(item -> item.getStatus() == ManagerDailyControlItemStatus.OPEN).toList();
            if (!openConcreteItems.isEmpty()) {
                blockers.add("Остались открытые карточки внутри пунктов: " + openConcreteItems.stream().limit(5).map(ManagerDailyControlConcreteItem::getTitle).collect(Collectors.joining(", ")));
            }
            List<ManagerDailyControlItem> incompleteConcreteParents = concreteParents.stream().filter(item -> concreteByParentId.getOrDefault(item.getId(), List.of()).size() < item.getCount()).toList();
            if (!incompleteConcreteParents.isEmpty()) {
                blockers.add("Не раскрыты все карточки по красным пунктам: " + incompleteConcreteParents.stream().limit(5).map(item -> item.getLabel() + " " + concreteByParentId.getOrDefault(item.getId(), List.of()).size() + "/" + item.getCount()).collect(Collectors.joining(", ")));
            }
        }
        List<ManagerDailyControlItem> criticalWithoutComment = items.stream().filter(item -> item.getGroup() == ManagerDailyControlGroup.ACTION).filter(item -> item.getSeverity() == ManagerDailyControlSeverity.CRITICAL).filter(item -> item.getStatus() != ManagerDailyControlItemStatus.OPEN).filter(item -> item.getStatus() != ManagerDailyControlItemStatus.RESOLVED).filter(item -> safe(item.getComment()).isBlank()).toList();
        if (!criticalWithoutComment.isEmpty()) {
            blockers.add("Нет комментария по критичным пунктам: " + criticalWithoutComment.stream().limit(5).map(ManagerDailyControlItem::getLabel).collect(Collectors.joining(", ")));
        }
        return blockers;
    }

    List<ManagerDailyControlItem> activeControlItems(List<ManagerDailyControlItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().filter(this::isActiveControlItem).toList();
    }

    boolean isActiveControlItem(ManagerDailyControlItem item) {
        if (item == null) {
            return false;
        }
        if (item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        if (item.getGroup() == ManagerDailyControlGroup.WORKLOAD) {
            return false;
        }
        return item.getItemType() != ManagerDailyControlItemType.WORKER_SECTION || !"risk".equals(item.getSectionCode());
    }

    ManagerControlCloseResponse closeResponse(ManagerDailyControl control, boolean closed, List<String> blockers) {
        return new ManagerControlCloseResponse(closed, control.getStatus().name(), control.getQualityScore(), control.getQualityGrade(), control.getRiskScore(), control.isFastClickRisk(), blockers);
    }

    boolean updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        if (control == null || items == null) {
            return false;
        }
        ManagerControlQualityQueries.Quality result = qualityQueries.evaluate(control, items);
        int riskScore = result.riskScore();
        boolean fastClickRisk = result.fastClickRisk();
        int quality = result.qualityScore();
        String qualityGrade = result.qualityGrade();
        boolean changed = control.getRiskScore() != riskScore || control.isFastClickRisk() != fastClickRisk || control.getQualityScore() != quality || !Objects.equals(control.getQualityGrade(), qualityGrade);
        if (!changed) {
            return false;
        }
        control.setRiskScore(riskScore);
        control.setFastClickRisk(fastClickRisk);
        control.setQualityScore(quality);
        control.setQualityGrade(qualityGrade);
        return true;
    }

    boolean requiresConcreteCardAction(ManagerDailyControlItem item) {
        return item != null && item.getGroup() == ManagerDailyControlGroup.ACTION && item.getSeverity() == ManagerDailyControlSeverity.CRITICAL && item.getCount() > 0;
    }

    void acceptControlIfCurrentManager(ManagerDailyControl control, Principal principal, String comment) {
        if (control == null || control.getMorningCompletedAt() != null) {
            return;
        }
        Long actorUserId = accessPolicy.actorUserId(principal);
        Long managerUserId = control.getManagerUserId();
        if (managerUserId == null && control.getManager() != null && control.getManager().getUser() != null) {
            managerUserId = control.getManager().getUser().getId();
        }
        if (actorUserId == null || !Objects.equals(actorUserId, managerUserId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        if (control.getMorningStartedAt() == null) {
            control.setMorningStartedAt(now);
        }
        control.setMorningCompletedAt(now);
        control.setLastActivityAt(now);
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, null, actorUserId, ManagerDailyControlEventType.CONTROL_ACCEPTED, null, comment);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
