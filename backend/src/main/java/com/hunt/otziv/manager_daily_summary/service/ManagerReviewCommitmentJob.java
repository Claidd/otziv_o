package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.repository.ManagerReviewCommitmentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReviewCommitmentJob {
    private final ManagerReviewCommitmentRepository repository;
    private final ManagerReportReviewTaskContextService taskContext;

    @Scheduled(fixedDelayString = "${manager.report-review.commitment-check-ms:60000}")
    @Transactional
    public void verifyResults() {
        LocalDateTime now = LocalDateTime.now();
        for (var promise : repository.findByStatusInOrderByCheckedAtAsc(
                ManagerReportReviewCommitmentService.OPEN, PageRequest.of(0, 100))) {
            if (taskContext.resolvedSatisfactorily(promise.getSourceTaskId())) {
                // Closure proves the case no longer needs follow-up, not who performed every promised action.
                promise.setStatus("CASE_RESOLVED");
            } else if (!promise.getDueAt().isAfter(now)) {
                promise.setStatus("OVERDUE");
            }
            promise.setCheckedAt(now);
            repository.save(promise);
        }
    }
}
