package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import org.springframework.data.repository.CrudRepository;

import java.util.Set;

public interface CompanyRepository extends CrudRepository<Company, Long> {

    @Override
    Set<Company> findAll();
}
