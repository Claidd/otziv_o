package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerReportReviewDisputeRepository extends JpaRepository<ManagerReportReviewDispute, Long> {

    Optional<ManagerReportReviewDispute> findFirstByIssue_Review_IdAndStatusInOrderByCreatedAtDesc(
            Long reviewId,
            Collection<ManagerReportReviewDisputeStatus> statuses
    );

    List<ManagerReportReviewDispute> findByIssue_Review_IdOrderByCreatedAtAsc(Long reviewId);

    long countByIssue_Review_IdAndStatusIn(
            Long reviewId,
            Collection<ManagerReportReviewDisputeStatus> statuses
    );
}
