package com.hunt.otziv.analytics.repository;

import com.hunt.otziv.analytics.model.AnalyticsMonthlyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AnalyticsMonthlyUserRepository extends JpaRepository<AnalyticsMonthlyUser, Long> {

    @Query("""
            select monthly
            from AnalyticsMonthlyUser monthly
            where monthly.monthStart between :fromMonthInclusive and :toMonthInclusive
            order by monthly.monthStart, monthly.user.id
            """)
    List<AnalyticsMonthlyUser> findAllInMonthPeriod(
            @Param("fromMonthInclusive") LocalDate fromMonthInclusive,
            @Param("toMonthInclusive") LocalDate toMonthInclusive
    );

    @Query("""
            select monthly
            from AnalyticsMonthlyUser monthly
            where monthly.user.id in :userIds
              and monthly.monthStart between :fromMonthInclusive and :toMonthInclusive
            order by monthly.monthStart, monthly.user.id
            """)
    List<AnalyticsMonthlyUser> findByUserIdsInMonthPeriod(
            @Param("userIds") Collection<Long> userIds,
            @Param("fromMonthInclusive") LocalDate fromMonthInclusive,
            @Param("toMonthInclusive") LocalDate toMonthInclusive
    );

    @Query("""
            select monthly
            from AnalyticsMonthlyUser monthly
            where monthly.roleName = :roleName
              and monthly.monthStart between :fromMonthInclusive and :toMonthInclusive
            order by monthly.monthStart, monthly.user.id
            """)
    List<AnalyticsMonthlyUser> findByRoleNameInMonthPeriod(
            @Param("roleName") String roleName,
            @Param("fromMonthInclusive") LocalDate fromMonthInclusive,
            @Param("toMonthInclusive") LocalDate toMonthInclusive
    );
}
