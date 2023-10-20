package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.webjars.NotFoundException;

import java.math.BigDecimal;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CompanyService companyService;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final OrderDetailsService orderDetailsService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final BotService botService;
    private final FilialService filialService;
    private final ReviewService reviewService;
    private final OrderStatusService orderStatusService;
    private final ReviewArchiveService reviewArchiveService;
    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final UserService userService;
    private final CompanyStatusService companyStatusService;


    //    ======================================== ВЗЯТЬ ЗАКАЗЫ ПО РОЛЯМ =============================================================
    public List<OrderDTO> getAllOrderDTO(){
        return convertToOrderDTOList(orderRepository.findAll());
    }
    public List<OrderDTO> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
//        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> companyId;
        List<Company> companyPage;
        List<CompanyListDTO> companyListDTOs = null;
        if (!keyword.isEmpty()){
            return convertToOrderDTOList(orderRepository.findAllByCompanyTitleContainingIgnoreCaseOrCompanyTelephoneContainingIgnoreCase(keyword, keyword));
        }
        else{
            return convertToOrderDTOList(orderRepository.findAll());
        }

    }  // Берем все заказы с поиском по названию компании или номеру
    public List<OrderDTO> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword){ // Берем все заказы с поиском для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        if (!keyword.isEmpty()){
            return convertToOrderDTOList(orderRepository.findAllByManagerAndCompanyTitleContainingIgnoreCaseOrManagerAndCompanyTelephoneContainingIgnoreCase(manager,keyword, manager, keyword));
        }
        else return convertToOrderDTOList(orderRepository.findAllByManager(manager));
    } // Берем все заказы с поиском для Менеджера
    public List<OrderDTO> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword){ // Берем все заказы с поиском для Менеджера
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        if (!keyword.isEmpty()){
            return convertToOrderDTOList(orderRepository.findAllByWorkerAndCompanyTitleContainingIgnoreCaseOrWorkerAndCompanyTelephoneContainingIgnoreCase(worker,keyword, worker, keyword));
        }
        else return convertToOrderDTOList(orderRepository.findAllByWorker(worker));
    } // Берем все заказы с поиском для Работника
    public Order getOrder(Long orderId){ // Взять заказ
        return orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Заказ № '%d' не найден", orderId)));
    } // Взять заказ
    public OrderDTO getOrderDTO(Long orderId){ // Взять заказ DTO
        return  toDTO(orderRepository.findById(orderId).orElseThrow());
    } // Взять заказ DTO


//    private Page<CompanyListDTO> getPage(List<Company> companyPage, int pageNumber, int pageSize) {
//        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
//        int start = (int)pageable.getOffset();
//        int end = Math.min((start + pageable.getPageSize()), companyPage.size());
//        List<CompanyListDTO> companyListDTOs = companyPage.subList(start, end)
//                .stream()
//                .map(this::convertCompanyListDTO)
//                .collect(Collectors.toList());
//        return new PageImpl<>(companyListDTOs, pageable, companyPage.size());
//    }

    //    ======================================== ВЗЯТЬ ЗАКАЗЫ ПО РОЛЯМ =============================================================





    //    ======================================== СОЗДАНИЕ НОВЫХ ОТЗЫВОВ =========================================================
    @Override
    public OrderDTO newOrderDTO(Long id) { // Создание DTO заготовки для создания нового Отзыва
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(id); // берем компанию по id с переводом ее в дто нового заказа
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setCompany(companyDTO); // устанавливаем заказу компанию
        orderDTO.setWorkers(companyDTO.getWorkers()); // список работников в этой компании
        orderDTO.setManager(companyDTO.getManager());
        orderDTO.setStatus(orderStatusService.getOrderStatusDTOByTitle("Новый"));
        return orderDTO;
    } // Создание DTO заготовки для создания нового Отзыва
    private Review createNewReview(Company company, OrderDetails orderDetails, Order order){ // Создание нового отзыва
        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(order.getWorker().getId());
        var random = new SecureRandom();
        return Review.builder()
                .category(company.getCategoryCompany())
                .subCategory(company.getSubCategory())
                .text("Текст отзыва")
                .answer(" ")
                .orderDetails(orderDetails)
                .bot(bots.get(random.nextInt(bots.size())))
                .filial(order.getFilial())
                .publish(false)
                .worker(order.getWorker())
                .build();
    } // Создание нового отзыва
    @Transactional
    public boolean addNewReview(Long orderId){  // Добавление нового отзыва
        try {
            log.info("1. Зашли в добавление нового отзыва");
            Order saveOrder = orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));
            OrderDetails orderDetails = saveOrder.getDetails().get(0);
            Company saveCompany = saveOrder.getCompany();
            Review review = createNewReview(saveCompany, orderDetails, saveOrder);
            log.info("2. Создали новый отзыв");
            List<Review> newList = orderDetails.getReviews();
            newList.add(review);
            orderDetails.setReviews(newList);
            orderDetails.setAmount(orderDetails.getAmount() + 1);
            orderDetails.setPrice(orderDetails.getPrice().add(orderDetails.getProduct().getPrice()));
            orderDetailsService.save(orderDetails);
            log.info("3. Сохранили его в детали");
            saveOrder.setAmount(saveOrder.getAmount() + 1);
            saveOrder.setSum(saveOrder.getSum().add(saveOrder.getDetails().get(0).getProduct().getPrice()));
            Order saveOrder2 = orderRepository.save(saveOrder);
            log.info("4. Обновили счетчик в заказе");

            saveCompany.setCounterNoPay(saveCompany.getCounterNoPay() + (saveOrder2.getAmount() - saveCompany.getCounterNoPay()));
            companyService.save(saveCompany);
            return true;
        }
        catch (Exception e){
            log.info("2. Что-то пошло не так в создании нового отзыва");
            return false;
        }
    } // Добавление нового отзыва
    @Transactional
    public boolean deleteNewReview(Long orderId, Long reviewId){ // Удаление отзыва
        try {
            Order saveOrder = orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));
            OrderDetails orderDetails = saveOrder.getDetails().get(0);
            Company saveCompany = saveOrder.getCompany();
            log.info("2. Удалили отзыв");
            List<Review> newList = orderDetails.getReviews();
            Review review = reviewService.getReviewById(reviewId);
            newList.remove(review);
            orderDetails.setReviews(newList);
            orderDetails.setAmount(orderDetails.getAmount() - 1);
            orderDetails.setPrice(orderDetails.getPrice().subtract(orderDetails.getProduct().getPrice()));
            orderDetailsService.save(orderDetails);
            log.info("3. Сохранили его в детали");
            saveOrder.setAmount(saveOrder.getAmount() - 1);
            saveOrder.setSum(saveOrder.getSum().subtract(saveOrder.getDetails().get(0).getProduct().getPrice()));
            Order saveOrder2 = orderRepository.save(saveOrder);
            reviewService.deleteReview(reviewId);
            log.info("4. Обновили счетчик в заказе");
            saveCompany.setCounterNoPay(saveCompany.getCounterNoPay() + (saveOrder2.getAmount() - saveCompany.getCounterNoPay()));
            companyService.save(saveCompany);
            return true;
        }
        catch (Exception e){
            log.info("2. Что-то пошло не так в создании нового отзыва");
            return false;
        }
    } // Удаление отзыва
    @Override
    @Transactional
    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) { // сохранение нового ORDER, ORDER_DETAIL и списка REVIEWS
        Order order = toEntityOrderFromDTO(orderDTO, productId);
        Order saveOrder = orderRepository.save(order);
        log.info("1. Сохранили ORDER");
        OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, saveOrder,productId);
        OrderDetails orderDetailsSave = orderDetailsService.save(orderDetails);
        log.info("2. Сохранили ORDER-DETAIL");
        List<Review> reviews = toEntityListReviewsFromDTO(orderDTO, orderDetailsSave);
        log.info("3. Сохранили REVIEWS");
        orderDetailsSave.setReviews(reviews);
        log.info("4. Установили REVIEWS в ORDER-DETAIL");
        OrderDetails orderDetails2 = orderDetailsService.save(orderDetailsSave);
        log.info("5. Сохранили  ORDER-DETAIL с REVIEWS");
        List<OrderDetails> detailsList;
        if (saveOrder.getDetails() != null){
            detailsList = saveOrder.getDetails();
            log.info("6. Взяли ORDER-DETAIL лист");
        }
        else {
            detailsList = new ArrayList<>();
            log.info("6. Создали пустой ORDER-DETAIL лист");
        }
        detailsList.add(orderDetails2);
        log.info("7. Добавили ORDER-DETAIL лист ");
        saveOrder.setDetails(detailsList);
        log.info("8. Установили его в ORDER");
        Order saveOrder2 = orderRepository.save(saveOrder);
        log.info("9. Сохранили ORDER с ORDER-DETAIL в БД");
        System.out.println(saveOrder2);
        log.info("10. Обновляем счетчик компании в БД");
        Company  company = companyService.getCompaniesById(companyId);
        company.setCounterNoPay(company.getCounterNoPay() + (saveOrder2.getAmount() - company.getCounterNoPay()));
        company.setStatus(companyStatusService.getStatusByTitle("В работе"));
        companyService.save(company);
        return true;
    } // сохранение нового ORDER, ORDER_DETAIL и списка REVIEWS

//    ======================================== СОЗДАНИЕ НОВЫХ ОТЗЫВОВ =========================================================




    //    ======================================== ЗАКАЗ UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) { // Метод Обновления Заказа
        log.info("2. Вошли в обновление данных Заказа");
        Order saveOrder = orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));
        log.info("Достали Заказ");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("filial id: " + !Objects.equals(orderDTO.getFilial().getId(), saveOrder.getFilial().getId()));
        System.out.println("filial url: " + !Objects.equals(orderDTO.getFilial().getUrl(), saveOrder.getFilial().getUrl()));
        System.out.println("worker: " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId()));
        System.out.println("manager: " + !Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getWorker().getId()));
        System.out.println("complete: " + !Objects.equals(orderDTO.isComplete(), saveOrder.isComplete()));


        if (!Objects.equals(orderDTO.getFilial().getTitle(), saveOrder.getFilial().getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем филиал");
            saveOrder.setFilial(convertFilialDTOToFilial(orderDTO.getFilial()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getFilial().getUrl(), saveOrder.getFilial().getUrl())){ /*Проверка смены названия*/
            log.info("Обновляем url филиала");
            saveOrder.getFilial().setUrl(orderDTO.getFilial().getUrl());
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId())){ /*Проверка смены работника*/
            log.info("Обновляем работника");
            saveOrder.setWorker(convertWorkerDTOToWorker(orderDTO.getWorker()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getWorker().getId())){ /*Проверка смены работника*/
            log.info("Обновляем менеджера");
            saveOrder.setManager(convertManagerDTOToManager(orderDTO.getManager()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())){ /*Проверка статус заказа*/
            log.info("Обновляем выполнение Заказа");
            saveOrder.setComplete(orderDTO.isComplete());
            isChanged = true;
        }


        if  (isChanged){
            log.info("3. Начали сохранять обновленный Заказ в БД");
            orderRepository.save(saveOrder);
            log.info("4. Сохранили обновленный Заказ в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Метод Обновления Заказа
    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title){ // смена статуса для заказа с проверкой на Оплачено
        try {

            if (title.equals("Оплачено")){
                log.info("1. Вошли в смену статуса в оплачено");
                Order order = orderRepository.findById(orderID).orElseThrow(() -> new NotFoundException("Order  not found for orderID: " + orderID));
                System.out.println("orderIsComplete: " + !order.isComplete());
                System.out.println("order.getAmount() <= order.getCounter(): " + Objects.equals(order.getAmount(), order.getCounter()));

                if (!order.isComplete() && Objects.equals(order.getAmount(), order.getCounter())){
                    log.info("2. Проверили, что заказ еще не бьл выполнен");
                    if (zpService.save(order)){
                        log.info("3. Сохранили ЗП");
                        boolean chek = paymentCheckService.save(order);
                        System.out.println(chek);
                        log.info("4. Сохранили Чек компании");
                        Company company = companyService.getCompaniesById(order.getCompany().getId());

                        try {
                            company.setCounterPay(company.getCounterPay() + order.getAmount());
                            System.out.println("счетчик: " + company.getCounterPay() + order.getAmount());
                            company.setSumTotal(company.getSumTotal().add(order.getSum()));
                            System.out.println("сумма: " + company.getSumTotal().add(order.getSum()));
                            log.info("5. Успешно установили суммы");
                            order.setComplete(true);
                            order.setPayDay(LocalDate.now());
                            System.out.println("PayDay: " + order.getPayDay());
                            orderRepository.save(order);
                            log.info("6. Заказ обновлен и сохранен");
                            companyService.save(checkStatusToCompany(company));
                            log.info("7. Компания сохранена");
                            log.info("8. Оплата поступила, ЗП начислена Менеджеру и Работнику");
                        }
                        catch (Exception e){
                            log.info("4. НЕ Успешно установили суммы");
                        }

                    }
                    else {
                        log.info("2. Оплата поступила, но при сохранении какие-то проблемы");
                    }
                }
                else {
                    log.info("3. Что-то пошло не так и выбросило в момент Зачисления");
                }
                log.info("2. Проверили, что заказ УЖЕ был выполнен и просто меняем статус");
                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                orderRepository.save(order);
                return true;
            }
            else {
                Order order = orderRepository.findById(orderID).orElseThrow(() -> new NotFoundException("Order  not found for orderID: " + orderID));
                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                orderRepository.save(order);
                return true;
            }
        } catch (Exception e){
            log.info("При смене статуса произошли какие-то проблемы");
            return false;
        }
    } // смена статуса для заказа с проверкой на Оплачено

    @Transactional
    public Company checkStatusToCompany(Company company){
        int result = 0;
        for (Order order1 : company.getOrderList()) {
            if (!order1.isComplete()) {
                result = 1;
                break;
            }
        }
        if (result == 0){
            company.setStatus(companyStatusService.getStatusByTitle("Новый заказ"));
        }
        return company;
    }

    @Override
    @Transactional
    public boolean changeStatusAndOrderCounter(Long reviewId) { // смена статуса отзыва, увеличение счетчика и смена статуса заказа если выполнен
        Review review = reviewService.getReviewById(reviewId);
        log.info("2. Достали отзыв по id" + reviewId);
        if (review != null){
            Order order = orderRepository.findById(review.getOrderDetails().getOrder().getId()).orElse(null);
            if(order != null && !review.isPublish()){
                log.info("3. Прошли проверку order != null && !review.isPublish()");
                    reviewArchiveService.saveNewReviewArchive(reviewId);
                log.info("4. Сохранили отзыв в архив");
                    order.setCounter(order.getCounter() + 1);
                    Order saveOrder = orderRepository.save(order);
                log.info("5. Увеличили и обновили счетчик публикаций в заказе");
                checkOrderCounterAndAmount(saveOrder);
            }
            else {
                log.info("3. Счетчик не увеличен, проверка не пройдена");
                System.out.println("order: " + (order != null));
                System.out.println("publish status: " + (!review.isPublish()));
            }
            log.info("6. Увеличиваем счетчик публикаций бота и сохраняем его");
            changeBotCounterAndStatus(review.getBot());
            review.setPublish(true);
            log.info("7. Установили Publish на тру - опубликовано");
            reviewService.save(review);
            log.info("8. Сохранили отзыв в БД");
            return true;
        }
        else {
            log.info("2. Что-то пошло не так и метод changeStatusAndOrderCounter не отработал правильно ");
            return false;
        }
    } // смена статуса отзыва, увеличение счетчика и смена статуса заказа если выполнен


    private void changeBotCounterAndStatus(Bot bot){ // обновление счетчика и статуса у бота
        bot.setCounter(bot.getCounter() + 1);
        if (bot.getCounter() >= 10){
            log.info("6. меняем статус бота от 10 отзывов");
            bot.setStatus(botService.changeStatus("Средний"));
        }
        if (bot.getCounter() >= 20){
            log.info("6. меняем статус бота от 20 отзывов");
            bot.setStatus(botService.changeStatus("Высокий"));
        }
        botService.save(bot);
    } // обновление счетчика и статуса у бота

    private void checkOrderCounterAndAmount(Order order){ // проверка счетчиков заказа
        if (order.getAmount() <= order.getCounter()){
            changeStatusForOrder(order.getId(), "Опубликовано");
            log.info("4. Счетчик совпадает с количеством заказа. Статус заказа сменен на Опубликовано");
        }
        else {
            log.info("4. Счетчик НЕ совпадает с количеством заказа. Статус заказа НЕ сменен на Опубликовано");
        }
    } // проверка счетчиков заказа

    //    ======================================== ЗАКАЗ UPDATE =========================================================









    //    ================================================== CONVERTER =====================================================

    private List<OrderDTO> convertToOrderDTOList(List<Order> orders){ // Конвертер DTO для списка заказов
        return orders.stream().map(this::toDTO).collect(Collectors.toList());
    } // Конвертер DTO для списка заказов
    private OrderDTO toDTO (Order order){// Конвертер DTO для заказа
        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged();
        // Вычисляем разницу между датами
        Period period = Period.between(changedDate, now);
        // Преобразуем период в дни
        int daysDifference = period.getDays();
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .sum(order.getSum())
                .created(order.getCreated())
                .changed(order.getChanged())
                .status(convertToOrderDTO(order.getStatus()))
                .company(convertToCompanyDTO(order.getCompany()))
                .filial(convertToFilialDTO(order.getFilial()))
                .manager(convertToManagerDTO(order.getManager()))
                .worker(convertToWorkerDTO(order.getWorker()))
                .details(convertToDetailsDTOList(order.getDetails()))
                .complete(order.isComplete())
                .counter(order.getCounter())
                .dayToChangeStatusAgo(period.getDays())
                .orderDetailsId(order.getDetails().iterator().next().getId())
                .build();
    } // Конвертер DTO для заказа
    private CompanyDTO convertToCompanyDTO(Company company){ // Конвертер DTO для компании
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .urlChat(company.getUrlChat())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .categoryCompany(convertToCategoryDto(company.getCategoryCompany()))
                .subCategory(convertToSubCategoryDto(company.getSubCategory()))
                .build();
    } // Конвертер DTO для компании

    private CategoryDTO convertToCategoryDto(Category category) {// Конвертер DTO для категории
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        // Other fields if needed
        return categoryDTO;
    } // Конвертер DTO для категории
    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) { // Конвертер DTO для субкатегории
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        // Other fields if needed
        return subCategoryDTO;
    } // Конвертер DTO для субкатегории
    private ManagerDTO convertToManagerDTO(Manager manager){// Конвертер DTO для менеджера
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .build();
    } // Конвертер DTO для менеджера
    private OrderStatusDTO convertToOrderDTO(OrderStatus orderStatus){// Конвертер DTO для статуса заказа
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    } // Конвертер DTO для статуса заказа
    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){// Конвертер DTO для списка работников
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка работников
    private WorkerDTO convertToWorkerDTO(Worker worker){// Конвертер DTO для работника
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    } // Конвертер DTO для работника
    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){ // Конвертер DTO для списка филиалов
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка филиалов
    private FilialDTO convertToFilialDTO(Filial filial){// Конвертер DTO для филиала
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    } // Конвертер DTO для филиала
    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details){// Конвертер DTO для списка деталей
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    } // Конвертер DTO для списка деталей
    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){ // Конвертер DTO для деталей
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    } // Конвертер DTO для деталей
    private ProductDTO convertToProductDTO(Product product){// Конвертер DTO для продукта
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    } // Конвертер DTO для продукта
    private OrderDTO convertToOrderDTO(Order order){ // Конвертер DTO для заказа
        return OrderDTO.builder()
                .id(order.getId())
                .build();
    } // Конвертер DTO для заказа
    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews){// Конвертер DTO для списка отзывов
        return reviews.stream().map(this::convertToReviewsDTO).collect(Collectors.toList());
    } // Конвертер DTO для списка отзывов
    private ReviewDTO convertToReviewsDTO(Review review){// Конвертер DTO для отзыва
        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .build();
    } // Конвертер DTO для отзыва

//    ================================================== CONVERTER =====================================================

    private Worker convertWorkerDTOToWorker(WorkerDTO workerDTO){ // Конвертер из DTO для работника
        return workerService.getWorkerById(workerDTO.getWorkerId());
    } // Конвертер из DTO для работника
    private Company convertCompanyDTOToCompany(CompanyDTO companyDTO){ // Конвертер из DTO для компании
        return companyService.getCompaniesById(companyDTO.getId());
    } // Конвертер из DTO для компании
    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){// Конвертер из DTO для менеджера
        return managerService.getManagerById(managerDTO.getManagerId());
    } // Конвертер из DTO для менеджера
    private OrderStatus convertStatusDTOToStatus(OrderStatusDTO orderStatusDTO){// Конвертер из DTO для статуса заказа
        return orderStatusService.getOrderStatusByTitle(orderStatusDTO.getTitle());
    } // Конвертер из DTO для статуса заказа
    private Filial convertFilialDTOToFilial(FilialDTO filialDTO){// Конвертер из DTO для филиала
        return filialService.getFilial(filialDTO.getId());
    } // Конвертер из DTO для филиала
    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId){ // Конвертер из DTO для заказа
        Product product1 = productService.findById(productId);
        System.out.println(orderDTO);
        return Order.builder()
                .amount(orderDTO.getAmount())
                .complete(false)
                .worker(convertWorkerDTOToWorker(orderDTO.getWorker()))
                .company(convertCompanyDTOToCompany(orderDTO.getCompany()))
                .manager(convertManagerDTOToManager(orderDTO.getManager()))
                .filial(convertFilialDTOToFilial(orderDTO.getFilial()))
                .sum(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .status(convertStatusDTOToStatus(orderDTO.getStatus()))
                .build();
    } // Конвертер из DTO для заказа
    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId){ // Конвертер из DTO для деталей заказа
        Product product1 = productService.findById(productId);
        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .order(order)
                .product(product1)
                .comment("")
                .build();
    } // Конвертер из DTO для деталей заказа
    private List<Review> toEntityListReviewsFromDTO(OrderDTO orderDTO, OrderDetails orderDetails){ // Конвертер из DTO для списка отзывов
        List<Review> reviewList = new ArrayList<>();
        for (int i = 0; i < orderDTO.getAmount(); i++) {
            Review review = toEntityReviewFromDTO(orderDTO.getCompany(), orderDetails, orderDTO.getWorker(), orderDTO.getFilial());
            Review review2 = reviewService.save(review);
            reviewList.add(review2);
        }
        return reviewList;
    } // Конвертер из DTO для списка отзывов
    private Review toEntityReviewFromDTO(CompanyDTO companyDTO, OrderDetails orderDetails, WorkerDTO workerDTO, FilialDTO filialDTO){ // Конвертер из DTO для отзыва
        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(workerDTO.getWorkerId());
        var random = new SecureRandom();

        return Review.builder()
                .category(convertCategoryDTOToCompany(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()))
                .text("Текст отзыва")
                .answer("Ответ на отзыв")
                .orderDetails(orderDetails)
                .bot(bots.get(random.nextInt(bots.size())))
                .filial(convertFilialDTOToFilial(filialDTO))
                .publish(false)
                .worker(orderDetails.getOrder().getWorker())
                .build();
    }// Конвертер из DTO для отзыва
    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){ // Конвертер из DTO для категории
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // Конвертер из DTO для категории
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){// Конвертер из DTO для субкатегории
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    } // Конвертер из DTO для субкатегории



//    ==================================================================================================================
}
