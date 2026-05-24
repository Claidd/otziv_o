package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.CompanyContact;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompanyContactRepository extends CrudRepository<CompanyContact, Long> {
    List<CompanyContact> findByCompanyIdOrderByTypeAscIdAsc(Long companyId);
}
