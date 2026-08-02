package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.model.ReviewExternalCheck;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckTransactionService.ClaimedCheck;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Coordinates external-review work. Database mutations live in the separate
 * transactional bean; worker HTTP and screenshot S3 calls therefore never run
 * while a database transaction is open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalReviewCheckService {

    private static final String DEDUP_CONSTRAINT = "uk_review_external_checks_dedup_hash";

    private static final List<ExternalReviewCheckStatus> DUE_STATUSES = List.of(
            ExternalReviewCheckStatus.PENDING,
            ExternalReviewCheckStatus.NOT_FOUND,
            ExternalReviewCheckStatus.ERROR
    );

    private final ReviewExternalCheckRepository checkRepository;
    private final ExternalReviewWorkerClient workerClient;
    private final ExternalReviewScreenshotStorage screenshotStorage;
    private final ExternalReviewCheckProperties properties;
    private final ExternalReviewCheckRuntimeSwitch runtimeSwitch;
    private final ExternalReviewCheckTransactionService transactions;

    public int enqueueDueCandidates() {
        if (!runtimeSwitch.isEnabled()) {
            return 0;
        }

        int batchSize = batchSize();
        LocalDateTime threshold = currentTime()
                .minusDays(Math.max(0, properties.getConfirmationDelayDays()));
        List<Long> reviewIds = checkRepository.findCandidateReviewIds(
                threshold,
                PageRequest.of(0, batchSize)
        );

        int created = 0;
        for (Long reviewId : reviewIds) {
            if (!runtimeSwitch.isEnabled()) {
                break;
            }
            try {
                if (transactions.createAutomaticCheck(reviewId)) {
                    created++;
                }
            } catch (DataIntegrityViolationException duplicateClaim) {
                // Two nodes may select the same NOT EXISTS candidate. The
                // deterministic nullable hash is the final deduplication gate.
                if (isAutomaticDedupConflict(duplicateClaim)
                        && transactions.automaticDedupExists(reviewId)) {
                    log.debug("External review candidate already enqueued: reviewId={}", reviewId);
                } else {
                    throw duplicateClaim;
                }
            }
        }

        if (created > 0) {
            log.info("Поставлено проверок внешнего наличия отзывов: {}", created);
        }
        return created;
    }

    public int processDueChecks() {
        if (!runtimeSwitch.isEnabled()) {
            return 0;
        }

        LocalDateTime now = currentTime();
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        int batchSize = batchSize();

        // Legacy CHECKING rows and crashed claims at the retry ceiling cannot
        // win a new claim; surface them as ERROR instead of leaving them stuck.
        List<Long> exhausted = checkRepository.findExhaustedStaleCheckingIds(
                ExternalReviewCheckStatus.CHECKING,
                now,
                maxAttempts,
                PageRequest.of(0, batchSize)
        );
        for (Long checkId : exhausted) {
            if (!runtimeSwitch.isEnabled()) {
                return 0;
            }
            transactions.recoverExhaustedClaim(checkId);
        }

        List<Long> checkIds = new ArrayList<>(checkRepository.findStaleCheckingIds(
                ExternalReviewCheckStatus.CHECKING,
                now,
                maxAttempts,
                PageRequest.of(0, batchSize)
        ));
        int remaining = batchSize - checkIds.size();
        if (remaining > 0) {
            checkIds.addAll(checkRepository.findDueClaimableIds(
                    DUE_STATUSES,
                    now,
                    maxAttempts,
                    PageRequest.of(0, remaining)
            ));
        }

        int processed = 0;
        for (Long checkId : checkIds) {
            if (!runtimeSwitch.isEnabled()) {
                break;
            }
            if (processOne(checkId)) {
                processed++;
            }
        }
        return processed;
    }

    public ReviewExternalCheck createManualCheck(Long orderId, Long reviewId) {
        requireEnabled();
        return transactions.createManualCheck(orderId, reviewId);
    }

    public ReviewExternalCheck runManualCheck(Long orderId, Long reviewId) {
        ReviewExternalCheck check = createManualCheck(orderId, reviewId);
        processOne(check.getId());
        return transactions.findForRead(check.getId()).orElse(check);
    }

    /**
     * @return true only when this node won the claim and performed a worker
     *         attempt (including an attempt that ended in an upstream error)
     */
    public boolean processOne(Long checkId) {
        if (!runtimeSwitch.isEnabled()) {
            return false;
        }
        ClaimedCheck claim = transactions.claim(checkId).orElse(null);
        if (claim == null) {
            return false;
        }

        if (!runtimeSwitch.isEnabled()) {
            transactions.releaseUnconsumed(claim);
            return false;
        }

        ExternalReviewWorkerResponse response;
        try {
            response = workerClient.verify(claim.request());
        } catch (ExternalReviewWorkerDisabledException disabled) {
            transactions.releaseUnconsumed(claim);
            return false;
        } catch (Exception exception) {
            // Fresh-read before scheduling a retry. A false value leaves the
            // retry timestamp persisted but the scheduler remains inert.
            boolean enabledBeforeFailure = runtimeSwitch.isEnabled();
            String failureCode = failureCode(exception);
            log.warn(
                    "External review check failed: checkId={}, failureType={}, runtimeEnabled={}",
                    checkId,
                    failureCode,
                    enabledBeforeFailure
            );
            if (!transactions.fail(claim, failureCode)) {
                log.warn("Ignored stale external review failure: checkId={}", claim.checkId());
            }
            return true;
        }

        // A switch-off after the worker returned cannot safely discard the
        // result (that would repeat external I/O after re-enable), but it must
        // prevent the additional S3 side effect. Screenshot validation/upload
        // is also best-effort: its failure must not turn a completed provider
        // call into a retry of that provider call.
        boolean enabledAfterWorker = runtimeSwitch.isEnabled();
        ExternalReviewScreenshotStorage.StoredScreenshot screenshot = null;
        if (enabledAfterWorker
                && ExternalReviewWorkerResponsePolicy.evaluate(
                        response,
                        claim.checkId()
                ).evidenceAccepted()) {
            try {
                screenshot = storeScreenshot(claim, response);
            } catch (Exception screenshotFailure) {
                log.warn(
                        "External review screenshot skipped: checkId={}, failureType={}",
                        claim.checkId(),
                        safeFailureType(screenshotFailure)
                );
            }
        }
        boolean completed;
        try {
            completed = transactions.complete(claim, response, screenshot);
        } catch (RuntimeException completionFailure) {
            screenshotStorage.deleteBestEffort(screenshot);
            throw completionFailure;
        }
        if (!completed) {
            screenshotStorage.deleteBestEffort(screenshot);
            log.warn("Ignored stale external review completion: checkId={}", claim.checkId());
        } else if (claim.previousScreenshotKey() != null
                && (screenshot == null
                    || !claim.previousScreenshotKey().equals(screenshot.key()))) {
            screenshotStorage.deleteBestEffort(claim.previousScreenshotKey());
        }
        return true;
    }

    private ExternalReviewScreenshotStorage.StoredScreenshot storeScreenshot(
            ClaimedCheck claim,
            ExternalReviewWorkerResponse response
    ) {
        if (response == null
                || response.screenshotBase64() == null
                || response.screenshotBase64().isBlank()) {
            return null;
        }
        return screenshotStorage.store(
                claim.checkId(),
                claim.reviewId(),
                response.screenshotBase64(),
                response.screenshotContentType()
        );
    }

    private int batchSize() {
        return Math.max(1, properties.getBatchSize());
    }

    private LocalDateTime currentTime() {
        LocalDateTime databaseTime = transactions.currentDatabaseTime();
        return databaseTime != null ? databaseTime : LocalDateTime.now();
    }

    private void requireEnabled() {
        if (!runtimeSwitch.isEnabled()) {
            throw new ExternalReviewWorkerDisabledException();
        }
    }

    private String failureCode(Exception exception) {
        return "worker_exception:" + safeFailureType(exception);
    }

    private String safeFailureType(Exception exception) {
        String type = exception == null ? "unknown" : exception.getClass().getSimpleName();
        String sanitized = type == null ? "unknown" : type.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (sanitized.isBlank()) {
            sanitized = "unknown";
        }
        return sanitized.length() <= 96 ? sanitized : sanitized.substring(0, 96);
    }

    private boolean isAutomaticDedupConflict(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && DEDUP_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(java.util.Locale.ROOT).contains(DEDUP_CONSTRAINT)) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
