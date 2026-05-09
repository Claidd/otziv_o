package com.hunt.otziv.analytics.repository;

import com.hunt.otziv.analytics.model.AnalyticsMonthlyTotal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AnalyticsMonthlyTotalRepository extends JpaRepository<AnalyticsMonthlyTotal, Long> {

    Optional<AnalyticsMonthlyTotal> findByScopeKeyAndMonthStart(String scopeKey, LocalDate monthStart);

    @Query("""
            select total
            from AnalyticsMonthlyTotal total
            where total.scopeKey = :scopeKey
              and total.monthStart between :fromMonthInclusive and :toMonthInclusive
            order by total.monthStart
            """)
    List<AnalyticsMonthlyTotal> findByScopeKeyInMonthPeriod(
            @Param("scopeKey") String scopeKey,
            @Param("fromMonthInclusive") LocalDate fromMonthInclusive,
            @Param("toMonthInclusive") LocalDate toMonthInclusive
    );

    @Query("""
            select total
            from AnalyticsMonthlyTotal total
            where total.scopeType = :scopeType
              and total.scopeUser.id = :scopeUserId
              and total.monthStart between :fromMonthInclusive and :toMonthInclusive
            order by total.monthStart
            """)
    List<AnalyticsMonthlyTotal> findByScopeUserInMonthPeriod(
            @Param("scopeType") String scopeType,
            @Param("scopeUserId") Long scopeUserId,
            @Param("fromMonthInclusive") LocalDate fromMonthInclusive,
            @Param("toMonthInclusive") LocalDate toMonthInclusive
    );
}
