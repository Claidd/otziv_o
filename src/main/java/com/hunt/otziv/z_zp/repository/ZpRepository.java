package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public interface ZpRepository extends CrudRepository<Zp, Long>  {
    @NotNull
    List<Zp> findAll();

    @Query("SELECT z FROM Zp z WHERE z.userId = :userId AND YEAR(z.created) = YEAR(:localDate) AND MONTH(z.created) = MONTH(:localDate)")
    List<Zp> getAllWorkerZp(Long userId, LocalDate localDate);

    @Query("SELECT zp FROM Zp zp WHERE zp.userId = :userId AND zp.created >= :firstDayOfMonth AND zp.created <= :lastDayOfMonth")
    List<Zp> getAllWorkerZp(Long userId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);


//    @Query("SELECT z FROM Zp z WHERE YEAR(z.created) = YEAR(:localDate) AND MONTH(z.created) = MONTH(:localDate)")
//    List<Zp> findAllToDate(LocalDate localDate);

    @Query("SELECT z FROM Zp z WHERE YEAR(z.created) = YEAR(:localDate) OR YEAR(z.created) = YEAR(:localDate2)")
    List<Zp> findAllToDate(LocalDate localDate, LocalDate localDate2);

    @Query("SELECT z FROM Zp z WHERE (YEAR(z.created) = YEAR(:localDate) OR YEAR(z.created) = YEAR(:localDate2)) AND z.userId IN :peopleId")
    List<Zp> findAllToDateByOwner(LocalDate localDate, LocalDate localDate2, Set<Long> peopleId);


    @Query("SELECT z FROM Zp z WHERE (YEAR(z.created) = YEAR(:localDate) OR YEAR(z.created) = YEAR(:localDate2)) AND z.userId = :userId")
    List<Zp> findAllToDateByUser(LocalDate localDate, LocalDate localDate2, Long userId);


//    @Query("SELECT z.fio, SUM(z.sum) as totalSum FROM Zp z WHERE z.created BETWEEN :startDate AND :endDate GROUP BY z.fio ORDER BY totalSum DESC")
//    List<Object[]> findAllToDateToMap(LocalDate startDate, LocalDate endDate);


    @Query("""
    SELECT u.fio, 
           COALESCE(SUM(z.sum), 0) AS totalSum, 
           (SELECT MIN(r.name) 
            FROM User u2 
            JOIN u2.roles r 
            WHERE u2.id = u.id) AS role 
    FROM User u
    LEFT JOIN Zp z ON u.id = z.userId AND z.created BETWEEN :startDate AND :endDate
    GROUP BY u.fio, u.id
    ORDER BY totalSum DESC
""")
    List<Object[]> findAllUsersWithZpToDate(LocalDate startDate, LocalDate endDate);






}
