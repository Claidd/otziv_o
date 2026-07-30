package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReviewWalkReadinessReconciliationJobTest {

    @Mock
    private BotAssignmentService botAssignmentService;

    @Test
    void reconcilesAllUnpublishedReviewsWithAssignedAccounts() {
        new ReviewWalkReadinessReconciliationJob(botAssignmentService).reconcile();

        verify(botAssignmentService).promoteAllUnpublishedReviewsWithWalkedAccounts();
    }
}
