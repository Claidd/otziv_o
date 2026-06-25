package com.hunt.otziv.p_products.worker_flow;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerPublicationGateRolloutServiceTest {

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private ReviewRecoveryTaskRepository reviewRecoveryTaskRepository;

    @Mock
    private AppSettingService appSettingService;

    @Test
    void initializeSpecialTaskGateRolloutActualizesDatesAndSchedulesActivationOnce() {
        LocalDate today = LocalDate.now();

        when(appSettingService.getString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON, ""))
                .thenReturn("");
        when(badReviewTaskRepository.actualizeActiveTasksBefore(BadReviewTaskStatus.NEW, today))
                .thenReturn(4);
        when(reviewRecoveryTaskRepository.actualizeActiveTasksBefore(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                today
        )).thenReturn(6);

        service().initializeSpecialTaskGateRollout();

        verify(badReviewTaskRepository).actualizeActiveTasksBefore(BadReviewTaskStatus.NEW, today);
        verify(reviewRecoveryTaskRepository).actualizeActiveTasksBefore(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                today
        );
        verify(appSettingService).setBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false);
        verify(appSettingService).setString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ROLLOUT_STARTED_ON, today.toString());
        verify(appSettingService).setString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON, today.plusDays(3).toString());
    }

    @Test
    void initializeSpecialTaskGateRolloutDoesNothingWhenActivationDateAlreadyExists() {
        when(appSettingService.getString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON, ""))
                .thenReturn(LocalDate.now().plusDays(1).toString());

        service().initializeSpecialTaskGateRollout();

        verify(appSettingService, never()).setBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false);
        verifyNoInteractions(badReviewTaskRepository, reviewRecoveryTaskRepository);
    }

    private WorkerPublicationGateRolloutService service() {
        return new WorkerPublicationGateRolloutService(
                badReviewTaskRepository,
                reviewRecoveryTaskRepository,
                appSettingService
        );
    }
}
