package com.hunt.otziv.c_companies.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyContactDTO;
import com.hunt.otziv.c_companies.dto.CompanyInfoDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.dto.api.CompanyDeepReportLaunchResponse;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateOptionResponse;
import com.hunt.otziv.c_companies.dto.api.CompanyCreatePayloadResponse;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateRequest;
import com.hunt.otziv.c_companies.dto.api.CompanyCreateResultResponse;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyContactType;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchJobService;
import com.hunt.otziv.reputationai.config.DeepResearchProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/companies")
public class ApiCompanyCreateController {

    private static final String SOURCE_MANAGER = "manager";
    private static final String SOURCE_OPERATOR = "operator";
    private static final String SOURCE_MANUAL = "manual";
    private static final String FALLBACK_CITY = "Не задан";
    private static final int COMPANY_COMMENTS_MAX_LENGTH = 2000;
    private static final String COMPANY_DATA_SOURCE_MANUAL = "MANUAL";

    private final CompanyService companyService;
    private final LeadService leadService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final CityService cityService;
    private final FilialService filialService;
    private final ManagerService managerService;
    private final UserService userService;
    private final WorkerService workerService;
    private final PerformanceMetrics performanceMetrics;
    private final DeepCompanyResearchJobService deepCompanyResearchJobService;
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

        Company savedCompany = companyService.saveAndReturn(company)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Компания не создана"));

        if (request.leadId() != null) {
            leadService.changeStatusLeadOnInWork(request.leadId());
            if (SOURCE_OPERATOR.equals(source)) {
                leadService.changeCountToOperator(request.leadId());
            }
        }

        Long companyId = savedCompany.getId();
        CompanyDeepReportLaunchResponse deepReportLaunch = launchDeepReport(companyId, savedCompany.getTitle());

        return new CompanyCreateResultResponse(companyId, savedCompany.getTitle(), request.leadId(), source, deepReportLaunch);
    }

    private CompanyDeepReportLaunchResponse launchDeepReport(Long companyId, String companyTitle) {
        if (companyId == null || companyId <= 0) {
            String message = "Компания создана, но не удалось определить её ID для запуска глубокого отчёта.";
            log.warn("COMPANY_DEEP_REPORT_AUTO_START_SKIPPED reason=\"{}\" title=\"{}\"", message, safe(companyTitle));
            return CompanyDeepReportLaunchResponse.failed(message);
        }

        try {
            DeepCompanyResearchJobStatus job = deepCompanyResearchJobService.start(companyId, maximumReportRequest());
            String status = job.status();
            String message = isActiveDeepReport(job)
                    ? "Глубокий отчёт поставлен в очередь и соберётся в фоне."
                    : "Глубокий отчёт уже был запущен ранее.";
            log.info(
                    "COMPANY_DEEP_REPORT_AUTO_START_OK companyId={} jobId={} status={} title=\"{}\"",
                    companyId,
                    job.jobId(),
                    status,
                    safe(companyTitle)
            );
            return CompanyDeepReportLaunchResponse.started(job.jobId(), status, message);
        } catch (Exception exception) {
            String message = autoLaunchErrorMessage(exception);
            log.warn(
                    "COMPANY_DEEP_REPORT_AUTO_START_FAILED companyId={} title=\"{}\" reason=\"{}\"",
                    companyId,
                    safe(companyTitle),
                    message,
                    exception
            );
            return CompanyDeepReportLaunchResponse.failed(message);
        }
    }

    private boolean isActiveDeepReport(DeepCompanyResearchJobStatus job) {
        return job != null && ("QUEUED".equals(job.status()) || "RUNNING".equals(job.status()));
    }

    private ReputationResearchRequest maximumReportRequest() {
        return new ReputationResearchRequest(
                null,
                null,
                List.of(),
                List.of(),
                true,
                DeepResearchProfile.MAXIMUM.key(),
                DeepCompanyResearchJobService.OPERATION_FULL_REPORT,
                null,
                null,
                null,
                true
        );
    }

    private String autoLaunchErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
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
        Set<WorkerDTO> workers = workerService.getAllWorkersByManagerId(manager.getId());

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
            List<Manager> managers = new ArrayList<>(userService.findManagersByUserName(principal.getName()));
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
        boolean manualSource = SOURCE_MANUAL.equals(normalizeSource(request.source()));
        String telephone = manualSource
                ? normalize(request.telephone())
                : firstNonBlank(request.telephone(), base.getTelephone());
        String title = manualSource
                ? normalize(request.title())
                : firstNonBlank(request.title(), base.getTitle(), fallbackTitle(telephone));
        String city = manualSource
                ? normalize(request.city())
                : firstNonBlank(request.city(), base.getCity(), FALLBACK_CITY);
        String urlSite = firstNonBlank(request.urlSite(), base.getUrlSite());
        String urlChat = manualSource
                ? normalize(request.urlChat())
                : firstNonBlank(request.urlChat(), base.getUrlChat());
        String comments = manualSource
                ? normalize(request.commentsCompany())
                : firstNonBlank(request.commentsCompany(), base.getCommentsCompany());

        base.setTitle(title);
        base.setUrlChat(blankToNull(urlChat));
        base.setUrlSite(blankToNull(urlSite));
        base.setTelephone(telephone);
        base.setCity(city);
        base.setEmail(blankToNull(firstNonBlank(request.email(), base.getEmail())));
        base.setCommentsCompany(truncate(comments, COMPANY_COMMENTS_MAX_LENGTH));
        base.setCategoryCompany(validId(request.categoryId()) ? CategoryDTO.builder().id(request.categoryId()).build() : null);
        base.setSubCategory(validId(request.subCategoryId()) ? SubCategoryDTO.builder().id(request.subCategoryId()).build() : null);
        base.setWorker(validId(request.workerId()) ? WorkerDTO.builder().workerId(request.workerId()).build() : null);
        base.setFilial(buildFilialDto(base.getFilial(), request, manualSource));
        base.setContacts(buildContactDtos(base, request, manualSource));
        base.setInfo(buildCompanyInfoDto(base.getInfo(), request, manualSource));
        return base;
    }

    private void validateCreateRequest(CompanyDTO company, CompanyCreateRequest request) {
        boolean manualSource = SOURCE_MANUAL.equals(normalizeSource(request.source()));

        if (isBlank(company.getTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название компании не может быть пустым");
        }

        if (isBlank(company.getTelephone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Телефон компании не может быть пустым");
        }

        if (isBlank(company.getCity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город компании не может быть пустым");
        }

        if (manualSource && !validId(request.categoryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Категория не выбрана");
        }

        if (manualSource && !validId(request.subCategoryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подкатегория не выбрана");
        }

        if (manualSource && !validId(request.workerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не выбран");
        }

        if (manualSource && !validId(request.filialCityId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город филиала не выбран");
        }

        if (manualSource && isBlank(request.filialTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Адрес филиала не может быть пустым");
        }

        if (manualSource && isBlank(request.filialUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка 2ГИС не может быть пустой");
        }

        if (!isBlank(company.getEmail()) && !company.getEmail().contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный email");
        }

        if (!isBlank(request.filialUrl()) && filialService.findFilialByUrl(normalize(request.filialUrl())) != null) {
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
        List<CompanyCreateOptionResponse> cities = cityOptions();
        CompanyCreateOptionResponse filialCity = company.getFilial() != null && company.getFilial().getCity() != null
                ? new CompanyCreateOptionResponse(company.getFilial().getCity().getId(), safe(company.getFilial().getCity().getTitle()))
                : null;
        if (filialCity == null && !SOURCE_MANUAL.equals(source)) {
            filialCity = matchingCity(company.getCity(), cities);
        }
        CompanyInfoDTO info = company.getInfo();

        return new CompanyCreatePayloadResponse(
                source,
                leadId,
                safe(company.getTitle()),
                safe(company.getUrlChat()),
                safe(company.getUrlSite()),
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
                contactValues(company, CompanyContactType.PHONE),
                contactValues(company, CompanyContactType.MOBILE),
                contactValues(company, CompanyContactType.WHATSAPP),
                contactValues(company, CompanyContactType.EMAIL),
                contactValues(company, CompanyContactType.WEBSITE),
                contactValues(company, CompanyContactType.VK),
                contactValues(company, CompanyContactType.TELEGRAM),
                info != null ? safe(info.getRegion()) : "",
                info != null ? safe(info.getAddress()) : "",
                info != null ? safe(info.getIndustries()) : "",
                info != null ? safe(info.getCompanyType()) : "",
                managerOptions(principal, authentication),
                workerOptions(company),
                categoryOptions(),
                subCategoryOptions(categoryId),
                cities,
                SOURCE_MANUAL.equals(source) && hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER")
        );
    }

    private List<CompanyCreateOptionResponse> managerOptions(Principal principal, Authentication authentication) {
        List<Manager> managers;
        if (hasAnyRole(authentication, "ROLE_ADMIN")) {
            managers = managerService.getAllManagers();
        } else if (hasAnyRole(authentication, "ROLE_OWNER")) {
            managers = new ArrayList<>(userService.findManagersByUserName(principal.getName()));
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String fallbackTitle(String telephone) {
        String normalizedTelephone = normalize(telephone);
        return normalizedTelephone.isBlank() ? "Компания без названия" : "Компания " + normalizedTelephone;
    }

    private FilialDTO buildFilialDto(FilialDTO current, CompanyCreateRequest request, boolean manualSource) {
        String title = manualSource
                ? normalize(request.filialTitle())
                : firstNonBlank(request.filialTitle(), current != null ? current.getTitle() : null);
        String url = manualSource
                ? normalize(request.filialUrl())
                : firstNonBlank(request.filialUrl(), current != null ? current.getUrl() : null);
        Long cityId = request.filialCityId();

        if (!manualSource && !validId(cityId) && current != null && current.getCity() != null) {
            cityId = current.getCity().getId();
        }

        if (isBlank(title) && isBlank(url)) {
            return null;
        }

        return FilialDTO.builder()
                .title(title)
                .url(url)
                .city(validId(cityId) ? City.builder().id(cityId).build() : null)
                .build();
    }

    private Set<CompanyContactDTO> buildContactDtos(CompanyDTO base, CompanyCreateRequest request, boolean manualSource) {
        Map<String, CompanyContactDTO> contacts = new LinkedHashMap<>();
        if (!manualSource && base.getContacts() != null) {
            for (CompanyContactDTO contact : base.getContacts()) {
                addExistingContact(contacts, contact);
            }
        }

        addContact(contacts, CompanyContactType.PHONE, base.getTelephone(), true);
        addMultiContacts(contacts, CompanyContactType.PHONE, request.phones(), false);
        addMultiContacts(contacts, CompanyContactType.MOBILE, request.mobilePhones(), false);
        addMultiContacts(contacts, CompanyContactType.WHATSAPP, request.whatsappPhones(), true);
        addContact(contacts, CompanyContactType.EMAIL, base.getEmail(), true);
        addMultiContacts(contacts, CompanyContactType.EMAIL, request.emails(), false);
        addContact(contacts, CompanyContactType.WEBSITE, base.getUrlSite(), true);
        addMultiContacts(contacts, CompanyContactType.WEBSITE, request.websites(), false);
        addMultiContacts(contacts, CompanyContactType.VK, request.vkUrl(), true);
        addMultiContacts(contacts, CompanyContactType.TELEGRAM, request.telegramUrl(), true);
        return new LinkedHashSet<>(contacts.values());
    }

    private void addExistingContact(Map<String, CompanyContactDTO> contacts, CompanyContactDTO contact) {
        if (contact == null || isBlank(contact.getType()) || isBlank(contact.getValue())) {
            return;
        }

        CompanyContactType type;
        try {
            type = CompanyContactType.valueOf(contact.getType());
        } catch (IllegalArgumentException exception) {
            return;
        }

        String normalized = firstNonBlank(contact.getNormalizedValue(), normalizeContactValue(type, contact.getValue()));
        String key = contact.getType() + ":" + normalized;
        contacts.putIfAbsent(key, contact);
    }

    private void addMultiContacts(
            Map<String, CompanyContactDTO> contacts,
            CompanyContactType type,
            String values,
            boolean firstPrimary
    ) {
        boolean primary = firstPrimary;
        for (String value : multiValues(values)) {
            addContact(contacts, type, value, primary);
            primary = false;
        }
    }

    private void addContact(
            Map<String, CompanyContactDTO> contacts,
            CompanyContactType type,
            String value,
            boolean primary
    ) {
        String cleanValue = normalize(value);
        if (cleanValue.isBlank()) {
            return;
        }

        String normalized = normalizeContactValue(type, cleanValue);
        String key = type.name() + ":" + normalized;
        contacts.putIfAbsent(key, CompanyContactDTO.builder()
                .type(type.name())
                .value(cleanValue)
                .normalizedValue(normalized)
                .primaryContact(primary)
                .source(COMPANY_DATA_SOURCE_MANUAL)
                .build());
    }

    private List<String> multiValues(String value) {
        String cleanValue = normalize(value);
        if (cleanValue.isBlank()) {
            return List.of();
        }

        return List.of(cleanValue.split("[,;\\r\\n]+")).stream()
                .map(this::normalize)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private String normalizeContactValue(CompanyContactType type, String value) {
        String cleanValue = normalize(value);
        if (type == CompanyContactType.PHONE
                || type == CompanyContactType.MOBILE
                || type == CompanyContactType.WHATSAPP) {
            return LeadPhoneNormalizer.normalize(cleanValue);
        }
        return cleanValue.toLowerCase(Locale.ROOT);
    }

    private CompanyInfoDTO buildCompanyInfoDto(CompanyInfoDTO current, CompanyCreateRequest request, boolean manualSource) {
        String region = manualSource ? normalize(request.region()) : firstNonBlank(request.region(), current != null ? current.getRegion() : null);
        String address = manualSource ? normalize(request.address()) : firstNonBlank(request.address(), current != null ? current.getAddress() : null);
        String industries = manualSource ? normalize(request.industries()) : firstNonBlank(request.industries(), current != null ? current.getIndustries() : null);
        String companyType = manualSource ? normalize(request.companyType()) : firstNonBlank(request.companyType(), current != null ? current.getCompanyType() : null);

        if (region.isBlank() && address.isBlank() && industries.isBlank() && companyType.isBlank()) {
            return null;
        }

        return CompanyInfoDTO.builder()
                .id(current != null ? current.getId() : null)
                .region(region)
                .address(address)
                .industries(industries)
                .companyType(companyType)
                .source(current != null ? firstNonBlank(current.getSource(), COMPANY_DATA_SOURCE_MANUAL) : COMPANY_DATA_SOURCE_MANUAL)
                .sourceLeadId(current != null ? current.getSourceLeadId() : null)
                .build();
    }

    private String contactValues(CompanyDTO company, CompanyContactType type) {
        if (company.getContacts() == null || company.getContacts().isEmpty()) {
            return "";
        }

        return company.getContacts().stream()
                .filter(contact -> type.name().equals(contact.getType()))
                .map(CompanyContactDTO::getValue)
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private CompanyCreateOptionResponse matchingCity(String cityTitle, List<CompanyCreateOptionResponse> cities) {
        String normalizedCity = matchKey(cityTitle);
        if (normalizedCity.isBlank()) {
            return null;
        }

        return cities.stream()
                .filter(city -> matchKey(city.label()).equals(normalizedCity))
                .findFirst()
                .orElse(null);
    }

    private String matchKey(String value) {
        return normalize(value).toLowerCase();
    }

    private String truncate(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
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

    private boolean validId(Long id) {
        return id != null && id > 0;
    }

    private String storagePhone(String phone) {
        return LeadPhoneNormalizer.normalize(phone);
    }
}
