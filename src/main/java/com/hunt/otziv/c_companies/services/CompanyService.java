package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.dto.WorkerDTO;

import java.security.Principal;
import java.util.Set;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal); //    подготовка нового DTO на основе лида
    boolean save(CompanyDTO companyDTO); //    сохранить компанию
    Set<Company> getAllCompanies(); //    взять все компании
    Set<CompanyDTO> getAllCompaniesDTO(); //    взять все компании с переводом их в DTO
    CompanyDTO getCompaniesDTOById(Long id); // взять одну компанию по id с переводом их в DTO
    Company getCompaniesById(Long id); // взять одну компанию по id

    void updateCompany(CompanyDTO companyDTO, WorkerDTO workerDTO, Long companyId);

    boolean deleteWorkers(Long companyId, Long workerId);
    boolean deleteFilial(Long companyId, Long filialId);

//    Set<Filial> convertFilialDTOToFilial(Long id, FilialDTO filialDTO);
}
