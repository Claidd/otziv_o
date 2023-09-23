package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
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


    public List<Company> getAllCompaniesList(){
        return companyRepository.findAll();
    }

    @Override
    public List<CompanyDTO> getAllCompaniesDTOList() {
        return companyRepository.findAll().stream().map(this::convertToDto).sorted(Comparator.comparing(CompanyDTO::getCreateDate).reversed()).toList();
    }

    public List<CompanyDTO> getAllCompaniesDTOList(String keywords){ // Показ всех компаний + поиск
        if (!keywords.isEmpty()){
            log.info("Отработал метод с keywords");
            return convertToCompanyDTOList(companyRepository.findALLByTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(keywords, keywords).stream().sorted(Comparator.comparing(Company::getCreateDate).reversed()).collect(Collectors.toList()));
        }
        else return convertToCompanyDTOList(companyRepository.findAll().stream().sorted(Comparator.comparing(Company::getCreateDate).reversed()).collect(Collectors.toList()));
    }

    public List<CompanyDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword){ // Берем все заказы с поиском для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        if (!keyword.isEmpty()){
            return convertToCompanyDTOList(companyRepository.findAllByManagerAndTitleContainingIgnoreCaseOrManagerAndTelephoneContainingIgnoreCase(manager,keyword, manager, keyword));
        }
        else return convertToCompanyDTOList(companyRepository.findAllByManager(manager));
    } // Берем все заказы с поиском для Менеджера


    public CompanyDTO getCompaniesDTOById(Long id){
        return convertToDto(companyRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", id)
        )));
    }

    @Override
    public Company getCompaniesById(Long id) {
        return companyRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Компания '%d' не найден", id)
        ));
    }

    @Override
    public CompanyDTO getCompaniesAllStatusByIdAndKeyword(Long companyId, String keywords) {
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
    }// берем компанию с поиском для вывода всех ее заказов

    @Transactional
    public void save(Company company){
        companyRepository.save(company);
    }

    //    Метод подготовки ДТО при создании компании из Лида менеджером

    //      =====================================CREATE USERS - START=======================================================
    // Создание нового пользователя "Клиент" - начало
    @Transactional
    public boolean save(CompanyDTO companyDTO){
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");
    //        в начале сохранения устанавливаем поля из дто
        Company company = Company.builder()
                .title(companyDTO.getTitle())
                .telephone(companyDTO.getTelephone())
                .city(companyDTO.getCity())
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
    }
    // Создание нового пользователя "Клиент" - конец
//      =====================================CREATE USERS - START=======================================================

//    Метод подготовки ДТО при создании компании из Лида менеджером
    public CompanyDTO convertToDtoToManager(Long leadId, Principal principal) {
    //        находим лида по переданному id
        LeadDTO leadDTO = leadService.findById(leadId);
        //        Устанавливаем поля из лида в новый дто
        CompanyDTO companyDTO = new CompanyDTO();
        companyDTO.setTelephone(leadDTO.getTelephoneLead());
        companyDTO.setCity(leadDTO.getCityLead());
        companyDTO.setUser(convertToUserDtoToManager(principal));
        companyDTO.setOperator(leadDTO.getOperator().getUser().getFio());
        companyDTO.setManager(convertToManagerDto(leadDTO.getManager()));
        companyDTO.setStatus(convertToCompanyStatusDto(companyStatusService.getCompanyStatusById(1L)));
        companyDTO.setFilial(new FilialDTO());
        return companyDTO;
    }
    //    Метод подготовки ДТО при создании компании из Лида менеджером
    private Set<CompanyDTO> convertToCompanyDTOSet(Set<Company> companies){
        return companies.stream().map(this::convertToDto).collect(Collectors.toSet());
    }
    private List<CompanyDTO> convertToCompanyDTOList(List<Company> companies){
        return companies.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    public CompanyDTO convertToDto(Company company) {
        if (company.getId() != null) {
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
            companyDTO.setFilials(convertToFilialDtoSet(company.getFilial()));
            companyDTO.setOrders(convertToOrderDTOSet(company.getOrderList()));
            System.out.println(companyDTO);
            return companyDTO;
        }
        else {
            log.info("отработал нулл");
            return new CompanyDTO();
        }
    }

    private Set<OrderDTO> convertToOrderDTOSet(Set<Order> orders){
        return orders.stream().map(this::convertToOrderDTO).collect(Collectors.toSet());
    }

    private OrderDTO convertToOrderDTO(Order order){
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
//                .manager(convertToManagerDTO(order.getManager()))
//                .worker(convertToWorkerDTO(order.getWorker()))
//                .details(convertToDetailsDTOList(order.getDetails()))
                .company(convertToCompanyDTO(order.getCompany()))
                .dayToChangeStatusAgo(period.getDays())
//                .complete(order.isComplete())
//                .counter(order.getCounter())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product){
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }

    private OrderStatusDTO convertToOrderStatusDTO(OrderStatus orderStatus){
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    }

    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details){
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    }
    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
//                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
//                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company){
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .build();
    }

    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    }
    private FilialDTO convertToFilialDTO(Filial filial){
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    }
    private WorkerDTO convertToWorkerDTO(Worker worker){
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private ManagerDTO convertToManagerDTO(Manager manager){
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .build();
    }

    private UserDTO convertToUserDtoToManager(Principal principal) {
        UserDTO userDTO = new UserDTO();
        User user = userService.findByUserName(principal.getName()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", principal.getName())
        ));
        userDTO.setUsername(user.getUsername());
        userDTO.setFio(user.getFio());
        userDTO.setWorkers(user.getWorkers());
        // Other fields if needed
        return userDTO;
    }
    private UserDTO convertToUserDto(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setEmail(user.getEmail());
        userDTO.setFio(user.getFio());
        userDTO.setWorkers(user.getWorkers());

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
        workerDTO.setUser(worker.getUser());
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
        filialDTO.setUrl(filial.getUrl());
        // Other fields if needed
        return filialDTO;
    }

//    ======================================== COMPANY UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateCompany(CompanyDTO companyDTO, WorkerDTO newWorkerDTO, Long companyId) {
        log.info("2. Вошли в обновление данных компании");
        Company saveCompany = companyRepository.findById(companyId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", companyId)));
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("title: " + !Objects.equals(companyDTO.getTitle(), saveCompany.getTitle()));
        System.out.println("telephone: " + !Objects.equals(changeNumberPhone(companyDTO.getTelephone()), changeNumberPhone(saveCompany.getTelephone())));
        System.out.println("city: " + !Objects.equals(companyDTO.getCity(), saveCompany.getCity()));
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
    }

//    =====================================================================================================

public boolean deleteWorkers(Long companyId, Long workerId){
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
}
@Transactional
    public boolean deleteFilial(Long companyId, Long filialId){
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
    }

//    =====================================================================================================
    private User convertUserDTOToUser(UserDTO userDTO){
        return userService.findByUserName(userDTO.getUsername()).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", userDTO.getUsername())
        ));
    }

    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){
        return managerService.getManagerById(managerDTO.getManagerId());
    }

    private Set<Worker> convertWorkerDTOToWorkersSetToSet(Set<WorkerDTO> workerDTO){
        return workerDTO.stream()
                .map(workerDTO1 -> {
                    return workerService.getWorkerById(workerDTO1.getWorkerId());
                }).collect(Collectors.toSet());
    }

    private Set<Worker> convertWorkerDTOToWorker(WorkerDTO workerDTO){
        Set<Worker> workers = new HashSet<>();
        workers.add(workerService.getWorkerById(workerDTO.getWorkerId()));
        return workers;
    }

    private CompanyStatus convertCompanyStatusDTOToCompanyStatus(CompanyStatusDTO companyStatusDTO){
        return companyStatusService.getCompanyStatusById(companyStatusDTO.getId());
    }

    private Category convertCategoryDTOToCategory(CategoryDTO categoryDTO){
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    }

    private SubCategory convertSubCategoryDTOToSubCategory(SubCategoryDTO subcategoryDTO){
        return subCategoryService.getCategoryByIdSubCategory(subcategoryDTO.getId());
    }

    private Set<Filial> convertFilialDTOToFilial(FilialDTO filialDTO) {
        Filial existingFilial = filialService.findFilialByTitleAndUrl(filialDTO.getTitle(), filialDTO.getUrl());
        if (existingFilial != null) {
            return Collections.singleton(existingFilial);
        } else {
            Filial newFilial = filialService.save(filialDTO);
            return Collections.singleton(newFilial);
        }
    }

    // Вспомогательный метод для корректировки номера телефона
    private String changeNumberPhone(String phone){
        if (phone.contains("9")) {
            String[] a = phone.split("9");
            a[0] = "+79";
            String b = a[0] + a[1];
//            System.out.println(b);
            return b;
        } else {
            // Обработка случая, когда "9" не найдено в строке
            return phone; // или верните значение по умолчанию, которое вам нужно
        }
    }

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
    }

    public void changeDataTry(Long companyId){
        Company company = companyRepository.findById(companyId).orElse(null);
        assert company != null;
        company.setDateNewTry(company.getDateNewTry().plusDays(100));
        companyRepository.save(company);
    }
}
