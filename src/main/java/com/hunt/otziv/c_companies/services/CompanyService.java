package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal); //    подготовка нового DTO на основе лида
    boolean save(CompanyDTO companyDTO); //    сохранить компанию
    Page<CompanyListDTO> getAllCompaniesDTOList(String keyword, int pageNumber, int pageSize);
    List<Company> getAllCompaniesList();
    List<CompanyDTO> getAllCompaniesDTOList(String keywords, String status);
    CompanyDTO getCompaniesDTOById(Long id); // взять одну компанию по id с переводом их в DTO
    Company getCompaniesById(Long id); // взять одну компанию по id

    List<Object[]> getAllNewCompanies2(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);
    Map<String, Long> getAllNewCompanies(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    void updateCompany(CompanyDTO companyDTO, WorkerDTO workerDTO, Long companyId);
    boolean deleteWorkers(Long companyId, Long workerId);
    boolean deleteFilial(Long companyId, Long filialId);
    void save(Company company);
    boolean changeStatusForCompany(Long companyId, String title);
    void changeDataTry(Long companyId);
    CompanyDTO getCompaniesAllStatusByIdAndKeyword(Long id, String keyword);
    Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<CompanyListDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<CompanyListDTO> getAllCompaniesDTOListToList(String keywords, String status, int pageNumber, int pageSize);
    Page<CompanyListDTO> getAllCompaniesDTOListToListToSend(String keywords, String status, int pageNumber, int pageSize);
    Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManagerToSend(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<CompanyListDTO>getAllCompaniesDtoToOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<CompanyListDTO> getAllCompaniesDTOListOwner(Principal principal, String keyword, int pageNumber, int pageSize);

    CompanyDTO convertToDtoToManagerNotLead(Principal principal);
    int getAllCompanyDTOByStatus(String status);

    int getAllCompanyDTOByStatusToManager(Manager manager, String status);


    int getAllCompanyDTOByStatusToOwner(Set<Manager> managerList, String status);


    CompanyDTO convertToDtoToOperator(Long leadId, Principal principal);

    Optional<Company> getCompanyByTelephonAndTitle(String telephoneNumber, String title);

    Optional<Company> findByGroupId(String groupId);
}
