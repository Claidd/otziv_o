package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
@Repository
public interface ZpRepository extends CrudRepository<Zp, Long> {

    @NotNull
    List<Zp> findAll();

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.userId = :userId
          AND YEAR(z.created) = YEAR(:localDate)
          AND MONTH(z.created) = MONTH(:localDate)
        """)
    List<Zp> getAllWorkerZp(Long userId, LocalDate localDate);

    @Query("""
        SELECT zp
        FROM Zp zp
        WHERE zp.userId = :userId
          AND zp.created >= :firstDayOfMonth
          AND zp.created <= :lastDayOfMonth
        """)
    List<Zp> getAllWorkerZp(Long userId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE YEAR(z.created) = YEAR(:localDate)
           OR YEAR(z.created) = YEAR(:localDate2)
        """)
    List<Zp> findAllToDate(LocalDate localDate, LocalDate localDate2);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE (YEAR(z.created) = YEAR(:localDate) OR YEAR(z.created) = YEAR(:localDate2))
          AND z.userId IN :peopleId
        """)
    List<Zp> findAllToDateByOwner(LocalDate localDate, LocalDate localDate2, Set<Long> peopleId);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE (YEAR(z.created) = YEAR(:localDate) OR YEAR(z.created) = YEAR(:localDate2))
          AND z.userId = :userId
        """)
    List<Zp> findAllToDateByUser(LocalDate localDate, LocalDate localDate2, Long userId);

    @Query("""
        SELECT 
            u.fio,
            COALESCE(SUM(z.sum), 0) AS totalSum,
            (SELECT MIN(r.name) FROM Role r JOIN r.users ru WHERE ru.id = u.id) AS role,
            COUNT(DISTINCT z.id) AS totalOrders,
            COALESCE(SUM(z.amount), 0) AS totalAmount
        FROM User u
        LEFT JOIN Zp z ON u.id = z.userId AND z.created BETWEEN :startDate AND :endDate
        GROUP BY u.fio, u.id
        ORDER BY totalSum DESC
        """)
    List<Object[]> findAllUsersWithZpToDate(LocalDate startDate, LocalDate endDate);

    @Query("""
        SELECT z.userId, COALESCE(SUM(z.sum), 0), COUNT(z.id), COALESCE(SUM(z.amount), 0)
        FROM Zp z
        WHERE z.userId IN :userIds
          AND z.created >= :startDate
          AND z.created <= :endDate
        GROUP BY z.userId
        """)
    List<Object[]> aggregateUserZpByUserIds(Set<Long> userIds, LocalDate startDate, LocalDate endDate);

    @Query("""
        SELECT z.userId, COALESCE(SUM(z.sum), 0), COUNT(z.id), COALESCE(SUM(z.amount), 0)
        FROM Zp z
        WHERE z.userId IN :userIds
          AND z.created >= :startDate
          AND z.created <= :endDate
        GROUP BY z.userId
        """)
    List<Object[]> aggregateManagerZpByUserIds(Set<Long> userIds, LocalDate startDate, LocalDate endDate);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.created >= :firstDayOfMonth
          AND z.created <= :lastDayOfMonth
          AND z.userId IN :userIds
        """)
    List<Zp> findAllToDateByUserIds(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                    @Param("lastDayOfMonth") LocalDate lastDayOfMonth,
                                    @Param("userIds") Set<Long> userIds);

    @Query("""
        SELECT z
        FROM Zp z
        WHERE z.created >= :firstDayOfMonth
          AND z.created <= :lastDayOfMonth
          AND z.userId IN :userIds
        """)
    List<Zp> findAllToDateByUserIds(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                    @Param("lastDayOfMonth") LocalDate lastDayOfMonth,
                                    @Param("userIds") List<Long> userIds);
}
