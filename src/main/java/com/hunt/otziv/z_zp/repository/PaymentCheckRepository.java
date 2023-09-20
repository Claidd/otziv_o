package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.z_zp.model.PaymentCheck;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface PaymentCheckRepository extends CrudRepository<PaymentCheck, Long>  {
    @NotNull
    List<PaymentCheck> findAll();
}
