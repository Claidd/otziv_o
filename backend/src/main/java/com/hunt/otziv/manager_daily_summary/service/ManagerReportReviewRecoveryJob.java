package com.hunt.otziv.manager_daily_summary.service;

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
    private final PlatformTransactionManager transactionManager;
    private long sourceRefreshCursor;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recover() {
        if (!reviews.enabled()) return;
        var sources = repository.findSourceRefreshCandidates(sourceRefreshCursor);
        if (sources.isEmpty() && sourceRefreshCursor != 0) {
            sourceRefreshCursor = 0;
            sources = repository.findSourceRefreshCandidates(0);
        }
        for (Long id : sources) {
            sourceRefreshCursor = id;
            try {
                reviews.refreshSystemSourceQuestion(id);
            } catch (RuntimeException failure) {
                log.warn("Manager audit source refresh failed reviewId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        var transaction = new TransactionTemplate(transactionManager);
        for (Long id : repository.findRecoveryCandidates(now, now.minusMinutes(1))) {
            try {
                LocalDateTime claimTime = LocalDateTime.now();
                // The ten-minute lease exceeds the bounded four-minute provider deadline.
                Integer claimed = transaction.execute(ignored -> repository.claimRecovery(
                        id, claimTime, claimTime.minusMinutes(1), claimTime.plusMinutes(10)));
                if (Integer.valueOf(1).equals(claimed)) reviews.recoverAutomatically(id);
            } catch (RuntimeException failure) {
                // No employee answer, evidence or provider body in operational logs.
                log.warn("Manager audit automatic recovery failed reviewId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }
}
