package com.hunt.otziv.p_products.application;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import java.security.Principal;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for TaskAssignment. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
public class WorkerTaskAssignmentCommands {

    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final WorkerStaffAccessPolicy staff;

    public void reassignBadReviewTask(Long taskId, Long workerId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER");
        Principal principal = authentication;
        try {
            assignmentMutationGuardService.assertBadTask(taskId, authentication);
            BadReviewTask task = badReviewTaskService.getTask(taskId);
            staff.enforceTaskAssignmentAccess(task == null ? null : task.getOrder(), null, principal, authentication);
            Worker worker = staff.assignmentWorker(workerId, principal, authentication);
            badReviewTaskService.reassignTask(taskId, worker, authentication);
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Специалист плохой задачи не изменен");
        }
    }

    public void reassignRecoveryTask(Long taskId, Long workerId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER");
        Principal principal = authentication;
        try {
            assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
            ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);
            staff.enforceTaskAssignmentAccess(
                    task == null ? null : task.getOrder(),
                    task == null ? null : task.getManager(),
                    principal,
                    authentication
            );
            Worker worker = staff.assignmentWorker(workerId, principal, authentication);
            reviewRecoveryTaskService.reassignTask(taskId, worker, authentication);
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Специалист восстановления не изменен");
        }
    }
}
