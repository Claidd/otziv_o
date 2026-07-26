package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerReportReviewIssueRepository extends JpaRepository<ManagerReportReviewIssue, Long> {

    List<ManagerReportReviewIssue> findByReview_IdOrderByQuestionIndexAsc(Long reviewId);

    Optional<ManagerReportReviewIssue> findByReview_IdAndQuestionIndex(Long reviewId, int questionIndex);

    Optional<ManagerReportReviewIssue> findFirstByReview_IdAndStatusOrderByQuestionIndexAsc(
            Long reviewId,
            ManagerReportReviewIssueStatus status
    );

    long countByReview_IdAndStatusIn(Long reviewId, Collection<ManagerReportReviewIssueStatus> statuses);
}
