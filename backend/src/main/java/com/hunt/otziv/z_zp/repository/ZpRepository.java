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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public interface ZpRepository extends CrudRepository<Zp, Long>  {
    @NotNull
    List<Zp> findAll();

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


//    @Query("SELECT z.fio, SUM(z.sum) as totalSum FROM Zp z WHERE z.created BETWEEN :startDate AND :endDate GROUP BY z.fio ORDER BY totalSum DESC")
//    List<Object[]> findAllToDateToMap(LocalDate startDate, LocalDate endDate);


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
