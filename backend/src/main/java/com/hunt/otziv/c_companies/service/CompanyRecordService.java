package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.CompanyRecordOperations;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyInfoRepository;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistence boundary with no dependency on company screens, reviews or order/payment workflows. */
@Service
@RequiredArgsConstructor
public class CompanyRecordService implements CompanyRecordOperations {
    private final CompanyRepository companyRepository;
    private final CompanyInfoRepository companyInfoRepository;

    @Override
    public Company getCompaniesById(Long id) {
        return companyRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", id)));
    }

    @Override
    @Transactional
    public void save(Company company) {
        Company saved = companyRepository.save(company);
        persistTransientCompanyInfo(saved);
    }

    /** Kept inside the company module; the surrounding save/create transaction owns both writes. */
    void persistTransientCompanyInfo(Company company) {
        if (company == null || company.getInfo() == null) return;
        company.getInfo().setCompany(company);
        company.setInfo(companyInfoRepository.save(company.getInfo()));
    }
}
