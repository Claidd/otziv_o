package com.hunt.otziv.p_products.worker_flow;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerPublicationGateRolloutService {

    private static final int ACTIVATION_DELAY_DAYS = 3;

    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRecoveryTaskRepository reviewRecoveryTaskRepository;
    private final AppSettingService appSettingService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeSpecialTaskGateRollout() {
        String activateOn = appSettingService.getString(
                AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON,
                ""
        );
        if (!activateOn.isBlank()) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate activationDate = today.plusDays(ACTIVATION_DELAY_DAYS);
        int badTasks = badReviewTaskRepository.actualizeActiveTasksBefore(BadReviewTaskStatus.NEW, today);
        int recoveryTasks = reviewRecoveryTaskRepository.actualizeActiveTasksBefore(
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN,
                today
        );

        appSettingService.setBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false);
        appSettingService.setString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ROLLOUT_STARTED_ON, today.toString());
        appSettingService.setString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON, activationDate.toString());

        log.info(
                "Worker publication special task gate rollout initialized: badTasksActualized={}, recoveryTasksActualized={}, activateOn={}",
                badTasks,
                recoveryTasks,
                activationDate
        );
    }
}
