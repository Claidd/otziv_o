package com.hunt.otziv.p_products.application;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for TaskEditing. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
public class WorkerTaskEditingCommands {

    private static final String SECTION_RECOVERY="recovery";

    private static final String SECTION_BAD="bad";

    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final WorkerActivityService workerActivityService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final WorkerTaskSchedulePolicy schedule;

    public void updateBadReviewTask(Long taskId, BadTaskUpdateRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        if (request == null || request.taskText() == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Текст плохой задачи не указан");
        }

        try {
            LocalDate scheduledDate = schedule.allowedBadTaskScheduledDate(taskId, request.scheduledDate(), authentication);
            badReviewTaskService.updateTask(taskId, request.taskText(), scheduledDate, authentication);
            if (workerActivityService.isPlainWorker(authentication)) {
                BadReviewTask task = badReviewTaskService.getTask(taskId);
                workerActivityService.recordSafely(
                        authentication,
                        WorkerActivityAction.BAD_TASK_UPDATE,
                        "bad_review_task",
                        taskId,
                        orderId(task),
                        reviewId(task),
                        SECTION_BAD,
                        "scheduledDateChanged=" + !Objects.equals(scheduledDate, request.scheduledDate())
                );
            }
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Плохая задача не сохранена");
        }
    }

    public void updateRecoveryTask(Long taskId, RecoveryTaskUpdateRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        if (request == null || request.recoveryText() == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Текст восстановления не указан");
        }

        try {
            LocalDate scheduledDate = schedule.allowedRecoveryTaskScheduledDate(taskId, request.scheduledDate(), authentication);
            reviewRecoveryTaskService.updateTask(taskId, request.recoveryText(), request.recoveryAnswer(), scheduledDate, authentication);
            if (workerActivityService.isPlainWorker(authentication)) {
                ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);
                workerActivityService.recordSafely(
                        authentication,
                        WorkerActivityAction.RECOVERY_TASK_UPDATE,
                        "recovery_task",
                        taskId,
                        orderId(task),
                        reviewId(task),
                        SECTION_RECOVERY,
                        "scheduledDateChanged=" + !Objects.equals(scheduledDate, request.scheduledDate())
                );
            }
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Задача восстановления не сохранена");
        }
    }

    public record BadTaskUpdateRequest(String taskText, LocalDate scheduledDate) {}

    public record RecoveryTaskUpdateRequest(String recoveryText, String recoveryAnswer, LocalDate scheduledDate) {}
}
