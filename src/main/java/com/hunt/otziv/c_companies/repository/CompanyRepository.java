package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import org.springframework.data.repository.CrudRepository;

public interface CompanyRepository extends CrudRepository<Company, Long> {
}
