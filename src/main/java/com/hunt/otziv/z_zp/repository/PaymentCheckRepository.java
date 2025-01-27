package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public interface PaymentCheckRepository extends CrudRepository<PaymentCheck, Long>  {
    @NotNull
    List<PaymentCheck> findAll();

//    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate) AND MONTH(p.created) = MONTH(:localDate)")
//    List<PaymentCheck> findAllToDate(LocalDate localDate);

//    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate)")
//    List<PaymentCheck> findAllToDate(LocalDate localDate);

    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate) OR YEAR(p.created) = YEAR(:localDate2)")
    List<PaymentCheck> findAllToDate(LocalDate localDate, LocalDate localDate2);

    @Query("SELECT p FROM PaymentCheck p WHERE (YEAR(p.created) = YEAR(:localDate) OR YEAR(p.created) = YEAR(:localDate2)) AND p.managerId IN :managers")
    List<PaymentCheck> findAllToDateByManagers(LocalDate localDate, LocalDate localDate2, List<Long> managers);

    @Query("SELECT p FROM PaymentCheck p WHERE p.managerId = :managerId AND p.created >= :firstDayOfMonth AND p.created <= :lastDayOfMonth")
    List<PaymentCheck> getAllWorkerPayments(Long managerId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);
}
