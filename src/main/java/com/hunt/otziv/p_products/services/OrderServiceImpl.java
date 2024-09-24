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
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.dto.*;
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
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.webjars.NotFoundException;

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
    private final EmailService emailService;

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String OWNER = "ROLE_OWNER";
    public static final String MANAGER = "ROLE_MANAGER";
    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_PAYMENT = "Оплачено";
    public static final String STATUS_PUBLIC = "Опубликовано";
    public static final String STATUS_ARCHIVE = "Архив";
    public static final String STATUS_COMPANY_IN_WORK = "В работе";
    public static final String STATUS_COMPANY_IN_STOP = "На стопе";
    public static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";
    private static final int MEDIUM_COUNTER_THRESHOLD = 10;
    private static final int HIGH_COUNTER_THRESHOLD = 20;
    private static final String MEDIUM_STATUS = "Средний";
    private static final String HIGH_STATUS = "Высокий";
    private final MessageSource messageSource;


    //    ======================================== ВЗЯТЬ ЗАКАЗЫ ПО РОЛЯМ =============================================================

    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize){ // Берем все заказы для выбранной компании по id
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByCompanyIdAndKeyWord(companyId, keyword, keyword);
            orderPage = orderRepository.findAll(orderId);
        }
        else{
            orderId = orderRepository.findAllIdByCompanyId(companyId);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    }  // Берем все заказы для выбранной компании по id


    public List<OrderDTO> getAllOrderDTO(){
        return convertToOrderDTOList(orderRepository.findAll());
    }
    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByKeyWord(keyword, keyword);
            orderPage = orderRepository.findAll(orderId);
        }
        else{
            orderId = orderRepository.findAllIdToAdmin();
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    }  // Берем все заказы с поиском по названию компании или номеру

    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByKeyWordAndStatus(keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderId);
        }
        else{
            orderId = orderRepository.findAllIdByStatus(status);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    }  // Берем все заказы с поиском по названию компании или номеру


    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByByManagerAndKeyWord(manager,keyword, keyword);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            orderId = orderRepository.findAllIdToManager(manager);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Менеджера


    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByManagerAndKeyWordAndStatus(manager,keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            orderId = orderRepository.findAllIdByManagerAndStatus(manager, status);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Менеджера


    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByOwnerAndKeyWord(managerList, keyword, keyword);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            orderId = orderRepository.findAllIdToOwner(managerList);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Менеджера


    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            System.out.println("отработал метод с ключевым словом");
            orderId = orderRepository.findAllIdByOwnerAndKeyWordAndStatus(managerList,keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            System.out.println("отработал метод с БЕЗ ключевго слова");
            orderId = orderRepository.findAllIdByOwnerAndStatus(managerList, status);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Менеджера



    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByByWorkerAndKeyWord(worker,keyword, keyword);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            orderId = orderRepository.findAllIdToWorker(worker);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrdersToWorkers(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Работника


    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize){ // Берем все заказы с поиском для Менеджера
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> orderId;
        List<Order> orderPage;
        if (!keyword.isEmpty()){
            orderId = orderRepository.findAllIdByWorkerAndKeyWordAndStatus(worker,keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderId);
        }
        else {
            orderId = orderRepository.findAllIdByWorkerAndStatus(worker, status);
            orderPage = orderRepository.findAll(orderId);
        }
        return getPageOrders(orderPage,pageNumber,pageSize);
    } // Берем все заказы с поиском для Работника


    private Page<OrderDTOList> getPageOrders(List<Order> orderPage, int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updateStatus").descending());
        Pair<Integer, Integer> startAndEnd = calculateStartAndEnd(pageable, orderPage.size());

        List<OrderDTOList> orderListDTOs = orderPage.subList(startAndEnd.getFirst(), startAndEnd.getSecond())
                .stream()
                .map(this::toDTOListOrders)
                .collect(Collectors.toList());
        return new PageImpl<>(orderListDTOs, pageable, orderPage.size());
    }

    private Pair<Integer, Integer> calculateStartAndEnd(Pageable pageable, int size) {
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), size);
        return Pair.of(start, end);
    }


    private Page<OrderDTOList> getPageOrdersToWorkers(List<Order> orderPage, int pageNumber, int pageSize) {
        // Сортируем список заказов по статусу "В работе"
        List<Order> sortedOrderPage = orderPage.stream()
                .sorted(Comparator.comparing(order -> "Публикация".equals(order.getStatus().getTitle()) ? 0 : 1))
                .toList();
        // Создаем Pageable для разбиения на страницы
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("status").descending());
        // Определяем начальный и конечный индексы для текущей страницы
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), sortedOrderPage.size());
        // Преобразуем подсписок в DTO
        List<OrderDTOList> orderListDTOs = sortedOrderPage.subList(start, end)
                .stream()
                .map(this::toDTOListOrders)
                .collect(Collectors.toList());
        // Возвращаем страницу с DTO
        return new PageImpl<>(orderListDTOs, pageable, sortedOrderPage.size());
    }

    public Order getOrder(Long orderId){ // Взять заказ
        return orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Заказ № '%d' не найден", orderId)));
    } // Взять заказ
    public OrderDTO getOrderDTO(Long orderId){ // Взять заказ DTO
        return  toDTO(orderRepository.findById(orderId).orElseThrow());
    } // Взять заказ DTO


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
    @Transactional
    protected Review createNewReview(Company company, OrderDetails orderDetails, Order order){ // Создание нового отзыва
        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(order.getWorker().getId());
        Bot selectedBot = null;
        if (!bots.isEmpty()) {
            var random = new SecureRandom();
            selectedBot = bots.get(random.nextInt(bots.size()));
        }
        var random = new SecureRandom();
        return Review.builder()
                .category(company.getCategoryCompany())
                .subCategory(company.getSubCategory())
                .text("Текст отзыва")
                .answer(" ")
                .orderDetails(orderDetails)
                .bot(selectedBot)
                .filial(order.getFilial())
                .publish(false)
                .worker(order.getWorker())
                .build();
    } // Создание нового отзыва
    @Transactional
    public boolean addNewReview(Long orderId) {  // Добавление нового отзыва
        try {
            log.info("1. Зашли в добавление нового отзыва");

            // Ищем заказ по ID
            Order saveOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new EntityNotFoundException(String.format("Заказ '%d' не найден", orderId)));

            // Получаем детали заказа
            OrderDetails orderDetails = saveOrder.getDetails().get(0);
            Company saveCompany = saveOrder.getCompany();

            log.info("2. Создаем новый отзыв");

            // Создаем и сохраняем новый отзыв
            Review review = reviewService.save(createNewReview(saveCompany, orderDetails, saveOrder));

            log.info("3. Создали новый отзыв");

            // Обновляем список отзывов и сохраняем детали заказа
            List<Review> newList = orderDetails.getReviews();
            newList.add(review);
            orderDetails.setReviews(newList);
            orderDetails.setAmount(orderDetails.getAmount() + 1);
            orderDetails.setPrice(orderDetails.getPrice().add(orderDetails.getProduct().getPrice()));

            log.info("4. Сохраняем обновленные детали заказа: {}", orderDetails);
            orderDetailsService.save(orderDetails);

            // Обновляем и сохраняем заказ
            saveOrder.setAmount(saveOrder.getAmount() + 1);
            saveOrder.setSum(saveOrder.getSum().add(orderDetails.getProduct().getPrice()));
            orderRepository.save(saveOrder);

            log.info("5. Обновили счетчик и сумму в заказе");

            // Обновляем и сохраняем компанию
            saveCompany.setCounterNoPay(saveCompany.getCounterNoPay() + 1);
            companyService.save(saveCompany);

            log.info("6. Обновили счетчик в компании");

            return true;
        } catch (Exception e) {
            log.error("Ошибка при создании нового отзыва", e);
            return false;
        }
    } // Добавление нового отзыва

    @Transactional
    public boolean deleteNewReview(Long orderId, Long reviewId) { // Удаление отзыва
        try {
            Order saveOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new EntityNotFoundException(String.format("Заказ '%d' не найден", orderId)));

            OrderDetails orderDetails = saveOrder.getDetails().getFirst();
            Company saveCompany = saveOrder.getCompany();
            log.info("1. Найден заказ и его детали");

            List<Review> newList = orderDetails.getReviews();
            Review review = reviewService.getReviewById(reviewId);
            if (review == null) {
                log.warn("Отзыв с ID '{}' не найден", reviewId);
                return false;
            }

            newList.remove(review);
            orderDetails.setReviews(newList);
            orderDetails.setAmount(orderDetails.getAmount() - 1);
            orderDetails.setPrice(orderDetails.getPrice().subtract(orderDetails.getProduct().getPrice()));
            orderDetailsService.save(orderDetails);
            log.info("2. Обновили детали заказа");

            saveOrder.setAmount(saveOrder.getAmount() - 1);
            saveOrder.setSum(saveOrder.getSum().subtract(orderDetails.getProduct().getPrice()));
            Order saveOrder2 = orderRepository.save(saveOrder);
            log.info("3. Обновили заказ");

            reviewService.deleteReview(reviewId);
            log.info("4. Удалили отзыв");

            saveCompany.setCounterNoPay(saveCompany.getCounterNoPay() - 1); // уменьшаем счетчик
            companyService.save(saveCompany);
            log.info("5. Обновили компанию");

            return true;
        } catch (Exception e) {
            log.error("Ошибка при удалении отзыва", e);
            return false;
        }
    }

//============================= СОХРАНЕНИЕ НВООГО ORDER, ORDER_DETAIL И СПИСКА REVIEWS==================================
    /**
     * Создает новый заказ с отзывами для указанной компании и продукта.
     * @param companyId Идентификатор компании.
     * @param productId Идентификатор продукта.
     * @param orderDTO  DTO объекта заказа, содержащий детали заказа.
     * @return true, если заказ успешно создан, иначе false.
     * @throws RuntimeException если происходит ошибка при создании заказа.
     */
    @Transactional
    @Override
    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) {
        try {
            Order order = saveOrder(orderDTO, productId);
            log.info("1. Сохранили ORDER");
            OrderDetails orderDetails = saveOrderDetails(order, orderDTO, productId);
            log.info("5. Сохранили ORDER-DETAIL с REVIEWS");
            log.info("6. Установили его в ORDER");
            updateOrder(order, orderDetails);
            log.info("9. Сохранили ORDER с ORDER-DETAIL в БД");
            log.info("10. Обновляем счетчик компании в БД");
            updateCompanyCounter(order, companyId);
            return true;
        } catch (PersistenceException | NumberFormatException e) {  //replace these with exceptions you expect
            log.error("Ошибка при создании нового заказа с отзывами", e);
            throw new RuntimeException("Ошибка при создании нового заказа с отзывами", e);
        }
    }


    private Order saveOrder(OrderDTO orderDTO, Long productId) {
        Order order = toEntityOrderFromDTO(orderDTO, productId);
        return orderRepository.save(order);
    }

    private OrderDetails saveOrderDetails(Order order, OrderDTO orderDTO, Long productId) {
        OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, order, productId);
        OrderDetails savedOrderDetails = orderDetailsService.save(orderDetails);
        List<Review> reviews = toEntityListReviewsFromDTO(orderDTO, savedOrderDetails);
        savedOrderDetails.setReviews(reviews);
        return orderDetailsService.save(savedOrderDetails);
    }

    private void updateOrder(Order order, OrderDetails orderDetails) {
        List<OrderDetails> detailsList = Optional.ofNullable(order.getDetails()).orElse(new ArrayList<>());
        detailsList.add(orderDetails);
        order.setDetails(detailsList);
        orderRepository.save(order);
    }

    private void updateCompanyCounter(Order order, Long companyId) {
        Company company = companyService.getCompaniesById(companyId);
        company.setCounterNoPay(calculateCounterNoPayValue(order, company));
        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
        companyService.save(company);
    }

    private int calculateCounterNoPayValue(Order order, Company company){
        return company.getCounterNoPay() + (order.getAmount() - company.getCounterNoPay());
    }



//    @Transactional
//    @Override
//    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) { // сохранение нового ORDER, ORDER_DETAIL и списка REVIEWS
//        try {
//            Order order = toEntityOrderFromDTO(orderDTO, productId);
//            Order saveOrder = orderRepository.save(order);
//            log.info("1. Сохранили ORDER");
//
//            OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, saveOrder, productId);
//            OrderDetails orderDetailsSave = orderDetailsService.save(orderDetails);
//            log.info("2. Сохранили ORDER-DETAIL");
//
//            List<Review> reviews = toEntityListReviewsFromDTO(orderDTO, orderDetailsSave);
//            log.info("3. Сохранили REVIEWS");
//
//            orderDetailsSave.setReviews(reviews);
//            log.info("4. Установили REVIEWS в ORDER-DETAIL");
//
//            OrderDetails orderDetails2 = orderDetailsService.save(orderDetailsSave);
//            log.info("5. Сохранили ORDER-DETAIL с REVIEWS");
//
//            List<OrderDetails> detailsList;
//            if (saveOrder.getDetails() != null) {
//                detailsList = saveOrder.getDetails();
//                log.info("6. Взяли ORDER-DETAIL лист");
//            } else {
//                detailsList = new ArrayList<>();
//                log.info("6. Создали пустой ORDER-DETAIL лист");
//            }
//
//            detailsList.add(orderDetails2);
//            log.info("7. Добавили ORDER-DETAIL лист");
//
//            saveOrder.setDetails(detailsList);
//            log.info("8. Установили его в ORDER");
//
//            Order saveOrder2 = orderRepository.save(saveOrder);
//            log.info("9. Сохранили ORDER с ORDER-DETAIL в БД");
//
//            log.info("10. Обновляем счетчик компании в БД");
//
//            Company company = companyService.getCompaniesById(companyId);
//            company.setCounterNoPay(company.getCounterNoPay() + (saveOrder2.getAmount() - company.getCounterNoPay()));
//            company.setStatus(companyStatusService.getStatusByTitle("В работе"));
//            companyService.save(company);
//
//            return true;
//        } catch (Exception e) {
//            log.error("Ошибка при создании нового заказа с отзывами", e);
//            throw new RuntimeException("Ошибка при создании нового заказа с отзывами", e); // убеждаемся, что транзакция откатывается при ошибке
//        }
//    }
//============================ СОХРАНЕНИЕ НВООГО ORDER, ORDER_DETAIL И СПИСКА REVIEWS КОНЕЦ ============================
//


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
        System.out.println(orderDTO.getCommentsCompany());
        /*Временная проверка сравнений*/
        System.out.println("filial id: " + !Objects.equals(orderDTO.getFilial().getId(), saveOrder.getFilial().getId()));
        System.out.println("filial url: " + !Objects.equals(orderDTO.getFilial().getUrl(), saveOrder.getFilial().getUrl()));
        try {System.out.println("worker: " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId()) + " " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getDetails().getFirst().getReviews().getFirst().getWorker().getId()));} catch (Exception e) {// Логируем ошибку и пропускаем выполнение блока
            log.error("Ошибка при обновлении работника заказа: ", e);}
        System.out.println("worker: " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId()) + " " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getDetails().getFirst().getReviews().getFirst().getWorker().getId()));
        System.out.println("manager: " + !Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getManager().getId()));
        System.out.println("complete: " + !Objects.equals(orderDTO.isComplete(), saveOrder.isComplete()));
        System.out.println("комментарий: " + !Objects.equals(orderDTO.getCommentsCompany(), saveOrder.getCompany().getCommentsCompany()));
        System.out.println("счетчик: " + !Objects.equals(orderDTO.getCounter(), saveOrder.getCounter()));


        if (!Objects.equals(orderDTO.getFilial().getId(), saveOrder.getFilial().getId())){ /*Проверка смены названия*/
            log.info("Обновляем филиал заказа");
            System.out.println(saveOrder.getFilial());
            saveOrder.setFilial(convertFilialDTOToFilial(orderDTO.getFilial()));
            log.info("Сменили филиал заказа");
            Filial filial = filialService.getFilial(orderDTO.getFilial().getId());
            List<Review> reviews = saveOrder.getDetails().getFirst().getReviews();
            for (Review review : reviews)   {
                review.setFilial(filial);
                reviewService.save(review);
                log.info("Сменили филиал у отзыва в заказе");
            }

            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getFilial().getUrl(), saveOrder.getFilial().getUrl())){ /*Проверка смены филиала*/
            log.info("Обновляем url филиала заказа");
        }
        try {
            // Проверяем, изменился ли работник
            if (!Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId()) ||
                    !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getDetails().getFirst().getReviews().getFirst().getWorker().getId())) {

                log.info("Обновляем работника заказа");
                Worker newWorker = convertWorkerDTOToWorker(orderDTO.getWorker());

                // Обновляем основного работника в заказе
                saveOrder.setWorker(newWorker);

                // Обновляем работника в связанных отзывах
                for (OrderDetails orderDetails : saveOrder.getDetails()) {
                    for (Review review : orderDetails.getReviews()) {
                        review.setWorker(newWorker);
                    }
                }
                isChanged = true;
            }
        } catch (Exception e) {
            // Логируем ошибку и пропускаем выполнение блока
            log.error("Ошибка при обновлении работника заказа: ", e);
        }
        if (!Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getManager().getId())){ /*Проверка смены работника*/
            log.info("Обновляем менеджера заказа");
            saveOrder.setManager(convertManagerDTOToManager(orderDTO.getManager()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())){ /*Проверка статус заказа*/
            log.info("Обновляем статус выполнения Заказа");
            saveOrder.setComplete(orderDTO.isComplete());
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getCommentsCompany(), saveOrder.getCompany().getCommentsCompany())){ /*Проверка комментария заказа*/
            log.info("Обновляем выполнение комментария заказа");
            saveOrder.getCompany().setCommentsCompany(orderDTO.getCommentsCompany());
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.getCounter(), saveOrder.getCounter())){ /*Проверка комментария заказа*/
            log.info("Обновляем выполнение счетчик опубликованных текстов в заказе");
            saveOrder.setCounter(orderDTO.getCounter());
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

//============================================ УДАЛЕНИЕ ЗАКАЗА =========================================================
@Transactional
public boolean deleteOrder(Long orderId, Principal principal){
    String userRole = getRole(principal);
    Order orderToDelete = orderRepository.findById(orderId)
            .orElseThrow(() -> new UsernameNotFoundException(String.format("Order '%d' not found", orderId)));
    if (canDeleteOrder(userRole, orderToDelete)) {
        orderRepository.delete(orderToDelete);
        log.info("Заказ удален Админом или Владельцем");
        return true;
    }
    log.info("Заказ не удален из-за статуса или роли");
    return false;
}

    private boolean isAdminOrOwner(String role) {
        return ADMIN.equals(role) || OWNER.equals(role);
    }

    private boolean isNewlyCreatedOrder(Order order) {
        return STATUS_NEW.equals(order.getStatus().getTitle());
    }

    private boolean canDeleteOrder(String role, Order orderToDelete) {
        return isAdminOrOwner(role) || (MANAGER.equals(role) && isNewlyCreatedOrder(orderToDelete));
    }


//    @Transactional
//    public boolean deleteOrder(Long orderId, Principal principal){
//        String userRole = getRole(principal);
//        Order deleteOrder = orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));
//        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole) ){
//            orderRepository.delete(deleteOrder);
//            log.info("заказ удален Админом или Владельцем");
//            return true;
//        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            if (deleteOrder.getStatus().getTitle().equals("Новый")){
//                orderRepository.delete(deleteOrder);
//                log.info("заказ удален Менеджером");
//                return true;
//            } else
//                System.out.println("не удален из-за статуса");
//        }
//        else
//            System.out.println("не удален из-за отсутсвия прав доступа");
//        return false;
//    }

//========================================= УДАЛЕНИЕ ЗАКАЗА КОНЕЦ ======================================================

    private String getRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    @Override
    public int getAllOrderDTOByStatus(String status) {
        return orderRepository.findAllIdByStatus(status).size();
    }

    @Override
    public int getAllOrderDTOByStatusToManager(Manager manager, String status) {
        return orderRepository.findAllIdByManagerAndStatus(manager, status).size();
    }

    @Override
    public int getAllOrderDTOByStatusToOwner(Set<Manager> managerList, String status) {
        return orderRepository.findAllIdByOwnerAndStatus(managerList, status).size();
    }

    public Review saveReviews(Review review, Worker newWorker) {
        review.setWorker(newWorker);
        return reviewService.save(review);
    }

//========================= СМЕНА СТАТУСА ЗАКАЗА С ПРОВЕРКОЙ НА ОПЛАЧЕНО================================================


    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title){ // смена статуса для заказа с проверкой на Оплачено
        try {
            Order order = orderRepository.findById(orderID).orElseThrow(() -> new NotFoundException("Order  not found for orderID: " + orderID));
            if (title.equals(STATUS_PAYMENT)){
                log.info("1. Пришел запрос на перевод статуса заказа в статус Оплачено");
                log.info("1. Смотрим текущий статус заказа выполнен или нет - orderIsComplete: {}", order.isComplete());
                log.info("1. Проверка счетчиков order.getAmount() <= order.getCounter(): {}", Objects.equals(order.getAmount(), order.getCounter()));

                if (!order.isComplete() && Objects.equals(order.getAmount(), order.getCounter())){
                    log.info("2. Проверили, что заказ еще не бьл выполнен и что счетчкики совпадают");
                    if (zpService.save(order)){
                        log.info("3. Сохранили ЗП");
                        boolean chek = paymentCheckService.save(order);
                        log.info(String.valueOf(chek));
                        log.info("4. Сохранили Чек компании");
                        Company company = companyService.getCompaniesById(order.getCompany().getId());

                        try {
                            company.setCounterPay(company.getCounterPay() + order.getAmount());
                            log.info("счетчик: {}{}", company.getCounterPay(), order.getAmount());
                            company.setSumTotal(company.getSumTotal().add(order.getSum()));
                            log.info("сумма: {}", company.getSumTotal().add(order.getSum()));
                            log.info("5. Успешно установили суммы");
                            order.setComplete(true);
                            order.setPayDay(LocalDate.now());
                            log.info("Дата оплаты: {}", order.getPayDay());
                            orderRepository.save(order);
                            log.info("6. Заказ обновлен и сохранен");
                            companyService.save(checkStatusToCompany(company));
                            log.info("7. Компания сохранена, статус сменен на Готов к Новому заказу");
//                             Создание нового заказа с отзывами
                            if (createNewOrderWithReviews(company.getId(), order.getDetails().getFirst().getProduct().getId(), convertToOrderDTOToRepeat(order))) {
                                log.info("8. Новый заказ создался автоматически - Успешно");
                                log.info("8. Оплата поступила, ЗП начислена Менеджеру и Работнику");
                            }
                            else {
                                log.info("8. Новый заказ создался автоматически - НЕ Успешно");
                                throw new Exception("8. Новый заказ создался автоматически - НЕ успешно");
                            }
                            log.info("8. Новый заказ создался автоматически - Успешно");
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
            if (order.getStatus().getTitle().equals(STATUS_ARCHIVE)){
                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                order.getCompany().setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
                log.info("Сменили статус компании на В работе");
                orderRepository.save(order);
            }
            if (title.equals(STATUS_ARCHIVE)){
                saveReviewsToArchive(order.getDetails().getFirst().getReviews());
                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                order.getCompany().setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_STOP));
                orderRepository.save(order);
                return true;
            }
            else {
                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                orderRepository.save(order);
                return true;
            }
        } catch (Exception e){
            log.info("При смене статуса произошли какие-то проблемы");
            return false;
        }
    } // смена статуса для заказа с проверкой на Оплачено


//====================== СМЕНА СТАТУСА ЗАКАЗА С ПРОВЕРКОЙ НА ОПЛАЧЕНО КОНЕЦ ============================================

    private void saveReviewsToArchive(List<Review> reviews) { // сохранение отзывов в архив при отправке заказа в статус архив
        for (Review review : reviews) {
            reviewArchiveService.saveNewReviewArchive(review.getId());
        }
    } // сохранение отзывов в архив при отправке заказа в статус архив


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
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        }
        return company;
    }


    @Override
    @Transactional
    public boolean changeStatusAndOrderCounter(Long reviewId) {
        boolean isChanged = false;
        try {

            Review review = reviewService.getReviewById(reviewId);
            log.info("2. Достали отзыв по id {}", reviewId);

            if (review == null) {
                log.error("2. Отзыв с id {} не найден", reviewId);
                throw new IllegalStateException("Проблема с отсутвием отзыва по ид, транзакция должна быть откатана");
//                return false;
            }

            OrderDetails orderDetails = review.getOrderDetails();
            if (orderDetails == null || orderDetails.getOrder() == null) {
                log.error("3. OrderDetails или Order отсутствуют для отзыва с ID: {}", reviewId);
                throw new IllegalStateException("Проблема с отсутвием деталей заказа по ид, транзакция должна быть откатана");
//                return false;
            }

            Order order = orderRepository.findById(orderDetails.getOrder().getId()).orElse(null);
            if (order == null || review.isPublish()) {
                log.info("3. Проверка не пройдена: пустой order = {}, или отзыв уже опубликован publish = {}", order != null, review.isPublish());
                throw new IllegalStateException("Проблема с отсутвием заказа по ид и статуса уже опубликован, транзакция должна быть откатана");
//                return false;
            }

            log.info("3. Заказ найден, и отзыв еще не опубликован. Продолжаем выполнение.");

            reviewArchiveService.saveNewReviewArchive(reviewId);
            log.info("4. Сохранили отзыв в архив");

            updateBotCounterAndStatus(review.getBot());
            log.info("5. Увеличили кол-во публикаций у бота");

            review.setPublish(true);
            log.info("6. Установили статус публикации отзыва на true");
            reviewService.save(review);
            log.info("7. Сохранили отзыв в базе данных");

            order.setCounter(order.getCounter() + 1);
            Order savedOrder = orderRepository.save(order);
            log.info("8. Обновили счетчик публикаций заказа. Новый счетчик: {}", savedOrder.getCounter());

            int reviewCounter = counterReviewIsPublish(savedOrder);
            
            log.info("9. reviewCounter: {}", reviewCounter);
            
            if (savedOrder.getCounter() != reviewCounter){
                log.info("9. !!!!!!!!!!! ЧТО-ТО НЕ ТАК !!!!!! Проверка savedOrder.getCounter() != reviewCounter не пройдена: savedOrder.getCounter() = {},  reviewCounter = {}", savedOrder.getCounter(), reviewCounter);
                emailService.sendSimpleEmail("2.12nps@mail.ru", "Ошибка проверки счетчика", "Срочно проверь. Что-то пошло не так при нажатии кнопки опубликовать у отзыва");
                isChanged = true;
                log.info("isChanged {}", isChanged);
                throw new IllegalStateException("Проблема с проверкой счетчиков, транзакция должна быть откатана");
            }
            checkOrderCounterAndAmount(savedOrder);
            log.info("10. Проверили счетчик заказа на выполнение заказа");

            if  (isChanged){
                return false;
            }
            else {
                return true;
            }
        } catch (Exception e) {
            log.error("Ошибка при выполнении метода changeStatusAndOrderCounter для отзыва с id {}", reviewId, e);
//            return false;
            throw e; // Транзакция откатится при исключении
        }
    }



    protected int counterReviewIsPublish(Order savedOrder){
        int reviewCounter = 0;
        List<Review> reviewList = savedOrder.getDetails().getFirst().getReviews();
        for (Review review1 : reviewList) {
            if (review1.isPublish()) {
                reviewCounter++;
            }
        }
        return reviewCounter;
    }


    public void updateBotCounterAndStatus(Bot bot) {
        try {
            bot.setCounter(bot.getCounter() + 1);

            if (bot.getCounter() >= HIGH_COUNTER_THRESHOLD) {
                log.info("Меняем статус бота при достижении 20 отзывов");
                bot.setStatus(botService.changeStatus(HIGH_STATUS));
            } else if (bot.getCounter() >= MEDIUM_COUNTER_THRESHOLD) {
                log.info("Меняем статус бота при достижении 10 отзывов");
                bot.setStatus(botService.changeStatus(MEDIUM_STATUS));
            }

            botService.save(bot);
        } catch (Exception e) {
            log.error("Ошибка при обновлении счетчика и статуса у бота с id {}", bot.getId(), e);
            throw e;
        }
    }

    @Transactional
    protected void checkOrderCounterAndAmount(Order order) {
        try {
            if (order.getAmount() <= order.getCounter()) {
                changeStatusForOrder(order.getId(), STATUS_PUBLIC);
                log.info("4. Счетчик совпадает с количеством заказа. Статус заказа с id {} сменен на Опубликовано", order.getId());
            } else {
                log.info("4. Счетчик не совпадает с количеством заказа с id {}. Статус заказа не изменён", order.getId());
            }
        } catch (Exception e) {
            log.error("Ошибка при проверке счетчиков заказа с id {}", order.getId(), e);
            throw e;
        }
    }





//    @Override
//    @Transactional
//    public boolean changeStatusAndOrderCounter(Long reviewId) { // смена статуса отзыва, увеличение счетчика и смена статуса заказа если выполнен
//        try {
//            Review review = reviewService.getReviewById(reviewId);
//            log.info("2. Достали отзыв по id " + reviewId);
//
//            if (review == null) {
//                log.info("2. Что-то пошло не так и метод changeStatusAndOrderCounter не отработал правильно: отзыв не найден");
//                return false;
//            }
//
//            Order order = orderRepository.findById(review.getOrderDetails().getOrder().getId()).orElse(null);
//            if (order == null || review.isPublish()) {
//                log.info("3. Счетчик не увеличен, проверка не пройдена: order = " + (order != null) + ", publish status = " + (!review.isPublish()));
//                return false;
//            }
//
//            log.info("3. Прошли проверку что заказ не пустой и его статус не опубликован - order != null && !review.isPublish()");
//            reviewArchiveService.saveNewReviewArchive(reviewId);
//            log.info("4. Сохранили опубликованный отзыв компании в отзыв в архив");
//
//            order.setCounter(order.getCounter() + 1);
//            Order savedOrder = orderRepository.save(order);
//            log.info("5. Увеличили, обновили и сохранили счетчик публикаций в заказе. Итого теперь: {}", savedOrder.getCounter());
//
//            checkOrderCounterAndAmount(savedOrder);
//            log.info("6. Увеличиваем счетчик публикаций бота и сохраняем его");
//
//            updateBotCounterAndStatus(review.getBot());
//            review.setPublish(true);
//            log.info("7. Установили Publish на true - опубликовано");
//
//            reviewService.save(review);
//            log.info("8. Сохранили отзыв в БД");
//
//            return true;
//        } catch (Exception e) {
//            log.error("Ошибка при выполнении метода changeStatusAndOrderCounter, который  делал - смена статуса отзыва, увеличение счетчика и смена статуса заказа если выполнен", e);
//            throw e; // Убедитесь, что транзакция откатывается при ошибке
//        }
//    } // смена статуса отзыва, увеличение счетчика и смена статуса заказа если выполнен


//    @Transactional
//    public void updateBotCounterAndStatus(Bot bot) {// обновление счетчика и статуса у бота
//        try {
//            bot.setCounter(bot.getCounter() + 1);
//            if (bot.getCounter() >= HIGH_COUNTER_THRESHOLD) {
//                log.info("Меняем статус бота от 20 отзывов");
//                bot.setStatus(botService.changeStatus(HIGH_STATUS));
//            } else if (bot.getCounter() >= MEDIUM_COUNTER_THRESHOLD) {
//                log.info("Меняем статус бота от 10 отзывов");
//                bot.setStatus(botService.changeStatus(MEDIUM_STATUS));
//            }
//            botService.save(bot);
//        } catch (Exception e) {
//            log.error("Ошибка при обновлении счетчика и статуса у бота", e);
//            throw e;
//        }
//    }// обновление счетчика и статуса у бота


//    @Transactional
//    protected void changeBotCounterAndStatus(Bot bot) { // обновление счетчика и статуса у бота
//        try {
//            bot.setCounter(bot.getCounter() + 1);
//            if (bot.getCounter() >= 10) {
//                log.info("6. меняем статус бота от 10 отзывов");
//                bot.setStatus(botService.changeStatus("Средний"));
//            }
//            if (bot.getCounter() >= 20) {
//                log.info("6. меняем статус бота от 20 отзывов");
//                bot.setStatus(botService.changeStatus("Высокий"));
//            }
//            botService.save(bot);
//        } catch (Exception e) {
//            log.error("Ошибка при обновлении счетчика и статуса у бота", e);
//            throw e; // Убедитесь, что транзакция откатывается при ошибке
//        }
//    } // обновление счетчика и статуса у бота



//    @Transactional
//    protected void checkOrderCounterAndAmount(Order order) { // проверка счетчиков заказа
//        try {
//            if (order.getAmount() <= order.getCounter()) {
//                changeStatusForOrder(order.getId(), STATUS_PUBLIC);
//                log.info("4. Счетчик совпадает с количеством заказа. Статус заказа сменен на Опубликовано");
//            } else {
//                log.info("4. Счетчик НЕ совпадает с количеством заказа. Статус заказа НЕ сменен на Опубликовано");
//            }
//        } catch (Exception e) {
//            log.error("Ошибка при проверке счетчиков заказа", e);
//            throw e; // проверка счетчиков заказа
//        }
//    }


    //    ======================================== ЗАКАЗ UPDATE =========================================================









    //    ================================================== CONVERTER =====================================================

    private List<OrderDTOList> toOrderDTOList(List<Order> orders){ // Конвертер DTO для списка заказов AllOrderListController/orders/
        return orders.stream().map(this::toDTOListOrders).collect(Collectors.toList());
    } // Конвертер DTO для списка заказов


    private OrderDTOList toDTOListOrders (Order order){// Конвертер DTO для заказа
        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged();
        // Вычисляем разницу между датами
        Period period = Period.between(changedDate, now);
        // Преобразуем период в дни
        int daysDifference = period.getDays();
        return OrderDTOList.builder()
                .id(order.getId())
                .companyId(order.getCompany().getId())
                .orderDetailsId(order.getDetails().iterator().next().getId())
                .companyTitle(order.getCompany().getTitle())
                .companyComments(order.getCompany().getCommentsCompany())
                .filialTitle(order.getFilial().getTitle())
                .filialUrl(order.getFilial().getUrl())
                .status(order.getStatus().getTitle())
                .sum(order.getSum())
                .companyUrlChat(order.getCompany().getUrlChat())
                .companyTelephone(order.getCompany().getTelephone())
                .managerPayText(order.getManager().getPayText())
                .amount(order.getAmount())
                .counter(order.getCounter())
                .workerUserFio(order.getWorker().getUser().getFio())
                .categoryTitle(order.getCompany().getCategoryCompany().getCategoryTitle())
                .subCategoryTitle(order.getCompany().getSubCategory().getSubCategoryTitle())
                .created(order.getCreated())
                .changed(order.getChanged())
                .payDay(order.getPayDay())
                .dayToChangeStatusAgo(period.getDays())
                .build();
    } // Конвертер DTO для заказа на AllOrderListController/orders/






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
                .commentsCompany(order.getCompany().getCommentsCompany())
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
                .amount(order.getAmount())
                .worker(convertToWorkerDTO(order.getWorker()))
                .manager(convertToManagerDTO(order.getManager()))
                .company(convertToCompanyDTO(order.getCompany()))
                .build();
    } // Конвертер DTO для заказа
    public OrderDTO convertToOrderDTOToRepeat(Order order){ // Конвертер DTO для создания нового заказа после завершения предыдущего
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(convertToWorkerDTO(order.getWorker()))
                .manager(convertToManagerDTO(order.getManager()))
                .company(convertToCompanyDTO(order.getCompany()))
                .filial(convertToFilialDTO(order.getFilial()))
                .commentsCompany(order.getCompany().getCommentsCompany())
                .status(convertToStatusDTO("Новый"))
                .build();
    } // Конвертер DTO для создания нового заказа после завершения предыдущего

    private OrderStatusDTO convertToStatusDTO(String status) {
        return OrderStatusDTO.builder()
                .title(status)
                .build();
    }

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
        List<Bot> bots = findAllBotsMinusFilial(orderDTO, convertFilialDTOToFilial(orderDTO.getFilial()));

        for (int i = 0; i < orderDTO.getAmount(); i++) {
            Review review = toEntityReviewFromDTO(orderDTO.getCompany(), orderDetails, orderDTO.getFilial(), bots);
            Review review2 = reviewService.save(review);
            reviewList.add(review2);
        }
        return reviewList;
    } // Конвертер из DTO для списка отзывов
    private Review toEntityReviewFromDTO(CompanyDTO companyDTO, OrderDetails orderDetails, FilialDTO filialDTO, List<Bot> bots){ // Конвертер из DTO для отзыва
        var random = new SecureRandom();
        return Review.builder()
                .category(convertCategoryDTOToCompany(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()))
                .text("Текст отзыва")
                .answer("впишите сюда замечания к отзыву, если есть и нажмите кнопку <Сохранить>, затем кнопку <Корректировать>, ниже, под всеми отзывами")
                .orderDetails(orderDetails)
                .bot(!bots.isEmpty() ? bots.get(random.nextInt(bots.size())) : null)
                .filial(convertFilialDTOToFilial(filialDTO))
                .publish(false)
                .worker(orderDetails.getOrder().getWorker())
                .build();
    }// Конвертер из DTO для отзыва

    private List<Bot> findAllBotsMinusFilial(OrderDTO orderDTO, Filial filial){
        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(orderDTO.getWorker().getWorkerId());
        List<Review> reviewListFilial = reviewService.findAllByFilial(filial);
        List<Bot> botsCompany = reviewListFilial.stream().map(Review::getBot).toList();
        bots.removeAll(botsCompany);
        return bots;
    }

    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){ // Конвертер из DTO для категории
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // Конвертер из DTO для категории
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){// Конвертер из DTO для субкатегории
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    } // Конвертер из DTO для субкатегории



//    ==================================================================================================================
}
