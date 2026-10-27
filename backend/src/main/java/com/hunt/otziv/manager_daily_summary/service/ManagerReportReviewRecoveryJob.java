package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable retries use the same locked state machine as Telegram answers. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerReportReviewRecoveryJob {
    private final ManagerReportReviewSessionRepository repository;
    private final ManagerReportReviewTelegramService reviews;
    private final AppSettingService settings;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recover() {
        if (!settings.getBoolean("manager.report-review.enabled", true)) return;
        LocalDateTime now = LocalDateTime.now();
        var transaction = new TransactionTemplate(transactionManager);
        for (Long id : repository.findRecoveryCandidates(now, now.minusMinutes(1))) {
            try {
                // The ten-minute lease exceeds the bounded four-minute provider deadline.
                Integer claimed = transaction.execute(ignored -> repository.claimRecovery(
                        id, now, now.minusMinutes(1), now.plusMinutes(10)));
                if (Integer.valueOf(1).equals(claimed)) reviews.recoverAutomatically(id);
            } catch (RuntimeException failure) {
                // No employee answer, evidence or provider body in operational logs.
                log.warn("Manager audit automatic recovery failed reviewId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }
}
