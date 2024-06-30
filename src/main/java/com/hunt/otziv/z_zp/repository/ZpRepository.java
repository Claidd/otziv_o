package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
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
}
