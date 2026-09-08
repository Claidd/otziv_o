package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.ReviewPublicationCommands;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** View-specific admission stays outside the short locked publication transaction. */
@Service
@RequiredArgsConstructor
public class ReviewPublicationCommandService implements ReviewPublicationCommands {
    private final ReviewPublicationMutationService mutation;
    private final ReviewService reviews;
    private final WorkerPublicationGateService gate;
    private final WorkerCredentialPreparationService preparation;
    private final WorkerCellularAccessService cellular;
    private final WorkerAssignmentMutationGuardService assignment;

    @Override public void publishWorker(Long id,Authentication actor) {
        WorkerOrderActor.from(actor).require("ADMIN","OWNER","MANAGER","WORKER");
        cellular.enforceProtectedAccess("publish",actor);
        gate.blockForPublication(actor,actor).ifPresent(block->{throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.CONFLICT,block.message());});
        try {
            var review=reviews.getReviewById(id);
            assignment.assertReview(id,actor);
            preparation.blockUntilReady(actor,WorkerCredentialPreparationScope.PUBLISH,id,botId(review),150)
                    .ifPresent(block->{throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.CONFLICT,block.message());});
            mutation.publish(id,null,null,actor,"",true);
            preparation.clear(actor,WorkerCredentialPreparationScope.PUBLISH);
            gate.recordPublicationActivity(actor,actor);
        } catch(Exception error) {throw commandFailure(error,"Отзыв не отмечен опубликованным");}
    }

    @Override public void publishManager(Long orderId,Long reviewId,Authentication actor,String sourceDetails) throws Exception {
        mutation.publish(reviewId,orderId,null,actor,sourceDetails,true);
        preparation.clear(actor,WorkerCredentialPreparationScope.PUBLISH);
    }

    @Override public LegacyOutcome publishLegacy(Long companyId,Long orderId,Long reviewId,Authentication actor) throws Exception {
        WorkerOrderActor.from(actor).require("ADMIN","OWNER","MANAGER","WORKER");
        var blocked=gate.blockForPublication(actor,actor);
        if(blocked.isPresent())return new LegacyOutcome(false,blocked.get().section(),blocked.get().message());
        mutation.publish(reviewId,orderId,companyId,actor,null,false);
        return new LegacyOutcome(true,null,null);
    }
}
