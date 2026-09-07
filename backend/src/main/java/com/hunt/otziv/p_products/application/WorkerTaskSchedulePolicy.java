package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.worker_access.service.WorkerTaskSchedulePermission;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Owns task schedule permissions; preserves the distinct bad/recovery null-date contracts. */
@Service
@RequiredArgsConstructor
public class WorkerTaskSchedulePolicy {

    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;

    public LocalDate allowedBadTaskScheduledDate(Long taskId, LocalDate requestedDate, Authentication authentication) {
        if (WorkerTaskSchedulePermission.canEdit(authentication)) {
            return requestedDate;
        }

        BadReviewTask task = badReviewTaskService.getTask(taskId);
        LocalDate currentDate = task == null ? null : task.getScheduledDate();
        requireWorkerScheduleUnchanged(requestedDate, currentDate, authentication);
        return currentDate;
    }

    public LocalDate allowedRecoveryTaskScheduledDate(Long taskId, LocalDate requestedDate, Authentication authentication) {
        if (WorkerTaskSchedulePermission.canEdit(authentication)) {
            return requestedDate;
        }

        ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);
        LocalDate currentDate = task == null ? null : task.getScheduledDate();
        requireWorkerScheduleUnchanged(requestedDate, currentDate, authentication);
        return requestedDate;
    }

    private void requireWorkerScheduleUnchanged(LocalDate requestedDate, LocalDate currentDate, Authentication authentication) {
        if (!WorkerTaskSchedulePermission.allows(requestedDate, currentDate, authentication)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.FORBIDDEN, WorkerTaskSchedulePermission.DENIED_MESSAGE);
        }
    }

}
