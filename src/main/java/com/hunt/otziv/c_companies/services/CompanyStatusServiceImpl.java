package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyStatusRepository;
import com.hunt.otziv.u_users.model.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CompanyStatusServiceImpl implements CompanyStatusService {

    private final CompanyStatusRepository companyStatusRepository;

    public CompanyStatusServiceImpl(CompanyStatusRepository companyStatusRepository) {
        this.companyStatusRepository = companyStatusRepository;
    }

    @Override
    public CompanyStatus getCompanyStatusById(Long id) {
        return companyStatusRepository.findById(id).orElse(null);
    }
}
