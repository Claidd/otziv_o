package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.z_zp.model.PaymentCheck;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
@Repository
public interface PaymentCheckRepository extends CrudRepository<PaymentCheck, Long>  {
    @NotNull
    List<PaymentCheck> findAll();

    @Query("SELECT p FROM PaymentCheck p WHERE YEAR(p.created) = YEAR(:localDate) AND MONTH(p.created) = MONTH(:localDate)")
    List<PaymentCheck> findAllToDate(LocalDate localDate);
}
