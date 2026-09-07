package com.hunt.otziv.p_products.application;

import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import java.security.Principal;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for ReviewPublication. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
public class WorkerReviewPublicationCommands {

    private static final String SECTION_NAGUL="nagul";

    private static final String SECTION_PUBLISH="publish";

    private final com.hunt.otziv.p_products.api.ReviewPublicationCommands publication;
    private final ReviewService reviewService;
    private final WorkerPublicationGateService workerPublicationGateService;
    private final WorkerActivityService workerActivityService;
    private final WorkerCredentialPreparationService credentialPreparationService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    public void publishReview(Long reviewId,WorkerOrderActor actor) {
        publication.publishWorker(reviewId,requireActor(actor,"ADMIN","OWNER","MANAGER","WORKER"));
    }

    public WorkerActionResponse nagulReview(Long reviewId, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        Principal principal = authentication;
        workerCellularAccessService.enforceProtectedAccess(SECTION_NAGUL, authentication);
        try {
            Review review = reviewService.getReviewById(reviewId);
            assignmentMutationGuardService.assertReview(reviewId, authentication);
            credentialPreparationService.blockUntilReady(
                    authentication,
                    WorkerCredentialPreparationScope.NAGUL,
                    reviewId,
                    botId(review),
                    REVIEW_NAGUL_CREDENTIAL_WAIT_SECONDS
            ).ifPresent(block -> {
                throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.CONFLICT, block.message());
            });
            reviewService.performNagulWithExceptions(reviewId, principal.getName(), authentication);
            workerActivityService.recordSafely(authentication,
                    WorkerActivityAction.REVIEW_NAGUL,
                    "review",
                    reviewId,
                    orderId(review),
                    reviewId,
                    SECTION_NAGUL,
                    botDetails(review == null ? null : review.getBot())
            );
            credentialPreparationService.clear(authentication, WorkerCredentialPreparationScope.NAGUL);
            return new WorkerActionResponse(true, "Отзыв успешно выгулен");
        } catch (NagulTooFastException | BotTemplateNameException exception) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.CONFLICT, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw commandFailure(exception, "Произошла ошибка при выполнении выгула");
        }
    }

    private static final int REVIEW_PUBLISH_CREDENTIAL_WAIT_SECONDS=150, REVIEW_NAGUL_CREDENTIAL_WAIT_SECONDS=180;

    public record WorkerActionResponse(boolean success, String message) {}
}
