package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.CompanyStatisticsOperations;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompanyStatisticsService implements CompanyStatisticsOperations {
    private final CompanyRepository companyRepository;

    @Override
    public List<Object[]> countNewCompaniesByManager(LocalDate firstDay, LocalDate lastDay) {
        return companyRepository.getAllNewCompanies(firstDay, lastDay);
    }
}
