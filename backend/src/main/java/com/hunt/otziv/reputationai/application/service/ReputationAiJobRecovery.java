package com.hunt.otziv.reputationai.application.service;

import com.hunt.otziv.reputationai.persistence.repository.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.repository.ReputationDeepReportJobRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deep reports and content packs run on an in-memory executor of this single application
 * instance. A restart drops that queue, and a job left QUEUED/RUNNING blocked every new run for
 * its company. On startup such jobs are marked failed so users can start them again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationAiJobRecovery {

    static final String INTERRUPTED_MESSAGE = "Задача прервана перезапуском сервера. Запустите её снова.";

    private final ReputationDeepReportJobRepository deepReportJobs;
    private final ReputationContentPackJobRepository contentPackJobs;

    @EventListener(ApplicationReadyEvent.class)
    public void failJobsInterruptedByRestart() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int deep = deepReportJobs.failInterruptedJobs(now, INTERRUPTED_MESSAGE);
            int packs = contentPackJobs.failInterruptedJobs(now, INTERRUPTED_MESSAGE);
            if (deep > 0 || packs > 0) {
                log.warn("REPUTATION_AI_JOBS_INTERRUPTED deepReports={} contentPacks={}", deep, packs);
            }
        } catch (RuntimeException failure) {
            log.warn("Не удалось завершить прерванные AI-задачи при старте", failure);
        }
    }
}
