package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
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
import java.time.LocalTime;
import java.util.List;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlDayActions {

    private final ManagerControlBoardWorkflow boardWorkflow;

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlItemRepository dailyControlItemRepository;

    @Transactional
    public ManagerControlManagerDetailResponse markStage(Long controlId, ManagerControlStageRequest request, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        String stage = safe(request == null ? null : request.stage()).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        switch(stage) {
            case "MORNING_START" ->
                control.setMorningStartedAt(now);
            case "MORNING_DONE" ->
                {
                    if (control.getMorningStartedAt() == null) {
                        control.setMorningStartedAt(now);
                    }
                    control.setMorningCompletedAt(now);
                }
            case "DAY_CHECK" ->
                {
                    rejectStageCompletionIfProblemsOpen(control, "Дневной контроль");
                    rejectIfPreviousStageMissing(control.getMorningCompletedAt(), "Сначала отметьте начало дня");
                    rejectIfOutsideStageWindow("Дневной контроль", now.toLocalTime());
                    control.setDayCheckedAt(now);
                }
            case "FINAL_CHECK" ->
                {
                    rejectStageCompletionIfProblemsOpen(control, "Конец дня");
                    rejectIfPreviousStageMissing(control.getMorningCompletedAt(), "Сначала отметьте начало дня");
                    rejectIfOutsideStageWindow("Конец дня", now.toLocalTime());
                    control.setFinalCheckedAt(now);
                }
            default ->
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный этап контроля");
        }
        updateQuality(control, dailyControlItemRepository.findByControl(control));
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(control, null, accessPolicy.actorUserId(principal), ManagerDailyControlEventType.STAGE_MARKED, null, stage + (safe(request == null ? null : request.comment()).isBlank() ? "" : ". " + request.comment()));
        return managerDetails(control.getManager(), false);
    }

    @Transactional
    public ManagerControlCloseResponse closeDay(Long controlId, ManagerControlCloseRequest request, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        List<ManagerDailyControlItem> items = dailyControlItemRepository.findByControl(control);
        acceptControlIfCurrentManager(control, principal, "Контроль принят перед закрытием");
        List<String> blockers = closeBlockers(control, items);
        updateQuality(control, items);
        if (!blockers.isEmpty()) {
            dailyControlRepository.save(control);
            cardLifecycle.saveEvent(control, null, accessPolicy.actorUserId(principal), ManagerDailyControlEventType.CLOSE_ATTEMPT_BLOCKED, null, String.join("; ", blockers));
            return closeResponse(control, false, blockers);
        }
        closeControl(control, items, LocalDateTime.now(), accessPolicy.actorUserId(principal), safe(request == null ? null : request.comment()));
        return closeResponse(control, true, List.of());
    }

    public ManagerControlManagerDetailResponse managerDetails(Long managerId, Principal principal, Authentication authentication) {
        return boardWorkflow.managerDetails(managerId, principal, authentication);
    }

    ManagerControlManagerDetailResponse managerDetails(Manager manager, boolean syncConcrete) {
        return boardWorkflow.managerDetails(manager, syncConcrete);
    }

    @Transactional
    public ManagerControlManagerDetailResponse acceptControl(Long controlId, Principal principal, Authentication authentication) {
        ManagerDailyControl control = controlForAction(controlId, principal, authentication);
        acceptControlIfCurrentManager(control, principal, "Контроль принят явным действием");
        return managerDetails(control.getManager(), false);
    }

    ManagerDailyControl controlForAction(Long controlId, Principal principal, Authentication authentication) {
        if (controlId == null || controlId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный контроль дня");
        }
        ManagerDailyControl control = dailyControlRepository.findById(controlId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Контроль дня не найден"));
        accessPolicy.requireControlAccess(control, principal, authentication);
        return control;
    }

    List<String> closeBlockers(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.closeBlockers(control, items);
    }

    void closeControl(ManagerDailyControl control, List<ManagerDailyControlItem> items, LocalDateTime now, Long actorUserId, String comment) {
        dayLifecycle.closeControl(control, items, now, actorUserId, comment);
    }

    void rejectStageCompletionIfProblemsOpen(ManagerDailyControl control, String stageLabel) {
        dayLifecycle.rejectStageCompletionIfProblemsOpen(control, stageLabel);
    }

    void rejectIfPreviousStageMissing(LocalDateTime completedAt, String message) {
        dayLifecycle.rejectIfPreviousStageMissing(completedAt, message);
    }

    void rejectIfOutsideStageWindow(String stageLabel, LocalTime time) {
        dayLifecycle.rejectIfOutsideStageWindow(stageLabel, time);
    }

    ManagerControlCloseResponse closeResponse(ManagerDailyControl control, boolean closed, List<String> blockers) {
        return dayLifecycle.closeResponse(control, closed, blockers);
    }

    boolean updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.updateQuality(control, items);
    }

    void acceptControlIfCurrentManager(ManagerDailyControl control, Principal principal, String comment) {
        dayLifecycle.acceptControlIfCurrentManager(control, principal, comment);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
