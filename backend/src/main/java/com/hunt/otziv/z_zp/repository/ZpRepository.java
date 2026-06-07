package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.z_zp.dto.ZpStatRow;
import com.hunt.otziv.z_zp.dto.ZpStatView;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public interface ZpRepository extends CrudRepository<Zp, Long>  {
    @NotNull
    List<Zp> findAll();

    List<Zp> findByOrderIdAndActiveTrue(Long orderId);

    @Query("SELECT z FROM Zp z WHERE z.userId = :userId AND z.created >= :startDate AND z.created < :endDate")
    List<Zp> getAllWorkerZpInPeriod(@Param("userId") Long userId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate);

    @Query("SELECT zp FROM Zp zp WHERE zp.userId = :userId AND zp.created >= :firstDayOfMonth AND zp.created <= :lastDayOfMonth")
    List<Zp> getAllWorkerZp(@Param("userId") Long userId,
                            @Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                            @Param("lastDayOfMonth") LocalDate lastDayOfMonth);


//    @Query("SELECT z FROM Zp z WHERE YEAR(z.created) = YEAR(:localDate) AND MONTH(z.created) = MONTH(:localDate)")
//    List<Zp> findAllToDate(LocalDate localDate);

    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate")
    List<Zp> findAllToDate(@Param("startDate") LocalDate startDate,
                           @Param("endDate") LocalDate endDate);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.ZpStatRow(z.created, z.sum, z.amount)
        FROM Zp z
        WHERE z.created >= :startDate AND z.created < :endDate
    """)
    List<ZpStatRow> findStatRowsToDate(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate AND z.userId IN :peopleId")
    List<Zp> findAllToDateByOwner(@Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate,
                                  @Param("peopleId") Set<Long> peopleId);

    @Query("""
        SELECT new com.hunt.otziv.z_zp.dto.ZpStatRow(z.created, z.sum, z.amount)
        FROM Zp z
        WHERE z.created >= :startDate AND z.created < :endDate AND z.userId IN :peopleId
    """)
    List<ZpStatRow> findStatRowsToDateByOwner(@Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate,
                                              @Param("peopleId") Set<Long> peopleId);

    @Query("SELECT z FROM Zp z WHERE z.userId IN :peopleId")
    List<Zp> findAllByOwner(@Param("peopleId") Set<Long> peopleId);


    @Query("SELECT z FROM Zp z WHERE z.created >= :startDate AND z.created < :endDate AND z.userId = :userId")
    List<Zp> findAllToDateByUser(@Param("startDate") LocalDate startDate,
                                 @Param("endDate") LocalDate endDate,
                                 @Param("userId") Long userId);

    @Query("SELECT SUM(z.sum) FROM Zp z WHERE z.userId = :userId AND z.created = :created")
    BigDecimal sumByUserAndCreated(@Param("userId") Long userId,
                                   @Param("created") LocalDate created);


//    @Query("SELECT z.fio, SUM(z.sum) as totalSum FROM Zp z WHERE z.created BETWEEN :startDate AND :endDate GROUP BY z.fio ORDER BY totalSum DESC")
//    List<Object[]> findAllToDateToMap(LocalDate startDate, LocalDate endDate);


    @Query(value = """
        SELECT
            u.fio AS fio,
            COALESCE(z.total_sum, 0) AS totalSum,
            MIN(r.name) AS role,
            COALESCE(z.total_orders, 0) AS totalOrders,
            COALESCE(z.total_amount, 0) AS totalAmount
        FROM users u
        LEFT JOIN (
            SELECT
                zp_user,
                SUM(zp_sum) AS total_sum,
                COUNT(DISTINCT zp_id) AS total_orders,
                SUM(zp_amount) AS total_amount
            FROM zp
            WHERE zp_date BETWEEN :startDate AND :endDate
            GROUP BY zp_user
        ) z ON z.zp_user = u.id
        LEFT JOIN users_roles ur ON ur.user_id = u.id
        LEFT JOIN roles r ON r.id = ur.role_id
        GROUP BY u.id, u.fio, z.total_sum, z.total_orders, z.total_amount
        ORDER BY totalSum DESC
    """, nativeQuery = true)
    List<Object[]> findAllUsersWithZpToDate(@Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);





//    @Query("""
//    SELECT u.fio,
//           COALESCE(SUM(z.sum), 0) AS totalSum,
//           (SELECT MIN(r.name)
//            FROM User u2
//            JOIN u2.roles r
//            WHERE u2.id = u.id) AS role
//    FROM User u
//    LEFT JOIN Zp z ON u.id = z.userId AND z.created BETWEEN :startDate AND :endDate
//    GROUP BY u.fio, u.id
//    ORDER BY totalSum DESC
//""")
//    List<Object[]> findAllUsersWithZpToDate(LocalDate startDate, LocalDate endDate);






}
