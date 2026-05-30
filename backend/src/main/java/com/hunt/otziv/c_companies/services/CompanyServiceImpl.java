package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyContactDTO;
import com.hunt.otziv.c_companies.dto.CompanyInfoDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyContact;
import com.hunt.otziv.c_companies.model.CompanyContactType;
import com.hunt.otziv.c_companies.model.CompanyInfo;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.common.BoardLiveSlice;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.next_order.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.NextOrderRequestSummary;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService{
    private static final Set<NextOrderRequestStatus> OPEN_NEXT_ORDER_STATUSES = Set.of(
            NextOrderRequestStatus.PENDING,
            NextOrderRequestStatus.FAILED
    );
    private static final long OPERATOR_COUNT_ZERO_MANAGER_ID = 2L;
    private static final long OPERATOR_COUNT_ONE_MANAGER_ID = 3L;
    private static final int COMPANY_COMMENTS_MAX_LENGTH = 2000;
    private static final String COMPANY_DATA_SOURCE_LEAD_IMPORT = "LEAD_IMPORT";
    private static final String COMPANY_DATA_SOURCE_MANUAL = "MANUAL";

    private final CompanyRepository companyRepository;
    private final LeadService leadService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final CompanyStatusService companyStatusService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final FilialService filialService;
    private final ReviewService reviewService;
    private final OperatorService operatorService;
    private final TelegramService telegramService;
    private final TelegramGroupLinkService telegramGroupLinkService;
    private final MaxGroupLinkService maxGroupLinkService;
    private final NextOrderRequestRepository nextOrderRequestRepository;

    @Value("${otziv.board.live-slice.retention-days:90}")
    private int liveSliceRetentionDays;

    @Transactional
    public void save(Company company){
        companyRepository.save(company);
    } // Сохранение компании в БД

    //    Метод подготовки ДТО при создании компании из Лида менеджером

    //      =====================================CREATE USERS - START=======================================================
    // Создание нового пользователя "Клиент" - начало
    @Transactional
    public boolean save(CompanyDTO companyDTO){ // Сохранение новой компании из дто
        return saveAndReturn(companyDTO).isPresent();
    } // Создание нового пользователя "Клиент" - конец

    @Transactional
    public Optional<Company> saveAndReturn(CompanyDTO companyDTO){ // Сохранение новой компании из дто
        log.info("1. Заходим в создание новой компаниии");
        //        в начале сохранения устанавливаем поля из дто
        Company company = Company.builder()
                .title(companyDTO.getTitle())
                .telephone(changeNumberPhone(companyDTO.getTelephone()))
                .city(companyDTO.getCity())
                .urlChat(companyDTO.getUrlChat())
                .urlSite(companyDTO.getUrlSite())
                .email(companyDTO.getEmail())
                .user(convertUserDTOToUser(companyDTO.getUser()))
                .operator(companyDTO.getOperator())
                .manager(convertManagerDTOToManager(companyDTO.getManager()))
                .workers(convertWorkerDTOToWorker(companyDTO.getWorker()))
                .status(convertCompanyStatusDTOToCompanyStatus(companyDTO.getStatus()))
                .categoryCompany(convertCategoryDTOToCategory(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCategoryDTOToSubCategory(companyDTO.getSubCategory()))
                .active(true)
                .publicationProgressReportsEnabled(companyDTO.isPublicationProgressReportsEnabled())
                .commentsCompany(companyDTO.getCommentsCompany())
                .counterNoPay(0)
                .counterPay(0)
                .sumTotal(new BigDecimal(0))
                .build();
        company.setContacts(convertContactDTOSetToContacts(companyDTO.getContacts(), company));
        company.setInfo(convertInfoDTOToInfo(companyDTO.getInfo(), company));

        log.info("2. Собрали компания из ДТО");

        //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
        Set<Filial> existingFilials = company.getFilial(); // пытаемся получить текущий список филиалов из компании
        if (existingFilials == null) {
            existingFilials = new HashSet<>();// если он пустой, то создаем новый set
        }
        Set<Filial> newFilials = convertFilialDTOToFilial(companyDTO.getFilial()); // берем список из дто
        existingFilials.addAll(newFilials); // объединяем эти списки
        company.setFilial(existingFilials); // устанавливаем компании объединенный список
        log.info("2. Добавили список филиалов");
        //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
        log.info("3. Компания успешно создана");
        try {
            Company company1 = companyRepository.save(company); // сохраняем новую компанию в БД
            log.info("3. Компания успешно сохранена");
            for (Filial filial : company1.getFilial()) { // проходимся по всем филиалам и устанавливаем им компанию
                filial.setCompany(company1); // Установка компании в филиале
                filialService.save(filial); // Сохранение обновленного филиала
            }

            if (company1.getManager() != null && company1.getManager().getUser() != null) {
                Long telegramChatId = company1.getManager().getUser().getTelegramChatId();

                if (telegramChatId != null && company1.getTitle() != null ) {
                    String resultBuilder =

                            "Добавлена новая компания: " + company1.getTitle() + "\n" +
                                    "https://o-ogo.ru/companies/company?status=Новая";

                    telegramService.sendMessage(telegramChatId, resultBuilder);
                }
            }
            return Optional.of(company1);
        } catch (Exception e) {
            log.error("ОШИБКА при сохранении компании: {}", e.getMessage(), e);
            return Optional.empty();
        }
    } // Создание нового пользователя "Клиент" - конец
//      =====================================CREATE USERS - START=======================================================

    public List<Company> getAllCompaniesList(){ // Взять все компании
        return companyRepository.findAll();
    } // Взять все компании


    // ======================================== JUST ADMIN ===============================================================
    public Page<CompanyListDTO> getAllCompaniesDTOListToList(String keywords, String status, int pageNumber, int pageSize){ // Показ всех компаний + поиск + статус
        return getAllCompaniesDTOListToList(keywords, status, pageNumber, pageSize, "desc");
    }

    public Page<CompanyListDTO> getAllCompaniesDTOListToList(String keywords, String status, int pageNumber, int pageSize, String sortDirection){ // Показ всех компаний + поиск + статус
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyIds = companyRepository.findPageIdByStatusAndKeyword(keywords, status, keywords, status, pageable);
        }
        else {
            companyIds = companyRepository.findPageIdByStatus(status, pageable);
        }
        return getCompanyDTOPage(companyIds);
    } // Показ всех компаний + поиск + статус


    public Page<CompanyListDTO> getAllCompaniesDTOListToListToSend(String keywords, String status, int pageNumber, int pageSize){ // Показ всех компаний + поиск + статус + для рассылки
        Pageable pageable = companyPageable(pageNumber, pageSize, "desc");
        Page<Long> companyIds;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyIds = companyRepository.findPageIdByStatusAndKeyword(keywords, status, keywords, status, pageable);
        }
        else {
            companyIds = companyRepository.findPageIdByStatus(status, pageable);
        }
        return getCompanyDTOPage(companyIds, true);
    } // Показ всех компаний + поиск + статус + для рассылки



    public Page<CompanyListDTO> getAllCompaniesDTOList(String keywords, int pageNumber, int pageSize) {
        return getAllCompaniesDTOList(keywords, pageNumber, pageSize, "desc");
    }

    public Page<CompanyListDTO> getAllCompaniesDTOList(String keywords, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyIds = companyRepository.findPageToAdminWithFetchWithKeyWord(keywords, keywords, pageable);
        }
        else {
            companyIds = companyRepository.findPageIdToAdminLive(
                    BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                    liveCutoff(),
                    pageable
            );
        }
        return getCompanyDTOPage(companyIds);
    }


// ======================================== WITH MANAGER ===============================================================

    public Page<CompanyListDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, int pageNumber, int pageSize){ // Берем все заказы  Менеджера + поиск
        return getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize, "desc");
    }

    public Page<CompanyListDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection){ // Берем все заказы  Менеджера + поиск
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keyword.isEmpty()){
            companyIds = companyRepository.findPageByManagerAndKeyWord(manager, keyword, manager, keyword, pageable);
        }
        else {
            companyIds = companyRepository.findPageByManagerLive(
                    manager,
                    BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                    liveCutoff(),
                    pageable
            );
        }
        return getCompanyDTOPage(companyIds);
    } // Берем все заказы  Менеджера + поиск


    public Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы Менеджера + поиск + статус
        return getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, "desc");
    }

    public Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection){ // Берем все заказы Менеджера + поиск + статус
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keyword.isEmpty()){
            companyIds = companyRepository.findPageByManagerAndStatusAndKeyWords(manager,keyword, status, manager, keyword, status, pageable);
        }
        else {
            companyIds = companyRepository.findPageByManagerAndStatus(manager, status, pageable);
        }
        return getCompanyDTOPage(companyIds);
    } // Берем все заказы Менеджера + поиск + статус

    public Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManagerToSend(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы Менеджера + поиск + статус
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        Pageable pageable = companyPageable(pageNumber, pageSize, "desc");
        Page<Long> companyIds;
        if (!keyword.isEmpty()){
            companyIds = companyRepository.findPageByManagerAndStatusAndKeyWords(manager,keyword, status, manager, keyword, status, pageable);
        }
        else{
            companyIds = companyRepository.findPageByManagerAndStatus(manager, status, pageable);
        }
        return getCompanyDTOPage(companyIds, true);
    } // Берем все заказы Менеджера + поиск + статус

    @Override
    public Page<CompanyListDTO> getAllCompaniesDtoToOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize) { // Берем все заказы Владельца + поиск + статус
        return getAllCompaniesDtoToOwner(principal, keyword, status, pageNumber, pageSize, "desc");
    }

    @Override
    public Page<CompanyListDTO> getAllCompaniesDtoToOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection) { // Берем все заказы Владельца + поиск + статус
        List<Manager> managerList = userService.findManagersByUserName(principal.getName()).stream().toList();
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keyword.isEmpty()){
            companyIds = companyRepository.findPageByOwnerListAndStatusAndKeyWords(managerList, keyword, status, keyword, status, pageable);
        }
        else{
            companyIds = companyRepository.findPageByOwnerAndStatusToOwner(managerList, status, pageable);
        }
        return getCompanyDTOPage(companyIds);
    } // Берем все заказы Владельца + поиск + статус

    @Override
    public Page<CompanyListDTO> getAllCompaniesDTOListOwner(Principal principal, String keyword, int pageNumber, int pageSize) {
        return getAllCompaniesDTOListOwner(principal, keyword, pageNumber, pageSize, "desc");
    }

    @Override
    public Page<CompanyListDTO> getAllCompaniesDTOListOwner(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        List<Manager> managerList = userService.findManagersByUserName(principal.getName()).stream().toList();
        Pageable pageable = companyPageable(pageNumber, pageSize, sortDirection);
        Page<Long> companyIds;
        if (!keyword.isEmpty()){
            log.info("Отработал метод с keywords");
            companyIds = companyRepository.findPageByOwnerAndKeyWord(managerList, keyword, keyword, pageable);
        }
        else {
            companyIds = companyRepository.findPageIdToOwnerLive(
                    managerList,
                    BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                    liveCutoff(),
                    pageable
            );
        }
        return getCompanyDTOPage(companyIds);
    }

    private Page<CompanyListDTO> getCompanyDTOPage(Page<Long> companyIds) {
        return getCompanyDTOPage(companyIds, false);
    }

    private Page<CompanyListDTO> getCompanyDTOPage(Page<Long> companyIds, boolean onlyAfterDateNewTry) {
        if (companyIds.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), companyIds.getPageable(), companyIds.getTotalElements());
        }

        List<Long> ids = companyIds.getContent();
        Map<Long, Integer> orderById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderById.put(ids.get(i), i);
        }

        List<Company> companies = companyRepository.findAll(ids).stream()
                .sorted(Comparator.comparingInt(company -> orderById.getOrDefault(company.getId(), Integer.MAX_VALUE)))
                .toList();
        Map<Long, NextOrderRequestSummary> nextOrderSummaries = nextOrderSummaries(ids);

        List<CompanyListDTO> companyListDTOs = companies.stream()
                .map(company -> convertCompanyListDTO(company, nextOrderSummaries.get(company.getId())))
                .filter(company -> !onlyAfterDateNewTry || LocalDate.now().isAfter(company.getDateNewTry()))
                .collect(Collectors.toList());

        return new PageImpl<>(companyListDTOs, companyIds.getPageable(), companyIds.getTotalElements());
    }

    private Pageable companyPageable(int pageNumber, int pageSize, String sortDirection) {
        Sort sort = "asc".equalsIgnoreCase(sortDirection)
                ? Sort.by("updateStatus").descending().and(Sort.by("id").descending())
                : Sort.by("updateStatus").ascending().and(Sort.by("id").ascending());
        return PageRequest.of(Math.max(pageNumber, 0), Math.max(pageSize, 1), sort);
    }

    private LocalDate liveCutoff() {
        return BoardLiveSlice.cutoff(liveSliceRetentionDays);
    }


    private Page<CompanyListDTO> getPage(List<Company> companyPage, int pageNumber, int pageSize) {
        return getPage(companyPage, pageNumber, pageSize, "desc");
    }

    private Page<CompanyListDTO> getPage(List<Company> companyPage, int pageNumber, int pageSize, String sortDirection) {
        List<Company> sortedCompanies = sortCompaniesByDaysWithoutChanges(companyPage, sortDirection);
        Pageable pageable = PageRequest.of(
                pageNumber,
                pageSize,
                Sort.by("updateStatus").descending().and(Sort.by("id").descending())
        );
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), sortedCompanies.size());
        List<CompanyListDTO> companyListDTOs = sortedCompanies.subList(start, end)
                .stream()
                .map(this::convertCompanyListDTO)
                .collect(Collectors.toList());
        return new PageImpl<>(companyListDTOs, pageable, sortedCompanies.size());
    }


    private Page<CompanyListDTO> getPageIsAfter(List<Company> companyPage, int pageNumber, int pageSize) {
        return getPageIsAfter(companyPage, pageNumber, pageSize, "desc");
    }

    private Page<CompanyListDTO> getPageIsAfter(List<Company> companyPage, int pageNumber, int pageSize, String sortDirection) {
        List<Company> sortedCompanies = sortCompaniesByDaysWithoutChanges(companyPage, sortDirection);
        Pageable pageable = PageRequest.of(
                pageNumber,
                pageSize,
                Sort.by("updateStatus").descending().and(Sort.by("id").descending())
        );
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), sortedCompanies.size());
        List<CompanyListDTO> companyListDTOs = sortedCompanies.subList(start, end)
                .stream()
                .map(this::convertCompanyListDTO)
                .filter(company -> LocalDate.now().isAfter(company.getDateNewTry()))
                .collect(Collectors.toList());
        return new PageImpl<>(companyListDTOs, pageable, sortedCompanies.size());
    }

    private List<Company> sortCompaniesByDaysWithoutChanges(List<Company> companies, String sortDirection) {
        if (companies == null || companies.isEmpty()) {
            return List.of();
        }

        Comparator<Company> comparator = Comparator.comparing(
                Company::getUpdateStatus,
                Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(
                Company::getId,
                Comparator.nullsLast(Comparator.naturalOrder())
        );

        if ("asc".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }

        return companies.stream()
                .sorted(comparator)
                .toList();
    }


    @Override
    public List<CompanyDTO> getAllCompaniesDTOList(String keywords, String status) {
        return null;
    }


    @Transactional(readOnly = true)
    public CompanyDTO getCompaniesDTOById(Long companyId){  // Берем компанию ДТО по Id
        Company company = companyRepository.findByIdForCompanyDto(companyId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", companyId)
        ));
        companyRepository.findByIdWithWorkers(companyId);
        companyRepository.findByIdWithFilials(companyId);
        return convertToDto(company);
    } // Берем компанию ДТО по Id

    @Override
    public Company getCompaniesById(Long id) { // Берем компанию по Id
        return companyRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", id)
        ));
    } // Берем компанию по Id

    @Override
    public CompanyDTO getCompaniesAllStatusByIdAndKeyword(Long companyId, String keywords) { // Взять компанию по Id и поиску
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            Company company = companyRepository.findByIdAndTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(companyId, keywords, keywords).orElse(null);
            if (company != null){
                log.info("Отработал не равно null " + company.getId());
                return convertToDto(company);
            }
            else {
                log.info("Отработал равно null");
                return convertToDto(companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId))));
            }

        }
        else return convertToDto(companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", companyId))));
    } // Взять компанию по Id и поиску



    public CompanyDTO convertToDtoToManager(Long leadId, Principal principal) {
        LeadDTO leadDTO = leadService.findById(leadId);

        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone(leadDTO.getTelephoneLead());
        companyDTO.setCity(leadDTO.getCityLead());
        applyLeadCompanyDefaults(companyDTO, leadDTO);
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(leadOperatorName(leadDTO));
        companyDTO.setManager(convertToManagerDto(leadDTO.getManager()));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        ensureFilial(companyDTO);

        Set<WorkerDTO> workers = workerService.getAllWorkersByManagerId(leadDTO.getManager().getId());

        companyDTO.setWorkers(workers); // ✅ добавляем весь список работников в DTO

        WorkerDTO selectedWorker = selectRandomWorker(workers);
        companyDTO.setWorker(selectedWorker);
        log.info("📦 CompanyDTO подготовлен для лида ID {}. Назначен сотрудник: {}", leadId, selectedWorker.getUser().getFio());

        return companyDTO;
    }


    private WorkerDTO selectRandomWorker(Set<WorkerDTO> workers) {
        if (workers.isEmpty()) {
            throw new IllegalStateException("❌ Нет доступных сотрудников для назначения.");
        }

        List<WorkerDTO> workerList = new ArrayList<>(workers);
        int index = new SecureRandom().nextInt(workerList.size());

        WorkerDTO selected = workerList.get(index);
        log.debug("🎯 Случайно выбран сотрудник {} (ID user: {}) из {} доступных",
                selected.getUser().getFio(), selected.getUser().getId(), workerList.size());

        return selected;
    }


    public CompanyDTO convertToDtoToOperator(Long leadId, Principal principal) { //    Метод подготовки ДТО при создании компании из Лида оператора
        LeadDTO leadDTO = leadService.findById(leadId);
        Operator operator = operatorService.getOperatorById(leadDTO.getOperatorId());
        Manager manager = operatorManager(operator);
        return getCompanyDTO(leadDTO, manager);
    } //    Метод подготовки ДТО при создании компании из Лида оператора



    @Override
    public Optional<Company> getCompanyByTelephonAndTitle(String telephoneNumber, String title) {
        Optional<Company> companyOpt = companyRepository.getByTelephoneOrTitleIgnoreCase(telephoneNumber, title);
        return companyOpt;
    }

    @Override
    public Optional<Company> findByGroupId(String groupId) {
        List<Company> companies = companyRepository.findAllByGroupId(groupId);
        if (companies.size() > 1) {
            log.warn("Найдено {} компаний с одинаковым WhatsApp groupId={}. Для общей обработки выбрана первая: id={}",
                    companies.size(), groupId, companies.getFirst().getId());
        }
        return companies.stream().findFirst();
    }

    private Manager operatorManager(Operator operator) {
        if (operator == null) {
            throw new IllegalStateException("Оператор для лида не найден");
        }

        Long managerId = switch (operator.getCount()) {
            case 0 -> OPERATOR_COUNT_ZERO_MANAGER_ID;
            case 1 -> OPERATOR_COUNT_ONE_MANAGER_ID;
            default -> throw new IllegalStateException("Неизвестное значение счетчика оператора: " + operator.getCount());
        };

        return managerService.getManagerById(managerId);
    }

    private CompanyDTO getCompanyDTO(LeadDTO leadDTO, Manager manager) {
        if (manager == null || manager.getUser() == null) {
            throw new IllegalStateException("Менеджер для операторского лида не найден");
        }

        Set<WorkerDTO> managerWorkers = workerService.getAllWorkersByManagerId(manager.getId());
        if (managerWorkers.isEmpty()) {
            throw new IllegalStateException("У менеджера нет доступных сотрудников");
        }

        List<WorkerDTO> workers = new ArrayList<>(managerWorkers);
        WorkerDTO randomWorker = workers.get(new SecureRandom().nextInt(workers.size()));

        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone(leadDTO.getTelephoneLead());
        companyDTO.setCity(leadDTO.getCityLead());
        applyLeadCompanyDefaults(companyDTO, leadDTO);
        companyDTO.setUser(convertToUserDto(manager.getUser()));
        companyDTO.setOperator(leadOperatorName(leadDTO));
        companyDTO.setManager(convertToManagerDto(manager));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        ensureFilial(companyDTO);
        companyDTO.setWorkers(managerWorkers);
        companyDTO.setWorker(randomWorker);
        return companyDTO;
    }

    public CompanyDTO convertToDtoToManagerNotLead(Principal principal) {
        Long userId = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"))
                .getId();

        Manager manager = managerService.getManagerByUserId(userId);

        // Получаем и преобразуем список работников
        Set<WorkerDTO> workers = workerService.getAllWorkersByManagerId(manager.getId());

        WorkerDTO selectedWorker = selectRandomWorker(workers);

        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone("");
        companyDTO.setCity("Не задан");
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(null);
        companyDTO.setManager(convertToManagerDto(manager));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        companyDTO.setFilial(new FilialDTO());
        companyDTO.setWorkers(workers); // ✅ список для отображения
        companyDTO.setWorker(selectedWorker); // ✅ назначенный по умолчанию

        log.info("📦 CompanyDTO создан без лида. Назначен менеджер: {}, сотрудник: {}",
                manager.getUser().getFio(), selectedWorker.getUser().getFio());

        return companyDTO;
    }

    private String leadOperatorName(LeadDTO leadDTO) {
        return leadDTO != null && leadDTO.getOperator() != null && leadDTO.getOperator().getUser() != null
                ? leadDTO.getOperator().getUser().getFio()
                : null;
    }

    private void applyLeadCompanyDefaults(CompanyDTO companyDTO, LeadDTO leadDTO) {
        if (leadDTO == null) {
            return;
        }

        companyDTO.setTelephone(firstNonBlank(
                companyDTO.getTelephone(),
                leadDTO.getTelephoneLead(),
                firstMultiValue(leadDTO.getWhatsappPhones()),
                firstMultiValue(leadDTO.getMobilePhones()),
                firstMultiValue(leadDTO.getPhones())
        ));
        companyDTO.setCity(firstNonBlank(companyDTO.getCity(), leadDTO.getCityLead(), "Не задан"));
        companyDTO.setTitle(firstNonBlank(leadDTO.getCompanyName(), companyDTO.getTitle()));
        companyDTO.setUrlSite(firstNonBlank(firstMultiValue(leadDTO.getWebsites()), companyDTO.getUrlSite()));
        companyDTO.setEmail(firstNonBlank(firstMultiValue(leadDTO.getEmails()), companyDTO.getEmail()));

        if (!clean(leadDTO.getAddress()).isBlank()) {
            ensureFilial(companyDTO).setTitle(leadDTO.getAddress());
        }

        String comments = leadCompanyComments(leadDTO);
        if (!comments.isBlank()) {
            companyDTO.setCommentsCompany(comments);
        }
        companyDTO.setContacts(leadCompanyContacts(leadDTO));
        companyDTO.setInfo(leadCompanyInfo(leadDTO));
    }

    private FilialDTO ensureFilial(CompanyDTO companyDTO) {
        if (companyDTO.getFilial() == null) {
            companyDTO.setFilial(new FilialDTO());
        }
        return companyDTO.getFilial();
    }

    private String leadCompanyComments(LeadDTO leadDTO) {
        StringJoiner comments = new StringJoiner("\n");
        addLeadComment(comments, "Отрасли", leadDTO.getIndustries());
        addLeadComment(comments, "Тип", leadDTO.getCompanyType());
        addLeadComment(comments, "Регион", leadDTO.getRegion());
        addLeadComment(comments, "Адрес", leadDTO.getAddress());
        addLeadComment(comments, "Телефоны", leadDTO.getPhones());
        addLeadComment(comments, "Мобильные", leadDTO.getMobilePhones());
        addLeadComment(comments, "WhatsApp", leadDTO.getWhatsappPhones());
        addLeadComment(comments, "Email", leadDTO.getEmails());
        addLeadComment(comments, "Сайты", leadDTO.getWebsites());
        addLeadComment(comments, "VK", leadDTO.getVkUrl());
        addLeadComment(comments, "TG", leadDTO.getTelegramUrl());
        addLeadComment(comments, "Комментарий лида", leadDTO.getCommentsLead());
        return truncate(comments.toString(), COMPANY_COMMENTS_MAX_LENGTH);
    }

    private Set<CompanyContactDTO> leadCompanyContacts(LeadDTO leadDTO) {
        Map<String, CompanyContactDTO> contacts = new LinkedHashMap<>();
        addContact(contacts, CompanyContactType.PHONE, leadDTO.getTelephoneLead(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.PHONE, leadDTO.getPhones(), false, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.MOBILE, leadDTO.getMobilePhones(), false, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.WHATSAPP, leadDTO.getWhatsappPhones(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.EMAIL, leadDTO.getEmails(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.WEBSITE, leadDTO.getWebsites(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.VK, leadDTO.getVkUrl(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        addMultiContacts(contacts, CompanyContactType.TELEGRAM, leadDTO.getTelegramUrl(), true, COMPANY_DATA_SOURCE_LEAD_IMPORT, leadDTO.getId());
        return new LinkedHashSet<>(contacts.values());
    }

    private CompanyInfoDTO leadCompanyInfo(LeadDTO leadDTO) {
        if (leadDTO == null
                || (clean(leadDTO.getRegion()).isBlank()
                && clean(leadDTO.getAddress()).isBlank()
                && clean(leadDTO.getIndustries()).isBlank()
                && clean(leadDTO.getCompanyType()).isBlank())) {
            return null;
        }

        return CompanyInfoDTO.builder()
                .region(clean(leadDTO.getRegion()))
                .address(clean(leadDTO.getAddress()))
                .industries(clean(leadDTO.getIndustries()))
                .companyType(clean(leadDTO.getCompanyType()))
                .source(COMPANY_DATA_SOURCE_LEAD_IMPORT)
                .sourceLeadId(leadDTO.getId())
                .build();
    }

    private void addMultiContacts(
            Map<String, CompanyContactDTO> contacts,
            CompanyContactType type,
            String values,
            boolean firstPrimary,
            String source,
            Long sourceLeadId
    ) {
        boolean primary = firstPrimary;
        for (String value : multiValues(values)) {
            addContact(contacts, type, value, primary, source, sourceLeadId);
            primary = false;
        }
    }

    private void addContact(
            Map<String, CompanyContactDTO> contacts,
            CompanyContactType type,
            String value,
            boolean primary,
            String source,
            Long sourceLeadId
    ) {
        String cleanValue = clean(value);
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
                .source(source)
                .sourceLeadId(sourceLeadId)
                .build());
    }

    private List<String> multiValues(String value) {
        String cleanValue = clean(value);
        if (cleanValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(cleanValue.split("[,;\\r\\n]+"))
                .map(this::clean)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private String normalizeContactValue(CompanyContactType type, String value) {
        String cleanValue = clean(value);
        if (type == CompanyContactType.PHONE
                || type == CompanyContactType.MOBILE
                || type == CompanyContactType.WHATSAPP) {
            return LeadPhoneNormalizer.normalize(cleanValue);
        }
        return cleanValue.toLowerCase(Locale.ROOT);
    }

    private void addLeadComment(StringJoiner comments, String label, String value) {
        String cleanValue = clean(value);
        if (!cleanValue.isBlank()) {
            comments.add(label + ": " + cleanValue);
        }
    }

    private String firstMultiValue(String value) {
        String cleanValue = clean(value);
        if (cleanValue.isBlank()) {
            return "";
        }
        for (String part : cleanValue.split("[,;\\r\\n]+")) {
            String cleanPart = clean(part);
            if (!cleanPart.isBlank()) {
                return cleanPart;
            }
        }
        return cleanValue;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleanValue = clean(value);
            if (!cleanValue.isBlank()) {
                return cleanValue;
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        String cleanValue = clean(value);
        if (cleanValue.length() <= maxLength) {
            return cleanValue;
        }
        return cleanValue.substring(0, maxLength);
    }






    @Override
    public int getAllCompanyDTOByStatus(String status) {
        return companyRepository.countByStatusTitle(status);
    }

    @Override
    public Map<String, Integer> countCompaniesByStatus() {
        return toStatusCountMap(companyRepository.countGroupedByStatusLive(
                BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                liveCutoff()
        ));
    }

    @Override
    public Map<String, Integer> countCompaniesByStatusToManager(Manager manager) {
        if (manager == null) {
            return Map.of();
        }
        return toStatusCountMap(companyRepository.countGroupedByStatusAndManagerLive(
                manager,
                BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                liveCutoff()
        ));
    }

    @Override
    public Map<String, Integer> countCompaniesByStatusToOwner(Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return Map.of();
        }
        return toStatusCountMap(companyRepository.countGroupedByStatusAndManagersLive(
                managerList,
                BoardLiveSlice.HIDDEN_COMPANY_STATUSES,
                liveCutoff()
        ));
    }

    private Map<String, Integer> toStatusCountMap(List<Object[]> rows) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }

        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            String status = row[0] == null ? "" : row[0].toString();
            long count = row[1] instanceof Number number ? number.longValue() : 0L;
            result.put(status, count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
        }
        return result;
    }

    @Override
    public int countAllCompanies() {
        return companyRepository.countAllCompanies();
    }

    @Override
    public int countAllCompaniesToManager(Manager manager) {
        if (manager == null) {
            return 0;
        }
        return companyRepository.countByManager(manager);
    }

    @Override
    public int countAllCompaniesToOwner(Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return 0;
        }
        return companyRepository.countByManagers(managerList);
    }

    @Override
    public int getAllCompanyDTOByStatusToManager(Manager manager, String status) {
        return companyRepository.countByManagerAndStatusTitle(manager, status);
    }

    @Override
    public int getAllCompanyDTOByStatusToOwner(Set<Manager> managerList, String status) {
        return companyRepository.countByManagersAndStatusTitle(managerList, status);
    }

    @Override
    public List<Object[]> getAllNewCompanies2(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return companyRepository.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
    }

    @Override
    public Map<String, Long> getAllNewCompanies(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return companyRepository.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth).stream()
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],   // ФИО менеджера
                        obj -> (Long) obj[1]      // Количество компаний
                ));
    }

    private Set<CompanyDTO> convertToCompanyDTOSet(Set<Company> companies){ // перевод компании в ДТО Сэт
        return companies.stream().map(this::convertToDto).collect(Collectors.toSet());
    } // перевод компании в ДТО
    private List<CompanyDTO> convertToCompanyDTOList(List<Company> companies){ // перевод компании в ДТО Лист
        return companies.stream().map(this::convertToDto).collect(Collectors.toList());
    } // перевод компании в ДТО

    public CompanyDTO convertToDto(Company company) { // перевод компании в ДТО
        if (company.getId() != null) {
            CompanyDTO companyDTO = new CompanyDTO();
            companyDTO.setId(company.getId());
            companyDTO.setTitle(company.getTitle());
            companyDTO.setTelephone(company.getTelephone());
            companyDTO.setCity(company.getCity());
            companyDTO.setUrlChat(company.getUrlChat());
            companyDTO.setUrlSite(company.getUrlSite());
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
            companyDTO.setGroupId(company.getGroupId());
            companyDTO.setTelegramGroupChatId(company.getTelegramGroupChatId());
            companyDTO.setTelegramGroupLinked(telegramGroupLinkService.isTelegramGroupLinked(company));
            companyDTO.setTelegramBotInviteUrl(telegramGroupLinkService.buildInviteUrl(company));
            companyDTO.setMaxGroupChatId(company.getMaxGroupChatId());
            companyDTO.setMaxGroupLinked(maxGroupLinkService.isMaxGroupLinked(company));
            companyDTO.setMaxBotInviteUrl(maxGroupLinkService.buildInviteUrl(company));
            companyDTO.setPublicationProgressReportsEnabled(company.isPublicationProgressReportsEnabled());
            // Convert related entities to DTOs
            companyDTO.setUser(convertToUserDto(company.getUser()));
            companyDTO.setManager(convertToManagerDto(company.getManager()));
            companyDTO.setWorkers(convertToWorkerDtoSet(company.getWorkers()));
            companyDTO.setStatus(convertToCompanyStatusDto(company.getStatus()));
            companyDTO.setCategoryCompany(convertToCategoryDto(company.getCategoryCompany()));
            companyDTO.setSubCategory(convertToSubCategoryDto(company.getSubCategory()));
            companyDTO.setFilials(convertToFilialDtoSet(company.getFilial()));
            companyDTO.setContacts(convertToContactDtoSet(company.getContacts()));
            companyDTO.setInfo(convertToInfoDto(company.getInfo()));
            companyDTO.setOrders(Collections.emptySet());
            return companyDTO;
        }
        else {
            log.info("отработал нулл");
            return new CompanyDTO();
        }
    } // перевод компании в ДТО

    private List<CompanyListDTO> convertToCompanyListDTO(List<Company> companies) {
        return companies.stream().map(this::convertCompanyListDTO).collect(Collectors.toList());
    }

    public CompanyListDTO convertCompanyListDTO(Company company) { // перевод компании в ДТО
        if (company == null || company.getId() == null) {
            return convertCompanyListDTO(company, null);
        }
        return convertCompanyListDTO(company, nextOrderSummaries(List.of(company.getId())).get(company.getId()));
    }

    private CompanyListDTO convertCompanyListDTO(Company company, NextOrderRequestSummary nextOrderSummary) { // перевод компании в ДТО
        if (company != null && company.getId() != null) {
            Filial firstFilial = company.getFilial() == null
                    ? null
                    : company.getFilial().stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            CompanyListDTO companyListDTO = new CompanyListDTO();
            companyListDTO.setId(company.getId());
            companyListDTO.setTitle(company.getTitle());
            companyListDTO.setTelephone(company.getTelephone());
            companyListDTO.setUrlChat(company.getUrlChat());
            companyListDTO.setGroupId(company.getGroupId());
            companyListDTO.setTelegramGroupChatId(company.getTelegramGroupChatId());
            companyListDTO.setTelegramGroupLinked(telegramGroupLinkService.isTelegramGroupLinked(company));
            companyListDTO.setTelegramBotInviteUrl(telegramGroupLinkService.buildInviteUrl(company));
            companyListDTO.setMaxGroupChatId(company.getMaxGroupChatId());
            companyListDTO.setMaxGroupLinked(maxGroupLinkService.isMaxGroupLinked(company));
            companyListDTO.setMaxBotInviteUrl(maxGroupLinkService.buildInviteUrl(company));
            companyListDTO.setCountFilials(company.getFilial() == null ? 0 : company.getFilial().size());
            companyListDTO.setUrlFilial(firstFilial != null && firstFilial.getUrl() != null ? firstFilial.getUrl() : "пусто");
            companyListDTO.setStatus(company.getStatus() != null ? company.getStatus().getTitle() : "");
            companyListDTO.setManager(company.getManager() != null && company.getManager().getUser() != null
                    ? company.getManager().getUser().getFio()
                    : "");
            companyListDTO.setCommentsCompany(company.getCommentsCompany());
            companyListDTO.setCity(firstFilial != null && firstFilial.getCity() != null ? firstFilial.getCity().getTitle() : "");
            companyListDTO.setDateNewTry(company.getDateNewTry());
            companyListDTO.setNextOrderRequestsCount(nextOrderSummary == null ? 0 : nextOrderSummary.openCount());
            companyListDTO.setFailedNextOrderRequestsCount(nextOrderSummary == null ? 0 : nextOrderSummary.failedCount());
            companyListDTO.setNextOrderRequestFilialTitle(nextOrderSummary == null ? null : nextOrderSummary.latestFilialTitle());
            companyListDTO.setNextOrderRequestError(nextOrderSummary == null ? null : nextOrderSummary.latestError());
            return companyListDTO;
        }
        else {
            log.info("отработал нулл");
            return new CompanyListDTO();
        }
    } // перевод компании в ДТО

    private Map<Long, NextOrderRequestSummary> nextOrderSummaries(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, MutableNextOrderSummary> mutableSummaries = new HashMap<>();
        List<NextOrderRequest> requests = nextOrderRequestRepository.findByCompanyIdInAndStatusIn(
                companyIds,
                OPEN_NEXT_ORDER_STATUSES
        );

        for (NextOrderRequest request : requests) {
            if (request.getCompany() == null || request.getCompany().getId() == null) {
                continue;
            }
            MutableNextOrderSummary summary = mutableSummaries.computeIfAbsent(
                    request.getCompany().getId(),
                    id -> new MutableNextOrderSummary()
            );
            summary.openCount++;
            if (isAfter(request.getUpdatedAt(), summary.latestRequestAt)) {
                summary.latestRequestAt = request.getUpdatedAt();
                summary.latestFilialTitle = request.getFilial() == null ? null : request.getFilial().getTitle();
            }
            if (request.getStatus() == NextOrderRequestStatus.FAILED) {
                summary.failedCount++;
                if (request.getErrorMessage() != null
                        && !request.getErrorMessage().isBlank()
                        && isAfter(request.getUpdatedAt(), summary.latestErrorAt)) {
                    summary.latestErrorAt = request.getUpdatedAt();
                    summary.latestError = request.getErrorMessage();
                }
            }
        }

        Map<Long, NextOrderRequestSummary> result = new HashMap<>();
        mutableSummaries.forEach((companyId, summary) -> result.put(
                companyId,
                new NextOrderRequestSummary(
                        summary.openCount,
                        summary.failedCount,
                        summary.latestFilialTitle,
                        summary.latestError
                )
        ));
        return result;
    }

    private boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    private static class MutableNextOrderSummary {
        private int openCount;
        private int failedCount;
        private LocalDateTime latestRequestAt;
        private String latestFilialTitle;
        private LocalDateTime latestErrorAt;
        private String latestError;
    }

    private Set<OrderDTO> convertToOrderDTOSet(Set<Order> orders){ // перевод заказа в ДТО Сэт
        return orders.stream().map(this::convertToOrderDTO).collect(Collectors.toSet());
    } // перевод заказа в ДТО Сэт

    private OrderDTO convertToOrderDTO(Order order){ // перевод заказа в ДТО
        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged();
        // Вычисляем разницу между датами
        Period period = Period.between(changedDate, now);
        // Преобразуем период в дни
        int daysDifference = period.getDays();
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .counter(order.getCounter())
                .sum(order.getSum())
                .created(order.getCreated())
                .changed(order.getChanged())
                .status(convertToOrderStatusDTO(order.getStatus()))
                .filial(convertToFilialDTO(order.getFilial()))
                .company(convertToCompanyDTO(order.getCompany()))
                .dayToChangeStatusAgo(period.getDays())
                .manager(convertToManagerDTO(order.getManager()))
                .worker(convertToWorkerDto(order.getWorker()))
                .orderDetailsId(order.getDetails().iterator().next().getId())
                .build();
    } // перевод заказа в ДТО

    private ProductDTO convertToProductDTO(Product product){ // перевод продукта в ДТО
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    } // перевод продукта в ДТО

    private OrderStatusDTO convertToOrderStatusDTO(OrderStatus orderStatus){ // перевод статус заказа в ДТО
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    } // перевод статус заказа в ДТО

    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details){ // перевод деталей заказа в ДТО
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    } // перевод деталей заказа в ДТО
    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){ // перевод деталей заказа в ДТО
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .comment(orderDetails.getComment())
                .build();
    } // перевод деталей заказа в ДТО

    private CompanyDTO convertToCompanyDTO(Company company){ // перевод компании в ДТО
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .urlChat(company.getUrlChat())
                .urlSite(company.getUrlSite())
                .telephone(company.getTelephone())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .categoryCompany(convertToCategoryDto(company.getCategoryCompany()))
                .subCategory(convertToSubCategoryDto(company.getSubCategory()))
                .groupId(company.getGroupId())
                .telegramGroupChatId(company.getTelegramGroupChatId())
                .telegramGroupLinked(telegramGroupLinkService.isTelegramGroupLinked(company))
                .telegramBotInviteUrl(telegramGroupLinkService.buildInviteUrl(company))
                .maxGroupChatId(company.getMaxGroupChatId())
                .maxGroupLinked(maxGroupLinkService.isMaxGroupLinked(company))
                .maxBotInviteUrl(maxGroupLinkService.buildInviteUrl(company))
                .publicationProgressReportsEnabled(company.isPublicationProgressReportsEnabled())
                .build();
    }

    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){ // перевод филиалов в ДТО Сэт
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    } // перевод филиалов в ДТО Сэт
    private FilialDTO convertToFilialDTO(Filial filial){ // перевод филиала в ДТО
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .city(filial.getCity())
                .build();
    } // перевод филиала в ДТО

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){ // перевод работника в ДТО Лист
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    } // перевод работника в ДТО Лист
    private WorkerDTO convertToWorkerDTO(Worker worker){ // перевод работника в ДТО
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(slimUser(worker.getUser()))
                .build();
    } // перевод работника в ДТО

    private ManagerDTO convertToManagerDTO(Manager manager){ // перевод менеджера в ДТО
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(slimUser(manager.getUser()))
                .payText(manager.getPayText())
                .build();
    } // перевод менеджера в ДТО

    private UserDTO convertToUserDtoToManager(Principal principal) { // перевод юзера в ДТО
        UserDTO userDTO = new UserDTO();
        User user = userService.findByUserName(principal.getName()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", principal.getName())
        ));
        userDTO.setUsername(user.getUsername());
        userDTO.setFio(user.getFio());
        return userDTO;
    } // перевод юзера в ДТО

    private UserDTO convertToUserDto(User user) { // перевод юзера в ДТО
        if (user == null) {
            return null;
        }
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setEmail(user.getEmail());
        userDTO.setFio(user.getFio());
        return userDTO;
    } // перевод юзера в ДТО

    private ManagerDTO convertToManagerDto(Manager manager) { // перевод менеджера в ДТО
        if (manager == null) {
            return null;
        }
        ManagerDTO managerDTO = new ManagerDTO();
        managerDTO.setManagerId(manager.getId());
        managerDTO.setUser(slimUser(manager.getUser()));
        return managerDTO;
    } // перевод менеджера в ДТО

    private Set<WorkerDTO> convertToWorkerDtoSet(Set<Worker> workers) { // перевод менеджера в ДТО Сэт
        return workers.stream()
                .map(this::convertToWorkerDto)
                .collect(Collectors.toSet());
    } // перевод менеджера в ДТО Сэт

    private WorkerDTO convertToWorkerDto(Worker worker) { // перевод работника в ДТО
        if (worker == null) {
            return null;
        }
        WorkerDTO workerDTO = new WorkerDTO();
        workerDTO.setWorkerId(worker.getId());
        workerDTO.setUser(slimUser(worker.getUser()));
        return workerDTO;
    } // перевод работника в ДТО

    private User slimUser(User user) {
        if (user == null) {
            return null;
        }

        User slim = new User();
        slim.setId(user.getId());
        slim.setUsername(user.getUsername());
        slim.setFio(user.getFio());
        slim.setEmail(user.getEmail());
        slim.setPhoneNumber(user.getPhoneNumber());
        slim.setActive(user.isActive());
        slim.setTelegramChatId(user.getTelegramChatId());
        return slim;
    }

    private CompanyStatusDTO convertToCompanyStatusDto(CompanyStatus status) { // перевод статуса компании в ДТО
        CompanyStatusDTO statusDTO = new CompanyStatusDTO();
        statusDTO.setId(status.getId());
        statusDTO.setTitle(status.getTitle());
        return statusDTO;
    } // перевод статуса компании в ДТО

    private CategoryDTO convertToCategoryDto(Category category) { // перевод категории в ДТО
        if (category == null) {
            return CategoryDTO.builder().id(0L).categoryTitle("Не выбрана").build();
        }

        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId() != null ? category.getId() : 0L);
        categoryDTO.setCategoryTitle(category.getCategoryTitle() !=null ? category.getCategoryTitle() : "Не выбрана");
        return categoryDTO;
    } // перевод категории в ДТО

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        if (subCategory == null) {
            return new SubCategoryDTO(0L, "Не выбрана"); // Безопасное значение по умолчанию
        }

        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0L);
        subCategoryDTO.setSubCategoryTitle(
                subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не выбрана"
        );

        return subCategoryDTO;
    }// перевод подкатегории в ДТО

    private Set<FilialDTO> convertToFilialDtoSet(Set<Filial> filial) { // перевод филиала в ДТО Сэт
        return filial.stream()
                .map(this::convertToFilialDto)
                .collect(Collectors.toSet());
    } // перевод филиала в ДТО Сэт

    private FilialDTO convertToFilialDto(Filial filial) { // перевод филиала в ДТО
        FilialDTO filialDTO = new FilialDTO();
        filialDTO.setId(filial.getId());
        filialDTO.setTitle(filial.getTitle());
        filialDTO.setUrl(filial.getUrl());
        filialDTO.setCity(filial.getCity());
        // Other fields if needed
        return filialDTO;
    }

    private Set<CompanyContactDTO> convertToContactDtoSet(Set<CompanyContact> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return Collections.emptySet();
        }

        return contacts.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((CompanyContact contact) -> contact.getType() == null ? "" : contact.getType().name())
                        .thenComparing(contact -> contact.getId() == null ? 0L : contact.getId()))
                .map(this::convertToContactDto)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private CompanyContactDTO convertToContactDto(CompanyContact contact) {
        return CompanyContactDTO.builder()
                .id(contact.getId())
                .type(contact.getType() == null ? "" : contact.getType().name())
                .value(contact.getValue())
                .normalizedValue(contact.getNormalizedValue())
                .primaryContact(contact.isPrimaryContact())
                .source(contact.getSource())
                .sourceLeadId(contact.getSourceLeadId())
                .build();
    }

    private CompanyInfoDTO convertToInfoDto(CompanyInfo info) {
        if (info == null) {
            return null;
        }

        return CompanyInfoDTO.builder()
                .id(info.getId())
                .region(info.getRegion())
                .address(info.getAddress())
                .industries(info.getIndustries())
                .companyType(info.getCompanyType())
                .source(info.getSource())
                .sourceLeadId(info.getSourceLeadId())
                .build();
    }

    //    ======================================== COMPANY UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateCompany(CompanyDTO companyDTO, WorkerDTO newWorkerDTO, Long companyId) { // Обновление компании
        log.info("2. Вошли в обновление данных компании");
        Company saveCompany = companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", companyId)));
        boolean isChanged = false;

        if (saveCompany.getSubCategory() == null) {
            saveCompany.setSubCategory(convertSubCategoryDTOToSubCategory(companyDTO.getSubCategory()));
        }

        /*Временная проверка сравнений*/
        System.out.println("title: " + !Objects.equals(companyDTO.getTitle(), saveCompany.getTitle()));
        System.out.println("telephone: " + !Objects.equals(changeNumberPhone(companyDTO.getTelephone()), changeNumberPhone(saveCompany.getTelephone())));
        System.out.println("city: " + !Objects.equals(companyDTO.getCity(), saveCompany.getCity()));
        System.out.println("urlChat: " + !Objects.equals(companyDTO.getUrlChat(), saveCompany.getUrlChat()));
        System.out.println("email: " + !Objects.equals(companyDTO.getEmail(), saveCompany.getEmail()));
        System.out.println("active: " + !Objects.equals(companyDTO.isActive(), saveCompany.isActive()));
        System.out.println("publicationReports: " + !Objects.equals(companyDTO.isPublicationProgressReportsEnabled(), saveCompany.isPublicationProgressReportsEnabled()));
        System.out.println("comments: " +  !Objects.equals(companyDTO.getCommentsCompany(), saveCompany.getCommentsCompany()));
        System.out.println("status: " + !Objects.equals(statusId(companyDTO.getStatus()), statusId(saveCompany.getStatus())));
        System.out.println("category: " + !Objects.equals(categoryId(companyDTO.getCategoryCompany()), categoryId(saveCompany.getCategoryCompany())));

        System.out.println("subCategory: " + !Objects.equals(subCategoryId(companyDTO.getSubCategory()), subCategoryId(saveCompany.getSubCategory())));
        System.out.println("manager: " + !Objects.equals(managerId(companyDTO.getManager()), managerId(saveCompany.getManager())));
        System.out.println("workerId: " +  (newWorkerDTO.getWorkerId() != 0));
        System.out.println("filial: " +  (!companyDTO.getFilial().getTitle().isEmpty()));

        if (!Objects.equals(companyDTO.getTitle(), saveCompany.getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем названия компании");
            saveCompany.setTitle(companyDTO.getTitle());
            isChanged = true;
        }
        if (!Objects.equals(changeNumberPhone(companyDTO.getTelephone()), changeNumberPhone(saveCompany.getTelephone()))){ /*Проверка смены телефона*/
            log.info("Обновляем телефон компании");
            saveCompany.setTelephone(changeNumberPhone(companyDTO.getTelephone()));
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getCity(), saveCompany.getCity())){ /*Проверка смены города*/
            log.info("Обновляем город");
            saveCompany.setCity(companyDTO.getCity());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getUrlChat(), saveCompany.getUrlChat())){ /*Проверка смены ссылки на чат*/
            log.info("Обновляем ссылку на чат");
            saveCompany.setUrlChat(companyDTO.getUrlChat());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getUrlSite(), saveCompany.getUrlSite())){ /*Проверка смены официального сайта*/
            log.info("Обновляем официальный сайт");
            saveCompany.setUrlSite(companyDTO.getUrlSite());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getEmail(), saveCompany.getEmail())){ /*Проверка смены мейл*/
            log.info("Обновляем мейл");
            System.out.println("email: " + !Objects.equals(companyDTO.getEmail(), saveCompany.getEmail()));
            System.out.println(companyDTO.getEmail());
            saveCompany.setEmail(companyDTO.getEmail());
            System.out.println(saveCompany.getEmail());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.isActive(), saveCompany.isActive())){ /*Проверка активности*/
            log.info("Обновляем активность");
            saveCompany.setActive(companyDTO.isActive());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.isPublicationProgressReportsEnabled(), saveCompany.isPublicationProgressReportsEnabled())){
            log.info("Обновляем настройку коротких отчетов о публикациях");
            saveCompany.setPublicationProgressReportsEnabled(companyDTO.isPublicationProgressReportsEnabled());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getCommentsCompany(), saveCompany.getCommentsCompany())){ /*Проверка комментарий*/
            log.info("Обновляем комментарий");
            saveCompany.setCommentsCompany(companyDTO.getCommentsCompany());
            isChanged = true;
        }
        if (!Objects.equals(statusId(companyDTO.getStatus()), statusId(saveCompany.getStatus()))){ /*Проверка статус компании*/
            log.info("Обновляем статус компании");
            saveCompany.setStatus(companyStatusService.getCompanyStatusById(companyDTO.getStatus().getId()));
            isChanged = true;
        }
        if (!Objects.equals(categoryId(companyDTO.getCategoryCompany()), categoryId(saveCompany.getCategoryCompany()))){ /*Проверка категорию*/
            log.info("Обновляем категорию");
            saveCompany.setCategoryCompany(convertCategoryDTOToCategory(companyDTO.getCategoryCompany()));
            Set<Filial> filials = saveCompany.getFilial();
            reviewService.updateReviewByFilials(filials, categoryId(companyDTO.getCategoryCompany()), subCategoryId(companyDTO.getSubCategory()));
            isChanged = true;
        }
        if (!Objects.equals(subCategoryId(companyDTO.getSubCategory()), subCategoryId(saveCompany.getSubCategory()))){ /*Проверка субкатегорию*/
            log.info("Обновляем субкатегорию");
            saveCompany.setSubCategory(convertSubCategoryDTOToSubCategory(companyDTO.getSubCategory()));
            Set<Filial> filials = saveCompany.getFilial();
            reviewService.updateReviewByFilials(filials, categoryId(companyDTO.getCategoryCompany()), subCategoryId(companyDTO.getSubCategory()));
            isChanged = true;
        }
        if (!Objects.equals(managerId(companyDTO.getManager()), managerId(saveCompany.getManager()))){ /*Проверка менеджера*/
            log.info("Обновляем менеджера");
            saveCompany.setManager(managerService.getManagerById(companyDTO.getManager().getManagerId()));
            isChanged = true;
        }
        if (newWorkerDTO.getWorkerId() != 0) { /* Добваление нового работника*/
            log.info("Обновляем список работников");
            Set<Worker> workerSet = saveCompany.getWorkers();
            workerSet.add(workerService.getWorkerById(newWorkerDTO.getWorkerId()));
            saveCompany.setWorkers(workerSet);
            isChanged = true;
        }
        if (!companyDTO.getFilial().getTitle().isEmpty()) { /*Проверка списка работников*/
            log.info("Добавляем новый филиал");
            Set<Filial> existingFilials = saveCompany.getFilial(); // пытаемся получить текущий список филиалов из компании
            if (existingFilials == null) {
                existingFilials = new HashSet<>();// если он пустой, то создаем новый set
            }
            Set<Filial> newFilials = convertFilialDTOToFilial(companyDTO.getFilial()); // берем список из дто
            existingFilials.addAll(newFilials); // объединяем эти списки
            saveCompany.setFilial(existingFilials); // устанавливаем компании объединенный список
            //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
            log.info("4. Компания успешно создана");
            try {
                Company company1 = companyRepository.save(saveCompany); // сохраняем новую компанию в БД
                log.info("5. Компания успешно сохранена");
                for (Filial filial : company1.getFilial()) { // проходимся по всем филиалам и устанавливаем им компанию
                    filial.setCompany(company1); // Установка компании в филиале
                    filialService.save(filial); // Сохранение обновленного филиала
                }
                isChanged = true;
            } catch (Exception e) {
                log.error("Ошибка при сохранении нового филиала компании: " + e.getMessage());
            }
        }
        if (syncCompanyContacts(saveCompany, companyDTO.getContacts())) {
            log.info("Обновляем контакты компании");
            isChanged = true;
        }
        if (syncCompanyInfo(saveCompany, companyDTO.getInfo())) {
            log.info("Обновляем сведения компании");
            isChanged = true;
        }
        if  (isChanged){
            log.info("3. Начали сохранять обновленную компанию в БД");
            System.out.println(saveCompany.getEmail());
            companyRepository.save(saveCompany);
            log.info("4. Сохранили обновленную компанию в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Обновление компании

//    =====================================================================================================

    @Transactional
    public boolean deleteWorkers(Long companyId, Long workerId){ // Удаление работника
        try {
            log.info("2. Вошли в удаление работника из списка работников компании");
            Company saveCompany = companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", companyId)));
            saveCompany.getWorkers().remove(workerService.getWorkerById(workerId));
            companyRepository.save(saveCompany);
            log.info("3. Сохранили обновленную компанию в БД");
            return true;
        }
        catch (Exception e){
            return false;
        }
    } // Удаление работника
    @Transactional
    public boolean deleteFilial(Long companyId, Long filialId){ // Удаление филиала
        try {
            log.info("2. Вошли в удаление филиала из списка филиалов компании");
            Company saveCompany = companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", companyId)));
            saveCompany.getFilial().remove(filialService.getFilial(filialId));
            filialService.deleteFilial(filialId);
            companyRepository.save(saveCompany);
            log.info("3. Сохранили обновленную компанию в БД");
            return true;
        }
        catch (Exception e){
            return false;
        }
    } // Удаление филиала

    //    =====================================================================================================
    private User convertUserDTOToUser(UserDTO userDTO){ // перевод юзера из Дто в сущность
        return userService.findByUserName(userDTO.getUsername()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", userDTO.getUsername())
        ));
    } // перевод юзера из Дто в Сущность

    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){ // перевод менеджера из Дто в сущность
        return managerService.getManagerById(managerDTO.getManagerId());
    } // перевод менеджера из Дто в Сущность

    private Set<Worker> convertWorkerDTOToWorkersSetToSet(Set<WorkerDTO> workerDTO){ // перевод работников из Дто в сущность
        return workerDTO.stream()
                .map(workerDTO1 -> {
                    return workerService.getWorkerById(workerDTO1.getWorkerId());
                }).collect(Collectors.toSet());
    } // перевод работников из Дто в сущность

    private Set<Worker> convertWorkerDTOToWorker(WorkerDTO workerDTO){ // перевод работника из Дто в сущность
        Set<Worker> workers = new HashSet<>();
        if (workerDTO == null || workerDTO.getWorkerId() == null || workerDTO.getWorkerId() <= 0) {
            return workers;
        }
        workers.add(workerService.getWorkerById(workerDTO.getWorkerId()));
        return workers;
    } // перевод работника из Дто в сущность

    private CompanyStatus convertCompanyStatusDTOToCompanyStatus(CompanyStatusDTO companyStatusDTO){ // перевод статуса компании из Дто в сущность
        return companyStatusService.getCompanyStatusById(companyStatusDTO.getId());
    } // перевод статуса компании из Дто в сущность

    private Category convertCategoryDTOToCategory(CategoryDTO categoryDTO){ // перевод категории из Дто в сущность
        if (categoryDTO == null || categoryDTO.getId() == null || categoryDTO.getId() <= 0) {
            return null;
        }
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // перевод категории из Дто в сущность

    private SubCategory convertSubCategoryDTOToSubCategory(SubCategoryDTO subcategoryDTO){ // перевод подкатегории из Дто в сущность
        if (subcategoryDTO == null || subcategoryDTO.getId() == null || subcategoryDTO.getId() <= 0) {
            return null;
        }
        return subCategoryService.getCategoryByIdSubCategory(subcategoryDTO.getId());
    } // перевод подкатегории из Дто в сущность

    private Set<Filial> convertFilialDTOToFilial(FilialDTO filialDTO) { // перевод филиала из Дто в сущность
        if (!hasFilialData(filialDTO)) {
            return Collections.emptySet();
        }
        if (filialDTO.getCity() == null || filialDTO.getCity().getId() == null || filialDTO.getCity().getId() <= 0) {
            return Collections.emptySet();
        }
        filialDTO.setTitle(clean(filialDTO.getTitle()));
        filialDTO.setUrl(clean(filialDTO.getUrl()));
        Filial existingFilial = filialService.findFilialByTitleAndUrl(filialDTO.getTitle(), filialDTO.getUrl());
        if (existingFilial != null) {
            return Collections.singleton(existingFilial);
        } else {
            Filial newFilial = filialService.save(filialDTO);
            return Collections.singleton(newFilial);
        }
    } // перевод филиала из Дто в сущность

    private boolean hasFilialData(FilialDTO filialDTO) {
        return filialDTO != null
                && (!clean(filialDTO.getTitle()).isBlank()
                || !clean(filialDTO.getUrl()).isBlank());
    }

    private Set<CompanyContact> convertContactDTOSetToContacts(Set<CompanyContactDTO> contactDTOs, Company company) {
        if (contactDTOs == null || contactDTOs.isEmpty()) {
            return new LinkedHashSet<>();
        }

        Map<String, CompanyContact> contacts = new LinkedHashMap<>();
        for (CompanyContactDTO contactDTO : contactDTOs) {
            CompanyContact contact = convertContactDTOToContact(contactDTO, company);
            if (contact == null) {
                continue;
            }
            String key = contact.getType().name() + ":" + clean(contact.getNormalizedValue());
            contacts.putIfAbsent(key, contact);
        }
        return new LinkedHashSet<>(contacts.values());
    }

    private CompanyContact convertContactDTOToContact(CompanyContactDTO contactDTO, Company company) {
        if (contactDTO == null || clean(contactDTO.getValue()).isBlank() || clean(contactDTO.getType()).isBlank()) {
            return null;
        }

        CompanyContactType type;
        try {
            type = CompanyContactType.valueOf(contactDTO.getType());
        } catch (IllegalArgumentException exception) {
            return null;
        }

        String value = clean(contactDTO.getValue());
        return CompanyContact.builder()
                .id(contactDTO.getId())
                .company(company)
                .type(type)
                .value(value)
                .normalizedValue(firstNonBlank(contactDTO.getNormalizedValue(), normalizeContactValue(type, value)))
                .primaryContact(contactDTO.isPrimaryContact())
                .source(firstNonBlank(contactDTO.getSource(), COMPANY_DATA_SOURCE_MANUAL))
                .sourceLeadId(contactDTO.getSourceLeadId())
                .build();
    }

    private CompanyInfo convertInfoDTOToInfo(CompanyInfoDTO infoDTO, Company company) {
        if (!hasInfoData(infoDTO)) {
            return null;
        }

        return CompanyInfo.builder()
                .id(infoDTO.getId())
                .company(company)
                .region(blankToNull(infoDTO.getRegion()))
                .address(blankToNull(infoDTO.getAddress()))
                .industries(blankToNull(infoDTO.getIndustries()))
                .companyType(blankToNull(infoDTO.getCompanyType()))
                .source(firstNonBlank(infoDTO.getSource(), COMPANY_DATA_SOURCE_MANUAL))
                .sourceLeadId(infoDTO.getSourceLeadId())
                .build();
    }

    private boolean hasInfoData(CompanyInfoDTO infoDTO) {
        return infoDTO != null
                && (!clean(infoDTO.getRegion()).isBlank()
                || !clean(infoDTO.getAddress()).isBlank()
                || !clean(infoDTO.getIndustries()).isBlank()
                || !clean(infoDTO.getCompanyType()).isBlank());
    }

    private boolean syncCompanyContacts(Company company, Set<CompanyContactDTO> contactDTOs) {
        Set<CompanyContact> updatedContacts = convertContactDTOSetToContacts(contactDTOs, company);
        if (companyContactsSame(company.getContacts(), updatedContacts)) {
            return false;
        }

        if (company.getContacts() == null) {
            company.setContacts(new LinkedHashSet<>());
        }
        company.getContacts().clear();
        company.getContacts().addAll(updatedContacts);
        return true;
    }

    private boolean companyContactsSame(Set<CompanyContact> currentContacts, Set<CompanyContact> updatedContacts) {
        return contactFingerprint(currentContacts).equals(contactFingerprint(updatedContacts));
    }

    private Set<String> contactFingerprint(Set<CompanyContact> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return Collections.emptySet();
        }

        return contacts.stream()
                .filter(Objects::nonNull)
                .map(contact -> (contact.getType() == null ? "" : contact.getType().name())
                        + "|"
                        + clean(contact.getNormalizedValue())
                        + "|"
                        + clean(contact.getValue())
                        + "|"
                        + contact.isPrimaryContact())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean syncCompanyInfo(Company company, CompanyInfoDTO infoDTO) {
        CompanyInfo updatedInfo = convertInfoDTOToInfo(infoDTO, company);
        CompanyInfo currentInfo = company.getInfo();
        if (companyInfoSame(currentInfo, updatedInfo)) {
            return false;
        }

        if (updatedInfo == null) {
            company.setInfo(null);
            return true;
        }

        if (currentInfo == null) {
            company.setInfo(updatedInfo);
            return true;
        }

        currentInfo.setRegion(updatedInfo.getRegion());
        currentInfo.setAddress(updatedInfo.getAddress());
        currentInfo.setIndustries(updatedInfo.getIndustries());
        currentInfo.setCompanyType(updatedInfo.getCompanyType());
        currentInfo.setSource(updatedInfo.getSource());
        currentInfo.setSourceLeadId(updatedInfo.getSourceLeadId());
        return true;
    }

    private boolean companyInfoSame(CompanyInfo currentInfo, CompanyInfo updatedInfo) {
        if (currentInfo == null || updatedInfo == null) {
            return currentInfo == updatedInfo;
        }

        return Objects.equals(clean(currentInfo.getRegion()), clean(updatedInfo.getRegion()))
                && Objects.equals(clean(currentInfo.getAddress()), clean(updatedInfo.getAddress()))
                && Objects.equals(clean(currentInfo.getIndustries()), clean(updatedInfo.getIndustries()))
                && Objects.equals(clean(currentInfo.getCompanyType()), clean(updatedInfo.getCompanyType()))
                && Objects.equals(clean(currentInfo.getSource()), clean(updatedInfo.getSource()))
                && Objects.equals(currentInfo.getSourceLeadId(), updatedInfo.getSourceLeadId());
    }

    private Long statusId(CompanyStatusDTO status) {
        return status == null || status.getId() == null ? 0L : status.getId();
    }

    private Long statusId(CompanyStatus status) {
        return status == null || status.getId() == null ? 0L : status.getId();
    }

    private Long categoryId(CategoryDTO category) {
        return category == null || category.getId() == null ? 0L : category.getId();
    }

    private Long categoryId(Category category) {
        return category == null || category.getId() == null ? 0L : category.getId();
    }

    private Long subCategoryId(SubCategoryDTO subCategory) {
        return subCategory == null || subCategory.getId() == null ? 0L : subCategory.getId();
    }

    private Long subCategoryId(SubCategory subCategory) {
        return subCategory == null || subCategory.getId() == null ? 0L : subCategory.getId();
    }

    private Long managerId(ManagerDTO manager) {
        return manager == null || manager.getManagerId() == null ? 0L : manager.getManagerId();
    }

    private Long managerId(Manager manager) {
        return manager == null || manager.getId() == null ? 0L : manager.getId();
    }

    private String blankToNull(String value) {
        String cleanValue = clean(value);
        return cleanValue.isBlank() ? null : cleanValue;
    }


    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "79";
            String tel = a[0] + a[1];
            String tel2 = tel.replace("-","");
            String tel3 = tel2.replace("(", "");
            String tel4 = tel3.replace(")","");
            return tel4.replace(" ", "");
        } else {
            return phone;
        }
    } // Вспомогательный метод для корректировки номера телефона

    public boolean changeStatusForCompany(Long companyId, String title){ // смена статуса компании
        try {
            Company company = companyRepository.findById(companyId).orElse(null);
            assert company != null;
            company.setStatus(companyStatusService.getStatusByTitle(title));
            companyRepository.save(company);
            return true;
        } catch (Exception e){
            log.error(String.valueOf(e));
            return false;
        }
    } // смена статуса компании

    public void changeDataTry(Long companyId){ // смена даты новой попытки (рассылки)
        Company company = companyRepository.findById(companyId).orElse(null);
        assert company != null;
        company.setDateNewTry(company.getDateNewTry().plusDays(100));
        companyRepository.save(company);
    } // смена даты новой попытки (рассылки)
}


//    public Page<CompanyListDTO> getAllCompaniesDTOList(String keywords, int pageNumber, int pageSize){ // Показ всех компаний + поиск
//        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
//        Page<Company> companyPage;
//        List<CompanyListDTO> companyListDTOs = null;
//        if (!keywords.isEmpty()){
//            log.info("Отработал метод с keywords");
//            companyPage = companyRepository.findALLByTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(keywords, keywords,pageable);
//        }
//        else companyPage = companyRepository.findAllToAdmin(pageable);
//        companyListDTOs = companyPage.getContent()
//                .stream()
//                .map(this::convertCompanyListDTO)
//                .collect(Collectors.toList());
//        return new PageImpl<>(companyListDTOs, pageable, companyPage.getTotalElements());
//    } // Показ всех компаний + поиск
