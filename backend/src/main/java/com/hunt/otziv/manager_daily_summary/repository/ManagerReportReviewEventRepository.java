package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerReportReviewEventRepository extends JpaRepository<ManagerReportReviewEvent, Long> {
    List<ManagerReportReviewEvent> findByReview_IdOrderByCreatedAtAsc(Long reviewId);
}
