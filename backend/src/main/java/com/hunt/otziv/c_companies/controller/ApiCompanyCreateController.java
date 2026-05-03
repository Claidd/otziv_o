package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateOptionResponse;
import com.hunt.otziv.c_companies.dto.api.CompanyCreatePayloadResponse;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateRequest;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateResultResponse;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/companies")
public class ApiCompanyCreateController {

    private static final String SOURCE_MANAGER = "manager";
    private static final String SOURCE_OPERATOR = "operator";
    private static final String SOURCE_MANUAL = "manual";

    private final CompanyService companyService;
    private final LeadService leadService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final CityService cityService;
    private final FilialService filialService;
    private final ManagerService managerService;
    private final UserService userService;
    private final PerformanceMetrics performanceMetrics;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/create-payload")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'OPERATOR')")
    public CompanyCreatePayloadResponse getCreatePayload(
            @RequestParam(defaultValue = SOURCE_MANAGER) String source,
            @RequestParam(required = false) Long leadId,
            @RequestParam(required = false) Long managerId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("companies.create-payload", () -> {
            String normalizedSource = normalizeSource(source);
            CompanyDTO company = buildBaseCompany(normalizedSource, leadId, managerId, principal, authentication);
            return toPayload(normalizedSource, leadId, company, principal, authentication);
        });
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'OPERATOR')")
    public List<CompanyCreateOptionResponse> getSubcategories(@PathVariable Long categoryId) {
        return subCategoryOptions(categoryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'OPERATOR')")
    public CompanyCreateResultResponse createCompany(
            @RequestBody CompanyCreateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные компании не переданы");
        }

        String source = normalizeSource(request.source());
        CompanyDTO baseCompany = buildBaseCompany(source, request.leadId(), request.managerId(), principal, authentication);
        CompanyDTO company = toCompanyDto(baseCompany, request);
        validateCreateRequest(company, request);

        boolean saved = companyService.save(company);
        if (!saved) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Компания не создана");
        }

        if (request.leadId() != null) {
            leadService.changeStatusLeadOnInWork(request.leadId());
            if (SOURCE_OPERATOR.equals(source)) {
                leadService.changeCountToOperator(request.leadId());
            }
        }

        Long companyId = companyService
                .getCompanyByTelephonAndTitle(storagePhone(company.getTelephone()), company.getTitle())
                .map(Company::getId)
                .orElse(null);

        return new CompanyCreateResultResponse(companyId, company.getTitle(), request.leadId(), source);
    }

    private CompanyDTO buildBaseCompany(
            String source,
            Long leadId,
            Long managerId,
            Principal principal,
            Authentication authentication
    ) {
        if (SOURCE_OPERATOR.equals(source)) {
            requireLeadId(leadId);
            return companyService.convertToDtoToOperator(leadId, principal);
        }

        if (SOURCE_MANUAL.equals(source)) {
            return manualCompanyDto(resolveManualManager(principal, authentication, managerId));
        }

        requireLeadId(leadId);
        return companyService.convertToDtoToManager(leadId, principal);
    }

    private void requireLeadId(Long leadId) {
        if (leadId == null || leadId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Лид не указан");
        }
    }

    private CompanyDTO manualCompanyDto(Manager manager) {
        User user = manager.getUser();
        Set<WorkerDTO> workers = user.getWorkers() == null
                ? Set.of()
                : user.getWorkers().stream().map(this::workerDto).collect(Collectors.toSet());

        return CompanyDTO.builder()
                .telephone("")
                .city("Не задан")
                .user(userDto(user))
                .operator(null)
                .manager(managerDto(manager))
                .status(CompanyStatusDTO.builder().id(1L).title("Новая").build())
                .filial(new FilialDTO())
                .workers(workers)
                .worker(selectWorker(workers))
                .build();
    }

    private Manager resolveManualManager(Principal principal, Authentication authentication, Long managerId) {
        if (hasAnyRole(authentication, "ROLE_ADMIN")) {
            if (managerId != null) {
                return managerService.getManagerById(managerId);
            }
            return firstManager(managerService.getAllManagers());
        }

        if (hasAnyRole(authentication, "ROLE_OWNER")) {
            List<Manager> managers = new ArrayList<>(currentUser(principal).getManagers());
            if (managerId != null) {
                return managers.stream()
                        .filter(manager -> Objects.equals(manager.getId(), managerId))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен"));
            }
            return firstManager(managers);
        }

        Manager manager = managerService.getManagerByUserId(currentUser(principal).getId());
        if (manager == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для пользователя не найден менеджер");
        }
        return manager;
    }

    private Manager firstManager(List<Manager> managers) {
        return managers.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нет доступных менеджеров"));
    }

    private User currentUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    private CompanyDTO toCompanyDto(CompanyDTO base, CompanyCreateRequest request) {
        base.setTitle(normalize(request.title()));
        base.setUrlChat(normalize(request.urlChat()));
        base.setTelephone(normalize(request.telephone()));
        base.setCity(normalize(request.city()));
        base.setEmail(blankToNull(request.email()));
        base.setCommentsCompany(normalize(request.commentsCompany()));
        base.setCategoryCompany(CategoryDTO.builder().id(request.categoryId()).build());
        base.setSubCategory(SubCategoryDTO.builder().id(request.subCategoryId()).build());
        base.setWorker(WorkerDTO.builder().workerId(request.workerId()).build());
        base.setFilial(FilialDTO.builder()
                .title(normalize(request.filialTitle()))
                .url(normalize(request.filialUrl()))
                .city(City.builder().id(request.filialCityId()).build())
                .build());
        return base;
    }

    private void validateCreateRequest(CompanyDTO company, CompanyCreateRequest request) {
        if (isBlank(company.getTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название компании не может быть пустым");
        }

        if (isBlank(company.getTelephone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Телефон компании не может быть пустым");
        }

        if (isBlank(company.getUrlChat())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чат не может быть пустой");
        }

        if (isBlank(company.getCity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город компании не может быть пустым");
        }

        if (request.categoryId() == null || request.categoryId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Категория не выбрана");
        }

        if (request.subCategoryId() == null || request.subCategoryId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подкатегория не выбрана");
        }

        if (request.workerId() == null || request.workerId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не выбран");
        }

        if (request.filialCityId() == null || request.filialCityId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город филиала не выбран");
        }

        if (isBlank(request.filialTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Адрес филиала не может быть пустым");
        }

        if (isBlank(request.filialUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка 2ГИС не может быть пустой");
        }

        if (!isBlank(company.getEmail()) && !company.getEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный email");
        }

        if (filialService.findFilialByUrl(normalize(request.filialUrl())) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Такой url филиала уже есть в базе");
        }

        if (companyService.getCompanyByTelephonAndTitle(storagePhone(company.getTelephone()), company.getTitle()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Компания с таким названием или телефоном уже есть");
        }
    }

    private CompanyCreatePayloadResponse toPayload(
            String source,
            Long leadId,
            CompanyDTO company,
            Principal principal,
            Authentication authentication
    ) {
        Long categoryId = company.getCategoryCompany() != null ? company.getCategoryCompany().getId() : null;
        CompanyCreateOptionResponse filialCity = company.getFilial() != null && company.getFilial().getCity() != null
                ? new CompanyCreateOptionResponse(company.getFilial().getCity().getId(), safe(company.getFilial().getCity().getTitle()))
                : null;

        return new CompanyCreatePayloadResponse(
                source,
                leadId,
                safe(company.getTitle()),
                safe(company.getUrlChat()),
                safe(company.getTelephone()),
                safe(company.getCity()),
                safe(company.getEmail()),
                safe(company.getCommentsCompany()),
                safe(company.getOperator()),
                option(company.getManager()),
                option(company.getWorker()),
                option(company.getStatus()),
                option(company.getCategoryCompany()),
                option(company.getSubCategory()),
                filialCity,
                company.getFilial() != null ? safe(company.getFilial().getTitle()) : "",
                company.getFilial() != null ? safe(company.getFilial().getUrl()) : "",
                managerOptions(principal, authentication),
                workerOptions(company),
                categoryOptions(),
                subCategoryOptions(categoryId),
                cityOptions(),
                SOURCE_MANUAL.equals(source) && hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER")
        );
    }

    private List<CompanyCreateOptionResponse> managerOptions(Principal principal, Authentication authentication) {
        List<Manager> managers;
        if (hasAnyRole(authentication, "ROLE_ADMIN")) {
            managers = managerService.getAllManagers();
        } else if (hasAnyRole(authentication, "ROLE_OWNER")) {
            managers = new ArrayList<>(currentUser(principal).getManagers());
        } else {
            Manager manager = managerService.getManagerByUserId(currentUser(principal).getId());
            managers = manager == null ? List.of() : List.of(manager);
        }

        return managers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(manager -> safe(manager.getUser() != null ? manager.getUser().getFio() : "")))
                .map(manager -> new CompanyCreateOptionResponse(
                        manager.getId(),
                        safe(manager.getUser() != null ? manager.getUser().getFio() : "Менеджер #" + manager.getId())
                ))
                .toList();
    }

    private List<CompanyCreateOptionResponse> workerOptions(CompanyDTO company) {
        if (company.getWorkers() != null && !company.getWorkers().isEmpty()) {
            return company.getWorkers().stream()
                    .sorted(Comparator.comparing(worker -> safe(worker.getUser() != null ? worker.getUser().getFio() : "")))
                    .map(this::option)
                    .filter(Objects::nonNull)
                    .toList();
        }

        if (company.getUser() != null && company.getUser().getWorkers() != null) {
            return company.getUser().getWorkers().stream()
                    .sorted(Comparator.comparing(worker -> safe(worker.getUser() != null ? worker.getUser().getFio() : "")))
                    .map(this::option)
                    .filter(Objects::nonNull)
                    .toList();
        }

        CompanyCreateOptionResponse worker = option(company.getWorker());
        return worker == null ? List.of() : List.of(worker);
    }

    private List<CompanyCreateOptionResponse> categoryOptions() {
        return categoryService.getAllCategories().stream()
                .sorted(Comparator.comparing(CategoryDTO::getCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<CompanyCreateOptionResponse> subCategoryOptions(Long categoryId) {
        List<SubCategoryDTO> subCategories = categoryId == null || categoryId <= 0
                ? subCategoryService.getAllSubCategories()
                : subCategoryService.getSubcategoriesByCategoryId(categoryId);

        return subCategories.stream()
                .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<CompanyCreateOptionResponse> cityOptions() {
        return cityService.getAllCities().stream()
                .sorted(Comparator.comparing(CityDTO::getCityTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(city -> new CompanyCreateOptionResponse(city.getId(), safe(city.getCityTitle())))
                .toList();
    }

    private CompanyCreateOptionResponse option(CategoryDTO category) {
        return category == null ? null : new CompanyCreateOptionResponse(category.getId(), safe(category.getCategoryTitle()));
    }

    private CompanyCreateOptionResponse option(SubCategoryDTO subCategory) {
        return subCategory == null ? null : new CompanyCreateOptionResponse(subCategory.getId(), safe(subCategory.getSubCategoryTitle()));
    }

    private CompanyCreateOptionResponse option(CompanyStatusDTO status) {
        return status == null ? null : new CompanyCreateOptionResponse(status.getId(), safe(status.getTitle()));
    }

    private CompanyCreateOptionResponse option(ManagerDTO manager) {
        if (manager == null) {
            return null;
        }

        String label = manager.getUser() != null ? manager.getUser().getFio() : "Менеджер #" + manager.getManagerId();
        return new CompanyCreateOptionResponse(manager.getManagerId(), safe(label));
    }

    private CompanyCreateOptionResponse option(WorkerDTO worker) {
        if (worker == null) {
            return null;
        }

        String label = worker.getUser() != null ? worker.getUser().getFio() : "Специалист #" + worker.getWorkerId();
        return new CompanyCreateOptionResponse(worker.getWorkerId(), safe(label));
    }

    private CompanyCreateOptionResponse option(Worker worker) {
        if (worker == null) {
            return null;
        }

        String label = worker.getUser() != null ? worker.getUser().getFio() : "Специалист #" + worker.getId();
        return new CompanyCreateOptionResponse(worker.getId(), safe(label));
    }

    private WorkerDTO selectWorker(Set<WorkerDTO> workers) {
        if (workers == null || workers.isEmpty()) {
            return null;
        }
        List<WorkerDTO> workerList = new ArrayList<>(workers);
        return workerList.get(secureRandom.nextInt(workerList.size()));
    }

    private WorkerDTO workerDto(Worker worker) {
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private ManagerDTO managerDto(Manager manager) {
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .clientId(manager.getClientId())
                .build();
    }

    private UserDTO userDto(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fio(user.getFio())
                .email(user.getEmail())
                .workers(user.getWorkers())
                .build();
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        if (authentication == null) {
            return false;
        }

        Set<String> allowed = Set.of(roles);
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(allowed::contains);
    }

    private String normalizeSource(String source) {
        if (SOURCE_OPERATOR.equalsIgnoreCase(source)) {
            return SOURCE_OPERATOR;
        }

        if (SOURCE_MANUAL.equalsIgnoreCase(source)) {
            return SOURCE_MANUAL;
        }

        return SOURCE_MANAGER;
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
        return value == null || value.isBlank();
    }

    private String storagePhone(String phone) {
        return LeadPhoneNormalizer.normalize(phone);
    }
}
