package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.u_users.dto.*;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService{
    private final CompanyRepository companyRepository;
    private final LeadService leadService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final CompanyStatusService companyStatusService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final FilialService filialService;

    @Transactional
    public void save(Company company){
        companyRepository.save(company);
    } // Сохранение компании в БД

    //    Метод подготовки ДТО при создании компании из Лида менеджером

    //      =====================================CREATE USERS - START=======================================================
    // Создание нового пользователя "Клиент" - начало
    @Transactional
    public boolean save(CompanyDTO companyDTO){ // Сохранение новой компании из дто
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");
        //        в начале сохранения устанавливаем поля из дто
        Company company = Company.builder()
                .title(companyDTO.getTitle())
                .telephone(changeNumberPhone(companyDTO.getTelephone()))
                .city(companyDTO.getCity())
                .urlChat(companyDTO.getUrlChat())
                .email(companyDTO.getEmail())
                .user(convertUserDTOToUser(companyDTO.getUser()))
                .operator(companyDTO.getOperator())
                .manager(convertManagerDTOToManager(companyDTO.getManager()))
                .workers(convertWorkerDTOToWorker(companyDTO.getWorker()))
                .status(convertCompanyStatusDTOToCompanyStatus(companyDTO.getStatus()))
                .categoryCompany(convertCategoryDTOToCategory(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCategoryDTOToSubCategory(companyDTO.getSubCategory()))
                .active(true)
                .commentsCompany(companyDTO.getCommentsCompany())
                .counterNoPay(0)
                .counterPay(0)
                .sumTotal(new BigDecimal(0))
                .build();

        //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
        Set<Filial> existingFilials = company.getFilial(); // пытаемся получить текущий список филиалов из компании
        if (existingFilials == null) {
            existingFilials = new HashSet<>();// если он пустой, то создаем новый set
        }
        Set<Filial> newFilials = convertFilialDTOToFilial(companyDTO.getFilial()); // берем список из дто
        existingFilials.addAll(newFilials); // объединяем эти списки
        company.setFilial(existingFilials); // устанавливаем компании объединенный список
        //        Проверка есть ли уже какие-то филиалы, если да, то добавляем, если нет то загружаем новый список
        log.info("4. Компания успешно создана");
        try {
            Company company1 = companyRepository.save(company); // сохраняем новую компанию в БД
            log.info("5. Компания успешно сохранена");
            for (Filial filial : company1.getFilial()) { // проходимся по всем филиалам и устанавливаем им компанию
                filial.setCompany(company1); // Установка компании в филиале
                filialService.save(filial); // Сохранение обновленного филиала
            }
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении компании: " + e.getMessage());
            return false;
        }
    } // Создание нового пользователя "Клиент" - конец
//      =====================================CREATE USERS - START=======================================================

    public List<Company> getAllCompaniesList(){ // Взять все компании
        return companyRepository.findAll();
    } // Взять все компании


// ======================================== JUST ADMIN ===============================================================
    public Page<CompanyListDTO> getAllCompaniesDTOListToList(String keywords, String status, int pageNumber, int pageSize){ // Показ всех компаний + поиск + статус
        List<Long> companyId;
        List<Company> companyPage;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyId = companyRepository.findAllIdByStatusAndKeyword(keywords, status, keywords, status);
            companyPage = companyRepository.findAll(companyId);
        }
        else {
            companyId = companyRepository.findAllIdByStatus(status);
            companyPage = companyRepository.findAll(companyId);
        }
        return getPage(companyPage,pageNumber,pageSize);
    } // Показ всех компаний + поиск + статус


    public Page<CompanyListDTO> getAllCompaniesDTOListToListToSend(String keywords, String status, int pageNumber, int pageSize){ // Показ всех компаний + поиск + статус + для рассылки
        List<Long> companyId;
        List<Company> companyPage;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyId = companyRepository.findAllIdByStatusAndKeyword(keywords, status, keywords, status);
            companyPage = companyRepository.findAll(companyId);
        }
        else {
            companyId = companyRepository.findAllIdByStatus(status);
            companyPage = companyRepository.findAll(companyId);
        }
        return getPageIsAfter(companyPage,pageNumber,pageSize);
    } // Показ всех компаний + поиск + статус + для рассылки



    public Page<CompanyListDTO> getAllCompaniesDTOList(String keywords, int pageNumber, int pageSize) {
        List<Long> companyId;
        List<Company> companyPage;
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            companyId = companyRepository.findAllToAdminWithFetchWithKeyWord(keywords,keywords);
            companyPage = companyRepository.findAll(companyId);
        }
        else {
            companyId = companyRepository.findAllIdToAdmin();
            companyPage = companyRepository.findAll(companyId);
        }
        return getPage(companyPage,pageNumber,pageSize);
    }


// ======================================== WITH MANAGER ===============================================================

    public Page<CompanyListDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, int pageNumber, int pageSize){ // Берем все заказы  Менеджера + поиск
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> companyId;
        List<Company> companyPage;
        if (!keyword.isEmpty()){
            companyId = companyRepository.findAllByManagerAndKeyWord(manager,keyword,  manager, keyword);
            companyPage = companyRepository.findAll(companyId);
        }
        else {
            companyId = companyRepository.findAllByManager(manager);
            companyPage = companyRepository.findAll(companyId);
        }
        return getPage(companyPage,pageNumber,pageSize);
    } // Берем все заказы  Менеджера + поиск


    public Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы Менеджера + поиск + статус
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> companyId;
        List<Company> companyPage;
        if (!keyword.isEmpty()){
                companyId = companyRepository.findAllByManagerAndStatusAndKeyWords(manager,keyword, status, manager, keyword, status);
                companyPage = companyRepository.findAll(companyId);
        }
        else {
            companyId = companyRepository.findAllByManagerAndStatus(manager, status);
            companyPage = companyRepository.findAll(companyId);
        }
        return getPage(companyPage,pageNumber,pageSize);
    } // Берем все заказы Менеджера + поиск + статус

    public Page<CompanyListDTO> getAllCompanyDTOAndKeywordByManagerToSend(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы Менеджера + поиск + статус
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> companyId;
        List<Company> companyPage;
        if (!keyword.isEmpty()){
            companyId = companyRepository.findAllByManagerAndStatusAndKeyWords(manager,keyword, status, manager, keyword, status);
            companyPage = companyRepository.findAll(companyId);
        }
        else{
            companyId = companyRepository.findAllByManagerAndStatus(manager, status);
            companyPage = companyRepository.findAll(companyId);
        }
        return getPageIsAfter(companyPage,pageNumber,pageSize);
    } // Берем все заказы Менеджера + поиск + статус


    private Page<CompanyListDTO> getPage(List<Company> companyPage, int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), companyPage.size());
        List<CompanyListDTO> companyListDTOs = companyPage.subList(start, end)
                .stream()
                .map(this::convertCompanyListDTO)
                .collect(Collectors.toList());
        return new PageImpl<>(companyListDTOs, pageable, companyPage.size());
    }
    private Page<CompanyListDTO> getPageIsAfter(List<Company> companyPage, int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), companyPage.size());
        List<CompanyListDTO> companyListDTOs = companyPage.subList(start, end)
                .stream()
                .map(this::convertCompanyListDTO)
                .filter(company -> LocalDate.now().isAfter(company.getDateNewTry()))
                .collect(Collectors.toList());
        return new PageImpl<>(companyListDTOs, pageable, companyPage.size());
    }


    @Override
    public List<CompanyDTO> getAllCompaniesDTOList(String keywords, String status) {
        return null;
    }


    public CompanyDTO getCompaniesDTOById(Long companyId){  // Берем компанию ДТО по Id
        return convertToDto(companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", companyId)
        )));
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



    public CompanyDTO convertToDtoToManager(Long leadId, Principal principal) { //    Метод подготовки ДТО при создании компании из Лида менеджером
        LeadDTO leadDTO = leadService.findById(leadId);
        List<WorkerDTO> workers = userService.findByUserName(principal.getName()).orElseThrow().getWorkers().stream().map(this::convertToWorkerDto).toList();
        var random = new SecureRandom();
    //        находим лида по переданному id

        //        Устанавливаем поля из лида в новый дто
        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone(leadDTO.getTelephoneLead());
        companyDTO.setCity(leadDTO.getCityLead());
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(leadDTO.getOperator().getUser().getFio());
        companyDTO.setManager(convertToManagerDto(leadDTO.getManager()));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        companyDTO.setFilial(new FilialDTO());
        companyDTO.setWorker(workers.get(random.nextInt(workers.size())));
        return companyDTO;
    } //    Метод подготовки ДТО при создании компании из Лида менеджером

    public CompanyDTO convertToDtoToManagerNotLead(Principal principal) { //    Метод подготовки ДТО при создании компании из Лида менеджером
        List<WorkerDTO> workers = userService.findByUserName(principal.getName()).orElseThrow().getWorkers().stream().map(this::convertToWorkerDto).toList();
        var random = new SecureRandom();
        //        находим лида по переданному id

        //        Устанавливаем поля из лида в новый дто
        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone("");
        companyDTO.setCity("");
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(null);
        companyDTO.setManager(convertToManagerDto(managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId())));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        companyDTO.setFilial(new FilialDTO());
        companyDTO.setWorker(workers.get(random.nextInt(workers.size())));
        return companyDTO;
    } //    Метод подготовки ДТО при создании компании из Лида менеджером

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
            companyDTO.setFilials(convertToFilialDtoSet(company.getFilial()));
            companyDTO.setOrders(convertToOrderDTOSet(company.getOrderList()));
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
        if (company.getId() != null) {
            CompanyListDTO companyListDTO = new CompanyListDTO();
            companyListDTO.setId(company.getId());
            companyListDTO.setTitle(company.getTitle());
            companyListDTO.setTelephone(company.getTelephone());
            companyListDTO.setUrlChat(company.getUrlChat());
            companyListDTO.setCountFilials(company.getFilial().size());
            companyListDTO.setUrlFilial(company.getFilial().iterator().next().getUrl() != null ? company.getFilial().iterator().next().getUrl() : String.valueOf(new Filial(1, "нет филиала", "пусто")));
            companyListDTO.setStatus(company.getStatus().getTitle());
            companyListDTO.setManager(company.getManager().getUser().getFio());
            companyListDTO.setCommentsCompany(company.getCommentsCompany());
            companyListDTO.setDateNewTry(company.getDateNewTry());
            return companyListDTO;
        }
        else {
            log.info("отработал нулл");
            return new CompanyListDTO();
        }
    } // перевод компании в ДТО

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
                .telephone(company.getTelephone())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .categoryCompany(convertToCategoryDto(company.getCategoryCompany()))
                .subCategory(convertToSubCategoryDto(company.getSubCategory()))
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
                .build();
    } // перевод филиала в ДТО

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){ // перевод работника в ДТО Лист
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    } // перевод работника в ДТО Лист
    private WorkerDTO convertToWorkerDTO(Worker worker){ // перевод работника в ДТО
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    } // перевод работника в ДТО

    private ManagerDTO convertToManagerDTO(Manager manager){ // перевод менеджера в ДТО
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
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
        userDTO.setWorkers(user.getWorkers());
        return userDTO;
    } // перевод юзера в ДТО

    private UserDTO convertToUserDto(User user) { // перевод юзера в ДТО
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setEmail(user.getEmail());
        userDTO.setFio(user.getFio());
        userDTO.setWorkers(user.getWorkers());
        return userDTO;
    } // перевод юзера в ДТО

    private ManagerDTO convertToManagerDto(Manager manager) { // перевод менеджера в ДТО
        ManagerDTO managerDTO = new ManagerDTO();
        managerDTO.setManagerId(manager.getId());
        managerDTO.setUser(manager.getUser());
        return managerDTO;
    } // перевод менеджера в ДТО

    private Set<WorkerDTO> convertToWorkerDtoSet(Set<Worker> workers) { // перевод менеджера в ДТО Сэт
        return workers.stream()
                .map(this::convertToWorkerDto)
                .collect(Collectors.toSet());
    } // перевод менеджера в ДТО Сэт

    private WorkerDTO convertToWorkerDto(Worker worker) { // перевод работника в ДТО
        WorkerDTO workerDTO = new WorkerDTO();
        workerDTO.setWorkerId(worker.getId());
        workerDTO.setUser(worker.getUser());
        return workerDTO;
    } // перевод работника в ДТО

    private CompanyStatusDTO convertToCompanyStatusDto(CompanyStatus status) { // перевод статуса компании в ДТО
        CompanyStatusDTO statusDTO = new CompanyStatusDTO();
        statusDTO.setId(status.getId());
        statusDTO.setTitle(status.getTitle());
        return statusDTO;
    } // перевод статуса компании в ДТО

    private CategoryDTO convertToCategoryDto(Category category) { // перевод категории в ДТО
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    } // перевод категории в ДТО

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) { // перевод подкатегории в ДТО
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        return subCategoryDTO;
    } // перевод подкатегории в ДТО

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
        // Other fields if needed
        return filialDTO;
    }

//    ======================================== COMPANY UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateCompany(CompanyDTO companyDTO, WorkerDTO newWorkerDTO, Long companyId) { // Обновление компании
        log.info("2. Вошли в обновление данных компании");
        Company saveCompany = companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", companyId)));
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("title: " + !Objects.equals(companyDTO.getTitle(), saveCompany.getTitle()));
        System.out.println("telephone: " + !Objects.equals(changeNumberPhone(companyDTO.getTelephone()), changeNumberPhone(saveCompany.getTelephone())));
        System.out.println("city: " + !Objects.equals(companyDTO.getCity(), saveCompany.getCity()));
        System.out.println("urlChat: " + !Objects.equals(companyDTO.getUrlChat(), saveCompany.getUrlChat()));
        System.out.println("email: " + !Objects.equals(companyDTO.getEmail(), saveCompany.getEmail()));
        System.out.println("active: " + !Objects.equals(companyDTO.isActive(), saveCompany.isActive()));
        System.out.println("comments: " +  !Objects.equals(companyDTO.getCommentsCompany(), saveCompany.getCommentsCompany()));
        System.out.println("status: " + !Objects.equals(companyDTO.getStatus().getId(), saveCompany.getStatus().getId()));
        System.out.println("category: " + !Objects.equals(companyDTO.getCategoryCompany().getId(), saveCompany.getCategoryCompany().getId()));
        System.out.println("subCategory: " + !Objects.equals(companyDTO.getSubCategory().getId(), saveCompany.getSubCategory().getId()));
        System.out.println("manager: " + !Objects.equals(companyDTO.getManager().getManagerId(), saveCompany.getManager().getId()));
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
        if (!Objects.equals(companyDTO.getEmail(), saveCompany.getEmail())){ /*Проверка смены мейл*/
            log.info("Обновляем мейл");
            saveCompany.setEmail(companyDTO.getEmail());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.isActive(), saveCompany.isActive())){ /*Проверка активности*/
            log.info("Обновляем активность");
            saveCompany.setActive(companyDTO.isActive());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getCommentsCompany(), saveCompany.getCommentsCompany())){ /*Проверка комментарий*/
            log.info("Обновляем комментарий");
            saveCompany.setCommentsCompany(companyDTO.getCommentsCompany());
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getStatus().getId(), saveCompany.getStatus().getId())){ /*Проверка статус компании*/
            log.info("Обновляем статус компании");
            saveCompany.setStatus(companyStatusService.getCompanyStatusById(companyDTO.getStatus().getId()));
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getCategoryCompany().getId(), saveCompany.getCategoryCompany().getId())){ /*Проверка категорию*/
            log.info("Обновляем категорию");
            saveCompany.setCategoryCompany(categoryService.getCategoryByIdCategory(companyDTO.getCategoryCompany().getId()));
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getSubCategory().getId(), saveCompany.getSubCategory().getId())){ /*Проверка субкатегорию*/
            log.info("Обновляем субкатегорию");
            saveCompany.setSubCategory(subCategoryService.getSubCategoryById(companyDTO.getSubCategory().getId()));
            isChanged = true;
        }
        if (!Objects.equals(companyDTO.getManager().getManagerId(), saveCompany.getManager().getId())){ /*Проверка менеджера*/
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
        if  (isChanged){
            log.info("3. Начали сохранять обновленную компанию в БД");
            companyRepository.save(saveCompany);
            log.info("4. Сохранили обновленную компанию в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Обновление компании

//    =====================================================================================================

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
        workers.add(workerService.getWorkerById(workerDTO.getWorkerId()));
        return workers;
    } // перевод работника из Дто в сущность

    private CompanyStatus convertCompanyStatusDTOToCompanyStatus(CompanyStatusDTO companyStatusDTO){ // перевод статуса компании из Дто в сущность
        return companyStatusService.getCompanyStatusById(companyStatusDTO.getId());
    } // перевод статуса компании из Дто в сущность

    private Category convertCategoryDTOToCategory(CategoryDTO categoryDTO){ // перевод категории из Дто в сущность
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // перевод категории из Дто в сущность

    private SubCategory convertSubCategoryDTOToSubCategory(SubCategoryDTO subcategoryDTO){ // перевод подкатегории из Дто в сущность
        return subCategoryService.getCategoryByIdSubCategory(subcategoryDTO.getId());
    } // перевод подкатегории из Дто в сущность

    private Set<Filial> convertFilialDTOToFilial(FilialDTO filialDTO) { // перевод филиала из Дто в сущность
        Filial existingFilial = filialService.findFilialByTitleAndUrl(filialDTO.getTitle(), filialDTO.getUrl());
        if (existingFilial != null) {
            return Collections.singleton(existingFilial);
        } else {
            Filial newFilial = filialService.save(filialDTO);
            return Collections.singleton(newFilial);
        }
    } // перевод филиала из Дто в сущность


    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
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
            System.out.println(e);
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
