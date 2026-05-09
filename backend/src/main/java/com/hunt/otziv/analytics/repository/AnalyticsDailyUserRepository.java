package com.hunt.otziv.analytics.repository;

import com.hunt.otziv.analytics.model.AnalyticsDailyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AnalyticsDailyUserRepository extends JpaRepository<AnalyticsDailyUser, Long> {

    @Query("""
            select daily
            from AnalyticsDailyUser daily
            where daily.metricDate between :fromInclusive and :toInclusive
            order by daily.metricDate, daily.user.id
            """)
    List<AnalyticsDailyUser> findAllInPeriod(
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toInclusive") LocalDate toInclusive
    );

    @Query("""
            select daily
            from AnalyticsDailyUser daily
            where daily.user.id in :userIds
              and daily.metricDate between :fromInclusive and :toInclusive
            order by daily.metricDate, daily.user.id
            """)
    List<AnalyticsDailyUser> findByUserIdsInPeriod(
            @Param("userIds") Collection<Long> userIds,
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toInclusive") LocalDate toInclusive
    );

    @Query("""
            select daily
            from AnalyticsDailyUser daily
            where daily.roleName = :roleName
              and daily.metricDate between :fromInclusive and :toInclusive
            order by daily.metricDate, daily.user.id
            """)
    List<AnalyticsDailyUser> findByRoleNameInPeriod(
            @Param("roleName") String roleName,
            @Param("fromInclusive") LocalDate fromInclusive,
            @Param("toInclusive") LocalDate toInclusive
    );
}
