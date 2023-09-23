package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompanyRepository extends CrudRepository<Company, Long> {

    @Override
    List<Company> findAll();

    List<Company> findAllByManager(Manager manager);

    boolean existsBySubCategoryId(Long reviewSubcategoryId);

    Optional<Company> findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(Long id, String keyword, String keyword2);

    List<Company> findALLByTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(String keyword, String keyword2);

    List<Company> findAllByManagerAndTitleContainingIgnoreCaseOrManagerAndTelephoneContainingIgnoreCase(Manager manager,String keyword,Manager manager2,String keyword2);
}
