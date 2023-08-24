package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CompanyServiceImpl implements CompanyService{
    private final CompanyRepository companyRepository;
    private final LeadService leadService;
    private final UserService userService;
    private final ManagerService managerService;

    public CompanyServiceImpl(CompanyRepository companyRepository, LeadService leadService, UserService userService, ManagerService managerService) {
        this.companyRepository = companyRepository;
        this.leadService = leadService;
        this.userService = userService;
        this.managerService = managerService;
    }

//    Метод подготовки ДТО при создании компании из Лида менеджером
    public CompanyDTO convertToDtoToManager(Long leadId, Principal principal) {
        LeadDTO leadDTO = leadService.findById(leadId);

        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone(leadDTO.getTelephoneLead());
        companyDTO.setCity(leadDTO.getCityLead());
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(leadDTO.getOperator().getUser().getFio());
        companyDTO.setManager(convertToManagerDto(leadDTO.getManager()));

        return companyDTO;
    }

    public CompanyDTO convertToDto(Company company) {
        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setId(company.getId());
        companyDTO.setTitle(company.getTitle());
        companyDTO.setTelephone(company.getTelephone());
        companyDTO.setCity(company.getCity());
        companyDTO.setEmail(company.getEmail());
        companyDTO.setOperator(company.getOperator());
        companyDTO.setCounterNoPay(company.getCounterNoPay());
        companyDTO.setCounterPay(company.getCounterPay());
        companyDTO.setSumTotal(company.getSumTotal());
        companyDTO.setCommentsCompany(company.getCommentsCompany());
        companyDTO.setCreateDate(company.getCreateDate());
        companyDTO.setUpdateStatus(company.getUpdateStatus());
        companyDTO.setDateNewTry(company.getDateNewTry());
        companyDTO.setActive(company.isActive());

        // Convert related entities to DTOs
        companyDTO.setUser(convertToUserDto(company.getUser()));
        companyDTO.setManager(convertToManagerDto(company.getManager()));
        companyDTO.setWorkers(convertToWorkerDtoSet(company.getWorkers()));
        companyDTO.setStatus(convertToCompanyStatusDto(company.getStatus()));
        companyDTO.setCategoryCompany(convertToCategoryDto(company.getCategoryCompany()));
        companyDTO.setSubCategory(convertToSubCategoryDto(company.getSubCategory()));
        companyDTO.setFilial(convertToFilialDtoSet(company.getFilial()));

        return companyDTO;
    }


    private UserDTO convertToUserDtoToManager(Principal principal) {
        UserDTO userDTO = new UserDTO();
        User user = userService.findByUserName(principal.getName()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", principal.getName())
        ));
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setEmail(user.getEmail());
        userDTO.setFio(user.getFio());
        // Other fields if needed
        return userDTO;
    }
    private UserDTO convertToUserDto(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setEmail(user.getEmail());
        // Other fields if needed
        return userDTO;
    }

    private ManagerDTO convertToManagerDto(Manager manager) {
        ManagerDTO managerDTO = new ManagerDTO();
        managerDTO.setManagerId(manager.getId());
        managerDTO.setUser(manager.getUser());
        return managerDTO;
    }

    private Set<WorkerDTO> convertToWorkerDtoSet(Set<Worker> workers) {
        return workers.stream()
                .map(this::convertToWorkerDto)
                .collect(Collectors.toSet());
    }

    private WorkerDTO convertToWorkerDto(Worker worker) {
        WorkerDTO workerDTO = new WorkerDTO();
        workerDTO.setWorkerId(worker.getId());
        workerDTO.setUser(workerDTO.getUser());
        // Other fields if needed
        return workerDTO;
    }

    private CompanyStatusDTO convertToCompanyStatusDto(CompanyStatus status) {
        CompanyStatusDTO statusDTO = new CompanyStatusDTO();
        statusDTO.setId(status.getId());
        statusDTO.setTitle(status.getTitle());
        // Other fields if needed
        return statusDTO;
    }

    private CategoryDTO convertToCategoryDto(Category category) {
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        // Other fields if needed
        return categoryDTO;
    }

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        // Other fields if needed
        return subCategoryDTO;
    }

    private Set<FilialDTO> convertToFilialDtoSet(Set<Filial> filial) {
        return filial.stream()
                .map(this::convertToFilialDto)
                .collect(Collectors.toSet());
    }

    private FilialDTO convertToFilialDto(Filial filial) {
        FilialDTO filialDTO = new FilialDTO();
        filialDTO.setId(filial.getId());
        filialDTO.setTitle(filial.getTitle());
        filialDTO.setUrl(filialDTO.getUrl());
        // Other fields if needed
        return filialDTO;
    }
}
