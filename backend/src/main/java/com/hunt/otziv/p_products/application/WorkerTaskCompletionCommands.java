package com.hunt.otziv.p_products.application;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for TaskCompletion. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerTaskCompletionCommands {

    private static final String SECTION_RECOVERY="recovery";

    private static final String SECTION_BAD="bad";

    private final UserService userService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final WorkerActivityService workerActivityService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    public void completeRecoveryTask(Long taskId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        try {
            ReviewRecoveryTask task = reviewRecoveryTaskService.completeTask(taskId, currentUser(authentication), authentication);
            workerActivityService.recordSafely(
                    authentication,
                    WorkerActivityAction.RECOVERY_TASK_COMPLETE,
                    "recovery_task",
                    task.getId(),
                    orderId(task),
                    reviewId(task),
                    SECTION_RECOVERY,
                    "botId=" + valueOrDash(botId(task)) + ";"
            );
        } catch (RuntimeException exception) {
            log.warn("Задача восстановления не выполнена: taskId={}, user={}",
                    taskId, authentication == null ? null : authentication.getName(), exception);
            throw commandFailure(exception, "Задача восстановления не выполнена");
        }
    }

    public void completeBadReviewTask(Long taskId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        try {
            BadReviewTask task = badReviewTaskService.completeTask(taskId, authentication);
            workerActivityService.recordSafely(
                    authentication,
                    WorkerActivityAction.BAD_TASK_COMPLETE,
                    "bad_review_task",
                    task.getId(),
                    orderId(task),
                    reviewId(task),
                    SECTION_BAD,
                    "botId=" + valueOrDash(botId(task)) + ";"
            );
        } catch (RuntimeException exception) {
            log.warn("Плохая задача не выполнена: taskId={}, user={}",
                    taskId, authentication == null ? null : authentication.getName(), exception);
            throw commandFailure(exception, "Плохая задача не выполнена");
        }
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }

        return userService.findByUserName(authentication.getName()).orElse(null);
    }
}
