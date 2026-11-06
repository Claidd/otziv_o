package com.hunt.otziv.analytics.repository;

import com.hunt.otziv.analytics.model.AnalyticsDailyTotal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AnalyticsDailyTotalRepository extends JpaRepository<AnalyticsDailyTotal, Long> {

    @Query("""
            select total
            from AnalyticsDailyTotal total
            where total.scopeKey = :scopeKey
              and total.metricDate between :fromInclusive and :toInclusive
            order by total.metricDate
            """)
    List<AnalyticsDailyTotal> findByScopeKeyInPeriod(
            @Param("scopeKey") String scopeKey,
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toInclusive") LocalDate toInclusive
    );
}
