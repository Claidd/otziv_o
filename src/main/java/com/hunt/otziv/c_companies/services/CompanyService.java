package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.dto.WorkerDTO;

import java.security.Principal;
import java.util.List;
import java.util.Set;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal); //    подготовка нового DTO на основе лида
    boolean save(CompanyDTO companyDTO); //    сохранить компанию
    List<CompanyDTO> getAllCompaniesDTOList();
    List<Company> getAllCompaniesList();
    List<CompanyDTO> getAllCompaniesDTOList(String keywords);
    CompanyDTO getCompaniesDTOById(Long id); // взять одну компанию по id с переводом их в DTO
    Company getCompaniesById(Long id); // взять одну компанию по id
    void updateCompany(CompanyDTO companyDTO, WorkerDTO workerDTO, Long companyId);
    boolean deleteWorkers(Long companyId, Long workerId);
    boolean deleteFilial(Long companyId, Long filialId);
    void save(Company company);
    boolean changeStatusForCompany(Long companyId, String title);
    void changeDataTry(Long companyId);
    CompanyDTO getCompaniesAllStatusByIdAndKeyword(Long id, String keyword);
    List<CompanyDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword);


}
