package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class WorkerReviewAccountCommandsTest {
    ReviewService reviews=mock(ReviewService.class);
    BotService bots=mock(BotService.class);
    WorkerAssignmentMutationGuardService guard=mock(WorkerAssignmentMutationGuardService.class);
    WorkerCellularAccessService cellular=mock(WorkerCellularAccessService.class);
    WorkerPublicationGateService publication=mock(WorkerPublicationGateService.class);
    WorkerActivityService activity=mock(WorkerActivityService.class);
    WorkerReviewAccountCommands commands=new WorkerReviewAccountCommands(reviews,bots,guard,cellular,publication,activity);
    WorkerOrderActor worker=new WorkerOrderActor("worker",Set.of("WORKER"));

    @Test void directApplicationCallRejectsUnauthorizedActorBeforeReadingAccount() {
        assertThatThrownBy(() -> commands.change(1,null,new WorkerOrderActor("anonymous",Set.of())))
                .isInstanceOf(WorkerOrderCommandException.class);
        verifyNoInteractions(reviews,bots,guard,activity);
    }

    @Test void foreignReviewStopsBeforeMutationAndAudit() {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(guard).assertReview(eq(1L),any(Authentication.class));
        assertThatThrownBy(() -> commands.rename(1,"name",worker))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(reviews,bots,activity);
    }

    @Test void spoofedNewSectionCannotDisableProtectedReviewNetworkRule() {
        Review review=new Review(); review.setId(1L); review.setVigul(true);
        when(reviews.getReviewById(1L)).thenReturn(review);
        commands.change(1,new WorkerReviewAccountCommands.Source("board",null,"new"),worker);
        verify(cellular).enforceSection(eq("publish"),argThat(actor -> actor.getName().equals("worker")));
        verify(guard).assertReview(eq(1L),argThat(actor -> actor.getName().equals("worker")));
    }

    @Test void workerCannotUseAdministratorOnlyDeleteCommand() {
        assertThatThrownBy(() -> commands.delete(1,worker)).isInstanceOf(WorkerOrderCommandException.class);
        verifyNoInteractions(bots,activity);
    }
}
