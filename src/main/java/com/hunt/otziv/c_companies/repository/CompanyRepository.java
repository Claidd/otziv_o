package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompanyRepository extends CrudRepository<Company, Long> {

    @Override
    Set<Company> findAll();

    boolean existsBySubCategoryId(Long reviewSubcategoryId);

    Optional<Company> findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(Long id, String keyword, String keyword2);
    Set<Company> findALLByTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(String keyword, String keyword2);
}
