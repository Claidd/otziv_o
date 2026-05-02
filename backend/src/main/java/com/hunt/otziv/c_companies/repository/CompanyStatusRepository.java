package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.p_products.model.OrderStatus;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface CompanyStatusRepository extends CrudRepository<CompanyStatus, Long> {

    @Override
    Optional<CompanyStatus> findById(Long id);

    Optional<CompanyStatus> findByTitle(String title);
}
