package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;

import java.security.Principal;
import java.util.Set;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal); //    подготовка нового DTO на основе лида
    boolean save(CompanyDTO companyDTO); //    сохранить компанию
    Set<Company> getAllCompanies(); //    взять все компании
    Set<CompanyDTO> getAllCompaniesDTO(); //    взять все компании с переводом их в DTO



//    Set<Filial> convertFilialDTOToFilial(Long id, FilialDTO filialDTO);
}
