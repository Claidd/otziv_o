package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.z_zp.dto.PaymentCheckStatRow;
import com.hunt.otziv.z_zp.dto.PaymentCheckStatView;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaymentCheckRepository extends CrudRepository<PaymentCheck, Long>  {
    @NotNull
    List<PaymentCheck> findAll();

//    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate) AND MONTH(p.created) = MONTH(:localDate)")
//    List<PaymentCheck> findAllToDate(LocalDate localDate);

//    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate)")
//    List<PaymentCheck> findAllToDate(LocalDate localDate);

    @Query("SELECT p FROM PaymentCheck p WHERE p.created >= :startDate AND p.created < :endDate")
    List<PaymentCheck> findAllToDate(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.PaymentCheckStatRow(p.created, p.sum)
        FROM PaymentCheck p
        WHERE p.created >= :startDate AND p.created < :endDate
    """)
    List<PaymentCheckStatRow> findStatRowsToDate(@Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);

    @Query("SELECT p FROM PaymentCheck p WHERE p.created >= :startDate AND p.created < :endDate AND p.managerId IN :managers")
    List<PaymentCheck> findAllToDateByManagers(@Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate,
                                                @Param("managers") List<Long> managers);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.PaymentCheckStatRow(p.created, p.sum)
        FROM PaymentCheck p
        WHERE p.created >= :startDate AND p.created < :endDate AND p.managerId IN :managers
    """)
    List<PaymentCheckStatRow> findStatRowsToDateByManagers(@Param("startDate") LocalDate startDate,
                                                           @Param("endDate") LocalDate endDate,
                                                           @Param("managers") List<Long> managers);

    @Query("SELECT p FROM PaymentCheck p WHERE p.managerId IN :managers")
    List<PaymentCheck> findAllByManagers(@Param("managers") List<Long> managers);

    @Query("SELECT p FROM PaymentCheck p WHERE p.managerId = :managerId AND p.created >= :firstDayOfMonth AND p.created <= :lastDayOfMonth")
    List<PaymentCheck> getAllWorkerPayments(@Param("managerId") Long managerId,
                                             @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                             @Param("lastDayOfMonth") LocalDate lastDayOfMonth);

    @Query("""
    SELECT u.fio, SUM(pc.sum)
    FROM PaymentCheck pc
    JOIN User u ON (pc.managerId = u.id OR pc.workerId = u.id)
    WHERE pc.created BETWEEN :startDate AND :endDate
    AND pc.active = true
    GROUP BY u.fio
    ORDER BY SUM(pc.sum) DESC
""")
    List<Object[]> findAllToDateToMap(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);




}
