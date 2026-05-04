package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.manager.dto.api.CompanyEditResponse;
import com.hunt.otziv.manager.dto.api.CompanyNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.CompanyOrderCreateRequest;
import com.hunt.otziv.manager.dto.api.CompanyOrderCreateResponse;
import com.hunt.otziv.manager.dto.api.CompanyOrderCreateResultResponse;
import com.hunt.otziv.manager.dto.api.CompanyUpdateRequest;
import com.hunt.otziv.manager.dto.api.OptionResponse;
import com.hunt.otziv.manager.dto.api.StatusChangeRequest;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerCompanyController {

    private final CompanyService companyService;
    private final OrderService orderService;
    private final OrderCreationService orderCreationService;
    private final ManagerBoardEditAssembler managerBoardEditAssembler;
    private final ManagerPermissionService managerPermissionService;

    @PostMapping("/companies/{companyId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateCompanyStatus(
            @PathVariable Long companyId,
            @RequestBody StatusChangeRequest request
    ) {
        String status = requireStatus(request);
        boolean updated = companyService.changeStatusForCompany(companyId, status);

        if (!updated) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус компании не изменен");
        }
    }

    @GetMapping("/companies/{companyId}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse getCompanyEdit(
            @PathVariable Long companyId,
            Principal principal,
            Authentication authentication
    ) {
        return managerBoardEditAssembler.buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @GetMapping("/companies/{companyId}/order-create")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyOrderCreateResponse getCompanyOrderCreate(@PathVariable Long companyId) {
        return managerBoardEditAssembler.buildCompanyOrderCreateResponse(companyService.getCompaniesDTOById(companyId));
    }

    @PostMapping("/companies/{companyId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyOrderCreateResultResponse createCompanyOrder(
            @PathVariable Long companyId,
            @RequestBody CompanyOrderCreateRequest request
    ) {
        CompanyDTO company = companyService.getCompaniesDTOById(companyId);
        Product product = managerBoardEditAssembler.validateCompanyOrderCreateRequest(company, request);

        OrderDTO orderDTO = orderService.newOrderDTO(companyId);
        orderDTO.setAmount(request.amount());
        orderDTO.setWorker(WorkerDTO.builder().workerId(request.workerId()).build());
        orderDTO.setFilial(FilialDTO.builder().id(request.filialId()).build());

        try {
            if (!orderCreationService.createNewOrderWithReviews(companyId, request.productId(), orderDTO)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не создан");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не создан: " + exception.getMessage(), exception);
        }

        return new CompanyOrderCreateResultResponse(
                companyId,
                safe(company.getTitle()),
                request.productId(),
                safe(product.getTitle()),
                request.amount()
        );
    }

    @PutMapping("/companies/{companyId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse updateCompany(
            @PathVariable Long companyId,
            @RequestBody CompanyUpdateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        CompanyDTO current = companyService.getCompaniesDTOById(companyId);

        try {
            companyService.updateCompany(toCompanyUpdateDto(current, request, companyId, authentication), toWorkerDTO(request), companyId);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Телефон, email или филиал уже используется", exception);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Компания не сохранена: " + exception.getMessage(), exception);
        }

        return managerBoardEditAssembler.buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @PutMapping("/companies/{companyId}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateCompanyNote(
            @PathVariable Long companyId,
            @RequestBody CompanyNoteUpdateRequest request
    ) {
        if (request == null || request.companyComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка компании не указана");
        }

        Company company = companyService.getCompaniesById(companyId);
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена");
        }

        company.setCommentsCompany(request.companyComments());
        companyService.save(company);
    }

    @DeleteMapping("/companies/{companyId}/workers/{workerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse deleteCompanyWorker(
            @PathVariable Long companyId,
            @PathVariable Long workerId,
            Principal principal,
            Authentication authentication
    ) {
        if (!companyService.deleteWorkers(companyId, workerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не удален из компании");
        }

        return managerBoardEditAssembler.buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @DeleteMapping("/companies/{companyId}/filials/{filialId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse deleteCompanyFilial(
            @PathVariable Long companyId,
            @PathVariable Long filialId,
            Principal principal,
            Authentication authentication
    ) {
        if (!companyService.deleteFilial(companyId, filialId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не удален из компании");
        }

        return managerBoardEditAssembler.buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public List<OptionResponse> getSubcategories(@PathVariable Long categoryId) {
        return managerBoardEditAssembler.subCategoryOptions(categoryId);
    }

    private CompanyDTO toCompanyUpdateDto(
            CompanyDTO current,
            CompanyUpdateRequest request,
            Long companyId,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные компании не переданы");
        }

        if (isBlank(request.title()) || isBlank(request.telephone()) || isBlank(request.urlChat()) || isBlank(request.city())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название, телефон, город и ссылка на чат обязательны");
        }

        Long newFilialCityId = request.newFilialCityId();
        String newFilialTitle = normalize(request.newFilialTitle());
        String newFilialUrl = normalize(request.newFilialUrl());
        if (!newFilialTitle.isEmpty() && (newFilialCityId == null || newFilialUrl.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для нового филиала нужны город, адрес и ссылка 2ГИС");
        }

        Long managerId = managerPermissionService.hasRole(authentication, "ADMIN")
                ? firstId(request.managerId(), idOf(current.getManager()))
                : firstId(idOf(current.getManager()), null);

        return CompanyDTO.builder()
                .id(companyId)
                .title(normalize(request.title()))
                .urlChat(normalize(request.urlChat()))
                .telephone(normalize(request.telephone()))
                .city(normalize(request.city()))
                .email(blankToNull(request.email()))
                .commentsCompany(normalize(request.commentsCompany()))
                .active(Boolean.TRUE.equals(request.active()))
                .status(CompanyStatusDTO.builder().id(firstId(request.statusId(), idOf(current.getStatus()))).build())
                .categoryCompany(CategoryDTO.builder().id(firstId(request.categoryId(), idOf(current.getCategoryCompany()))).build())
                .subCategory(SubCategoryDTO.builder().id(firstId(request.subCategoryId(), idOf(current.getSubCategory()))).build())
                .manager(ManagerDTO.builder().managerId(managerId).build())
                .filial(FilialDTO.builder()
                        .title(newFilialTitle)
                        .url(newFilialUrl)
                        .city(newFilialCityId == null ? null : City.builder().id(newFilialCityId).build())
                        .build())
                .build();
    }

    private WorkerDTO toWorkerDTO(CompanyUpdateRequest request) {
        Long workerId = request == null || request.newWorkerId() == null ? 0L : request.newWorkerId();
        return WorkerDTO.builder().workerId(workerId).build();
    }

    private Long idOf(CategoryDTO category) {
        return category == null ? null : category.getId();
    }

    private Long idOf(SubCategoryDTO subCategory) {
        return subCategory == null ? null : subCategory.getId();
    }

    private Long idOf(CompanyStatusDTO status) {
        return status == null ? null : status.getId();
    }

    private Long idOf(ManagerDTO manager) {
        return manager == null ? null : manager.getManagerId();
    }

    private Long firstId(Long value, Long fallback) {
        return value != null ? value : fallback != null ? fallback : 0L;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String requireStatus(StatusChangeRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус не указан");
        }
        return request.status().trim();
    }
}
