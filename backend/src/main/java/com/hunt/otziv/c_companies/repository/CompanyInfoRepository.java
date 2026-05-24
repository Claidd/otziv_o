package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.CompanyInfo;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyInfoRepository extends CrudRepository<CompanyInfo, Long> {
    Optional<CompanyInfo> findByCompanyId(Long companyId);
}
