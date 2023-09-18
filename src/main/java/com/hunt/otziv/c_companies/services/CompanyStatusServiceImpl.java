package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyStatusRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.webjars.NotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyStatusServiceImpl implements CompanyStatusService {

    private final CompanyStatusRepository companyStatusRepository;

    @Override
    public CompanyStatus getCompanyStatusById(Long id) {
        return companyStatusRepository.findById(id).orElse(null);
    }

    public CompanyStatus getStatusByTitle(String title){
       return companyStatusRepository.findByTitle(title).orElse(null);
    }


}
