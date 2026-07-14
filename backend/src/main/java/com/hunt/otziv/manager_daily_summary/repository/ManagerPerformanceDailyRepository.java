package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerPerformanceDaily;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerPerformanceDailyRepository extends JpaRepository<ManagerPerformanceDaily, Long> {
    Optional<ManagerPerformanceDaily> findBySummaryDateAndManager_Id(LocalDate date, Long managerId);
    List<ManagerPerformanceDaily> findBySummaryDateOrderByAdjustedScoreDesc(LocalDate date);
    List<ManagerPerformanceDaily> findBySummaryDateBetweenOrderByManager_IdAscSummaryDateAsc(LocalDate from, LocalDate to);
    boolean existsBySummaryDateAndAggregationStatusIn(LocalDate date, java.util.Collection<String> statuses);
    Optional<ManagerPerformanceDaily> findTopByManager_IdAndSummaryDateLessThanOrderBySummaryDateDesc(Long managerId, LocalDate date);
    Optional<ManagerPerformanceDaily> findTopByOrderBySummaryDateAsc();
    List<ManagerPerformanceDaily> findByManager_IdAndSummaryDateBetween(Long managerId, LocalDate from, LocalDate to);
}
