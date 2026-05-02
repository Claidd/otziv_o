package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyStatusServiceImpl implements CompanyStatusService {

    private final CompanyStatusRepository companyStatusRepository;

    @Override
    public CompanyStatus getCompanyStatusById(Long id) { // Взять все статусы компаний по Id
        return companyStatusRepository.findById(id).orElse(null);
    } // Взять все статусы компаний по Id

    public CompanyStatus getStatusByTitle(String title){ // Взять все статусы компаний
       return companyStatusRepository.findByTitle(title).orElse(null);
    } // Взять все статусы компаний

}
