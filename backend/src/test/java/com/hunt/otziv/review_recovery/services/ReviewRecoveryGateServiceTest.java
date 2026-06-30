package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryGateServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewRecoveryTaskRepository taskRepository;

    @InjectMocks
    private ReviewRecoveryGateService service;

    @Test
    void nextScheduledDateDoesNotUsePastReviewDateAsBase() {
        LocalDate today = LocalDate.now();
        when(reviewRepository.maxPublishedDateByOrderId(10L)).thenReturn(today.minusMonths(6));

        LocalDate scheduledDate = service.nextScheduledDate(10L);

        assertEquals(today.plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS), scheduledDate);
    }

    @Test
    void nextScheduledDateKeepsFutureReviewDateAsBase() {
        LocalDate today = LocalDate.now();
        LocalDate futureReviewDate = today.plusDays(7);
        when(reviewRepository.maxPublishedDateByOrderId(10L)).thenReturn(futureReviewDate);

        LocalDate scheduledDate = service.nextScheduledDate(10L);

        assertEquals(futureReviewDate.plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS), scheduledDate);
    }

    @Test
    void nextScheduledDateKeepsFutureTaskDateAsBase() {
        LocalDate today = LocalDate.now();
        LocalDate futureTaskDate = today.plusDays(12);
        when(reviewRepository.maxPublishedDateByOrderId(10L)).thenReturn(today.plusDays(3));
        when(taskRepository.maxScheduledDateByOrderId(10L, ReviewRecoveryTaskStatus.CANCELLED))
                .thenReturn(futureTaskDate);

        LocalDate scheduledDate = service.nextScheduledDate(10L);

        assertEquals(futureTaskDate.plusDays(ReviewRecoveryGateService.RECOVERY_SCHEDULE_STEP_DAYS), scheduledDate);
    }
}
