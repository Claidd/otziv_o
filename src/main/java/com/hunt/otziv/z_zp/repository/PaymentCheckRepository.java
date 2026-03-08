package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Repository
public interface PaymentCheckRepository extends CrudRepository<PaymentCheck, Long> {

    @NotNull
    List<PaymentCheck> findAll();

    @Query("""
            SELECT p
            FROM PaymentCheck p
            WHERE YEAR(p.created) = YEAR(:localDate)
               OR YEAR(p.created) = YEAR(:localDate2)
            """)
    List<PaymentCheck> findAllToDate(LocalDate localDate, LocalDate localDate2);

    @Query("""
            SELECT p
            FROM PaymentCheck p
            WHERE (YEAR(p.created) = YEAR(:localDate) OR YEAR(p.created) = YEAR(:localDate2))
              AND p.managerId IN :managers
            """)
    List<PaymentCheck> findAllToDateByManagers(LocalDate localDate, LocalDate localDate2, List<Long> managers);

    @Query("""
            SELECT p
            FROM PaymentCheck p
            WHERE p.managerId = :managerId
              AND p.created >= :firstDayOfMonth
              AND p.created <= :lastDayOfMonth
            """)
    List<PaymentCheck> getAllWorkerPayments(Long managerId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    @Query("""
            SELECT u.fio, SUM(pc.sum)
            FROM PaymentCheck pc
            JOIN User u ON (pc.managerId = u.id OR pc.workerId = u.id)
            WHERE pc.created BETWEEN :startDate AND :endDate
              AND pc.active = true
            GROUP BY u.fio
            ORDER BY SUM(pc.sum) DESC
            """)
    List<Object[]> findAllToDateToMap(LocalDate startDate, LocalDate endDate);

    // ====== NEW: активные суммы по менеджерам ======
    @Query("""
            SELECT p.managerId, COALESCE(SUM(p.sum), 0)
            FROM PaymentCheck p
            WHERE p.managerId IN :managerIds
              AND p.created >= :startDate
              AND p.created <= :endDate
              AND p.active = true
            GROUP BY p.managerId
            """)
    List<Object[]> aggregateActiveManagerPayments(Set<Long> managerIds, LocalDate startDate, LocalDate endDate);

    // ====== NEW: активные суммы по работникам ======
    @Query("""
            SELECT p.workerId, COALESCE(SUM(p.sum), 0)
            FROM PaymentCheck p
            WHERE p.workerId IN :workerIds
              AND p.created >= :startDate
              AND p.created <= :endDate
              AND p.active = true
            GROUP BY p.workerId
            """)
    List<Object[]> aggregateActiveWorkerPayments(Set<Long> workerIds, LocalDate startDate, LocalDate endDate);

    // (опционально) если где-то ещё используется старое имя
    @Query("""
            SELECT p.managerId, COALESCE(SUM(p.sum), 0)
            FROM PaymentCheck p
            WHERE p.managerId IN :managerIds
              AND p.created >= :startDate
              AND p.created <= :endDate
            GROUP BY p.managerId
            """)
    List<Object[]> aggregateManagerPayments(Set<Long> managerIds, LocalDate startDate, LocalDate endDate);

    @Query("""
    SELECT pc
    FROM PaymentCheck pc
    WHERE pc.created >= :firstDayOfMonth
      AND pc.created <= :lastDayOfMonth
      AND (
            pc.managerId IN :userIds
         OR pc.workerId IN :userIds
      )
""")
    List<PaymentCheck> findAllToDateByUserIds(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                              @Param("lastDayOfMonth") LocalDate lastDayOfMonth,
                                              @Param("userIds") Set<Long> userIds);

    @Query("""
    SELECT pc
    FROM PaymentCheck pc
    WHERE pc.created >= :firstDayOfMonth
      AND pc.created <= :lastDayOfMonth
      AND (
           pc.managerId IN :userIds
           OR pc.workerId IN :userIds
      )
""")
    List<PaymentCheck> findAllToDateByRelevantUserIds(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                                      @Param("lastDayOfMonth") LocalDate lastDayOfMonth,
                                                      @Param("userIds") List<Long> userIds);
}