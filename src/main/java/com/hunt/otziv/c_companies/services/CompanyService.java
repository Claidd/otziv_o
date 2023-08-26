package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;

import java.security.Principal;
import java.util.Set;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal);

    boolean save(CompanyDTO companyDTO);

    Set<Company> getAllCompanies();

//    Set<Filial> convertFilialDTOToFilial(Long id, FilialDTO filialDTO);
}
