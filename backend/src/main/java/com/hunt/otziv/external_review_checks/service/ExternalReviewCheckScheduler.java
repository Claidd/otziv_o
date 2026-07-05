package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalReviewCheckScheduler {

    private final ExternalReviewCheckService service;
    private final ExternalReviewCheckProperties properties;

    @Scheduled(
            fixedDelayString = "${external-review-check.scheduler.fixed-delay-ms:3600000}",
            initialDelayString = "${external-review-check.scheduler.initial-delay-ms:120000}"
    )
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }

        int enqueued = service.enqueueDueCandidates();
        int processed = service.processDueChecks();
        if (enqueued > 0 || processed > 0) {
            log.info("External review checks tick: enqueued={}, processed={}", enqueued, processed);
        }
    }
}
