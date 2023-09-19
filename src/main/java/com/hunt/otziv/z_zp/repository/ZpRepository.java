package com.hunt.otziv.z_zp.repository;

import com.hunt.otziv.r_review.model.Amount;
import com.hunt.otziv.z_zp.model.Zp;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ZpRepository extends CrudRepository<Zp, Long>  {
    @NotNull
    List<Zp> findAll();
}
