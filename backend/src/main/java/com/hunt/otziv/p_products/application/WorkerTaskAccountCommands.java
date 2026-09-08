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
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for TaskAccount. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
public class WorkerTaskAccountCommands {

    private static final String SECTION_RECOVERY="recovery";

    private static final String SECTION_BAD="bad";

    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final WorkerActivityService workerActivityService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    public BotChangeResponse changeRecoveryTaskBot(Long taskId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        try {
            ReviewRecoveryTask task = reviewRecoveryTaskService.changeTaskBot(taskId, authentication);
            workerActivityService.recordSafely(authentication,
                    WorkerActivityAction.RECOVERY_TASK_BOT_CHANGE,
                    "recovery_task",
                    taskId,
                    orderId(task),
                    reviewId(task),
                    SECTION_RECOVERY,
                    botChangeDetails(null, botId(task))
            );
            return new BotChangeResponse(null, botId(task));
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Аккаунт восстановления не заменен");
        }
    }

    public void deactivateRecoveryTaskBot(Long taskId, Long botId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        try {
            ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);
            reviewRecoveryTaskService.deactivateAndChangeTaskBot(taskId, botId, authentication);
            workerActivityService.recordSafely(authentication,
                    WorkerActivityAction.RECOVERY_TASK_BOT_DEACTIVATE,
                    "recovery_task",
                    taskId,
                    orderId(task),
                    reviewId(task),
                    SECTION_RECOVERY,
                    "botId=" + valueOrDash(botId) + ";"
            );
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Аккаунт восстановления не заблокирован");
        }
    }

    public BotChangeResponse changeBadReviewTaskBot(Long taskId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        try {
            BadReviewTask task = badReviewTaskService.changeTaskBot(taskId, authentication);
            workerActivityService.recordSafely(authentication,
                    WorkerActivityAction.BAD_TASK_BOT_CHANGE,
                    "bad_review_task",
                    taskId,
                    orderId(task),
                    reviewId(task),
                    SECTION_BAD,
                    botChangeDetails(null, botId(task))
            );
            return new BotChangeResponse(null, botId(task));
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Аккаунт плохой задачи не заменен");
        }
    }

    public void deactivateBadReviewTaskBot(Long taskId, Long botId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        try {
            BadReviewTask task = badReviewTaskService.getTask(taskId);
            badReviewTaskService.deactivateAndChangeTaskBot(taskId, botId, authentication);
            workerActivityService.recordSafely(authentication,
                    WorkerActivityAction.BAD_TASK_BOT_DEACTIVATE,
                    "bad_review_task",
                    taskId,
                    orderId(task),
                    reviewId(task),
                    SECTION_BAD,
                    "botId=" + valueOrDash(botId) + ";"
            );
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Аккаунт плохой задачи не заблокирован");
        }
    }

    public record BotChangeResponse(Long oldBotId, Long newBotId) {}
}
