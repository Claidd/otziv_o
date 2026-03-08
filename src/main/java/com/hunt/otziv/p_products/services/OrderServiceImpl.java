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
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.dto.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.*;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
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
    private final UserService userService;
    private final CompanyStatusService companyStatusService;
    private final TelegramService telegramService;
    private final PromoTextService textService;
    private final WhatsAppService whatsAppService;
    private final OrderTransactionService orderTransactionService;
    private final OrderStatusCheckerService orderStatusCheckerService;
    private final BotAssignmentService botAssignmentService;

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String OWNER = "ROLE_OWNER";
    public static final String MANAGER = "ROLE_MANAGER";

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_TO_CHECK = "В проверку";
    public static final String STATUS_IN_CHECK = "На проверке";
    public static final String STATUS_CORRECTION = "Коррекция";
    public static final String STATUS_TO_PUBLISH = "Публикация";
    public static final String STATUS_PAYMENT = "Оплачено";
    public static final String STATUS_PUBLIC = "Опубликовано";
    public static final String STATUS_TO_PAY = "Выставлен счет";
    public static final String STATUS_ARCHIVE = "Архив";

    public static final String STATUS_COMPANY_IN_WORK = "В работе";
    public static final String STATUS_COMPANY_IN_STOP = "На стопе";
    public static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";

    private static final int MEDIUM_COUNTER_THRESHOLD = 10;
    private static final int HIGH_COUNTER_THRESHOLD = 20;
    private static final String MEDIUM_STATUS = "Средний";
    private static final String HIGH_STATUS = "Высокий";

    private final String siteText =
            "1. Название и адрес филиала: Центр детских развлечений, г. Иркутск, мк-н, Юбилейный, 17.\n" +
                    "2. Основная сфера деятельности: Организация детских праздников, проведение квестов и развлечений.\n" +
                    "3. Как давно вы работаете: Работаем на рынке развлечений уже несколько лет.\n" +
                    "4. Что именно вы предлагаете: Организацию детских дней рождения \"под ключ\" с квестами, играми, анимацией, фотосессиями и питанием.\n" +
                    "5. Как выглядит вход: Интересный и яркий вход, оформленный в стиле детских приключений.\n" +
                    "6. Интерьер: Уютное и красочное помещение с различными зонами для игр и отдыха.\n" +
                    "7. Парковка и удобства: Есть парковочные места, комфортные условия для проведения мероприятий.\n" +
                    "8. Цены: Стоимость различных пакетов услуг начинается от 1100 рублей за человека.\n" +
                    "9. Хиты продаж: Популярные квесты \"Гарри Поттер\", \"Замок Дракулы\", а также пакеты дня рождения \"под ключ\".\n" +
                    "10. Уникальные предложения: Организация питания, бесплатная чайная зона, красочные костюмы для игроков.\n" +
                    "11. Имена и должности ключевых сотрудников: Не указано.\n" +
                    "12. Опыт, специализация: Специализация в проведении детских мероприятий и квестов.\n" +
                    "13. Акции и скидки: Скидки при большом количестве участников, скидка на повторное посещение.\n" +
                    "14. Фразы для отзыва: \"Наш ребенок провел здесь незабываемый день рождения! Все организовано на высшем уровне.\"\n" +
                    "15. Цитаты клиентов: \"Мои дети в восторге от проведенного времени! Спасибо за теплую атмосферу.\"\n" +
                    "16. Как происходит заказ: Заказ услуг осуществляется по телефону или через онлайн-форму на сайте.\n" +
                    "17. Гарантии и возвраты: Гарантия качества проведения мероприятий, возможность замены пакетов услуг.\n" +
                    "18. Срок выполнения: Время проведения мероприятий зависит от выбранного пакета услуг, от 2 до 4 часов.\n" +
                    "19. Прочая информация: Предоставляется широкий выбор развлечений для детей разного возраста и интересов, разнообразие квестов и анимаций.";

    // =========================================================================================================
    // ======================================== ВЗЯТЬ ЗАКАЗЫ ПО РОЛЯМ ==========================================
    // =========================================================================================================

    @Override
    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize) {
        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByCompanyIdAndKeyWord(companyId, keyword, keyword);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdByCompanyId(companyId);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public List<OrderDTO> getAllOrderDTO() {
        return convertToOrderDTOList(orderRepository.findAll());
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize) {
        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByKeyWord(keyword, keyword);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdToAdmin();
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize) {
        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByKeyWordAndStatus(keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdByStatus(status);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        Manager manager = resolveManagerFromPrincipal(principal);
        if (manager == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByByManagerAndKeyWord(manager, keyword, keyword);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdToManager(manager);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        Manager manager = resolveManagerFromPrincipal(principal);
        if (manager == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByManagerAndKeyWordAndStatus(manager, keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdByManagerAndStatus(manager, status);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        List<Manager> managerList = resolveOwnerManagersFromPrincipal(principal);
        if (managerList.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByOwnerAndKeyWord(managerList, keyword, keyword);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdToOwner(managerList);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        List<Manager> managerList = resolveOwnerManagersFromPrincipal(principal);
        if (managerList.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByOwnerAndKeyWordAndStatus(managerList, keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdByOwnerAndStatus(managerList, status);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        Worker worker = resolveWorkerFromPrincipal(principal);
        if (worker == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByByWorkerAndKeyWord(worker, keyword, keyword);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdToWorker(worker);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrdersToWorkers(orderPage, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        Worker worker = resolveWorkerFromPrincipal(principal);
        if (worker == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Long> orderIds;
        List<Order> orderPage;

        if (hasText(keyword)) {
            orderIds = orderRepository.findAllIdByWorkerAndKeyWordAndStatus(worker, keyword, status, keyword, status);
            orderPage = orderRepository.findAll(orderIds);
        } else {
            orderIds = orderRepository.findAllIdByWorkerAndStatus(worker, status);
            orderPage = orderRepository.findAll(orderIds);
        }

        return getPageOrders(orderPage, pageNumber, pageSize);
    }

    private Page<OrderDTOList> getPageOrders(List<Order> orderPage, int pageNumber, int pageSize) {
        if (orderPage == null || orderPage.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<OrderDTOList> allDTOs = orderPage.stream()
                .map(this::toDTOListOrders)
                .filter(Objects::nonNull)
                .toList();

        List<OrderDTOList> sortedDTOs = allDTOs.stream()
                .sorted(Comparator.comparingLong(OrderDTOList::getDayToChangeStatusAgo).reversed())
                .collect(Collectors.toList());

        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        Pair<Integer, Integer> startAndEnd = calculateStartAndEnd(pageable, sortedDTOs.size());

        List<OrderDTOList> orderListDTOs = sortedDTOs.subList(startAndEnd.getFirst(), startAndEnd.getSecond());
        return new PageImpl<>(orderListDTOs, pageable, sortedDTOs.size());
    }

    private Page<OrderDTOList> getPageOrdersToWorkers(List<Order> orderPage, int pageNumber, int pageSize) {
        if (orderPage == null || orderPage.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        List<Order> sortedOrderPage = orderPage.stream()
                .sorted(Comparator.comparing(order -> "Публикация".equals(safeStatusTitle(order)) ? 0 : 1))
                .toList();

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("status").descending());
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), sortedOrderPage.size());

        if (start >= sortedOrderPage.size()) {
            return new PageImpl<>(Collections.emptyList(), pageable, sortedOrderPage.size());
        }

        List<OrderDTOList> orderListDTOs = sortedOrderPage.subList(start, end)
                .stream()
                .map(this::toDTOListOrders)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PageImpl<>(orderListDTOs, pageable, sortedOrderPage.size());
    }

    @Override
    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(List<Long> workerIds, String status) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        return orderRepository.countByWorkerIdsAndStatus(workerIds, status)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Long) row[1]).intValue()
                ));
    }

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(Collection<Long> workerIds, String status) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : orderRepository.countByWorkerIdsAndStatus(workerIds, status)) {
            Long workerId = (Long) row[0];
            Long count = (Long) row[1];
            result.put(workerId, count.intValue());
        }
        return result;
    }

    private Pair<Integer, Integer> calculateStartAndEnd(Pageable pageable, int size) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), size);
        if (start > end) {
            start = end;
        }
        return Pair.of(start, end);
    }

    @Override
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Заказ № '%d' не найден", orderId)));
    }

    @Override
    public OrderDTO getOrderDTO(Long orderId) {
        return toDTO(orderRepository.findById(orderId).orElseThrow());
    }

    // =========================================================================================================
    // ======================================== СОЗДАНИЕ НОВЫХ ОТЗЫВОВ ==========================================
    // =========================================================================================================

    @Override
    public OrderDTO newOrderDTO(Long id) {
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(id);
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setCompany(companyDTO);
        orderDTO.setWorkers(companyDTO.getWorkers());
        orderDTO.setManager(companyDTO.getManager());
        orderDTO.setStatus(orderStatusService.getOrderStatusDTOByTitle(STATUS_NEW));
        orderDTO.setFilial(companyDTO.getFilial());
        return orderDTO;
    }

    @Transactional
    protected Review createNewReview(Company company, OrderDetails orderDetails, Order order) {
        List<Bot> bots = order != null && order.getWorker() != null
                ? botService.getAllBotsByWorkerIdActiveIsTrue(order.getWorker().getId())
                : Collections.emptyList();

        Bot selectedBot = null;
        if (bots != null && !bots.isEmpty()) {
            SecureRandom random = new SecureRandom();
            selectedBot = bots.get(random.nextInt(bots.size()));
        }

        Product product = orderDetails != null ? orderDetails.getProduct() : null;

        return Review.builder()
                .category(company != null ? company.getCategoryCompany() : null)
                .subCategory(company != null ? company.getSubCategory() : null)
                .text("Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(selectedBot)
                .filial(order != null ? order.getFilial() : null)
                .publish(false)
                .worker(order != null ? order.getWorker() : null)
                .product(product)
                .price(product != null ? product.getPrice() : null)
                .build();
    }

    @Override
    @Transactional
    public boolean addNewReview(Long orderId) {
        try {
            log.info("1. Зашли в добавление нового отзыва");

            Order saveOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new EntityNotFoundException(String.format("Заказ '%d' не найден", orderId)));

            OrderDetails orderDetails = getFirstDetail(saveOrder);
            if (orderDetails == null) {
                log.error("У заказа {} отсутствуют детали заказа", orderId);
                return false;
            }

            Company saveCompany = saveOrder.getCompany();
            if (saveCompany == null) {
                log.error("У заказа {} отсутствует компания", orderId);
                return false;
            }

            log.info("2. Создаем новый отзыв");

            Review review = reviewService.save(createNewReview(saveCompany, orderDetails, saveOrder));
            log.info("3. Создали новый отзыв");

            List<Review> newList = Optional.ofNullable(orderDetails.getReviews()).orElse(new ArrayList<>());
            newList.add(review);
            orderDetails.setReviews(newList);

            recalculateOrderAndDetails(orderDetails);
            log.info("4. Пересчитали детали и заказ");

            saveCompany.setCounterNoPay(saveCompany.getCounterNoPay() + 1);
            companyService.save(saveCompany);
            log.info("5. Обновили компанию");

            return true;
        } catch (Exception e) {
            log.error("Ошибка при создании нового отзыва", e);
            return false;
        }
    }

    @Override
    @Transactional
    public boolean deleteNewReview(Long orderId, Long reviewId) {
        try {
            Order saveOrder = orderRepository.findById(orderId)
                    .orElseThrow(() -> new EntityNotFoundException(String.format("Заказ '%d' не найден", orderId)));

            OrderDetails orderDetails = getFirstDetail(saveOrder);
            if (orderDetails == null) {
                log.error("У заказа {} отсутствуют детали заказа", orderId);
                return false;
            }

            Company saveCompany = saveOrder.getCompany();
            if (saveCompany == null) {
                log.error("У заказа {} отсутствует компания", orderId);
                return false;
            }

            log.info("1. Найден заказ и его детали");

            List<Review> newList = Optional.ofNullable(orderDetails.getReviews()).orElse(new ArrayList<>());
            Review review = reviewService.getReviewById(reviewId);
            if (review == null) {
                log.warn("Отзыв с ID '{}' не найден", reviewId);
                return false;
            }

            newList.remove(review);
            orderDetails.setReviews(newList);

            recalculateOrderAndDetails(orderDetails);
            log.info("2. Пересчитали детали и заказ");

            reviewService.deleteReview(reviewId);
            log.info("3. Удалили отзыв");

            saveCompany.setCounterNoPay(Math.max(0, saveCompany.getCounterNoPay() - 1));
            companyService.save(saveCompany);
            log.info("4. Обновили компанию");

            return true;
        } catch (Exception e) {
            log.error("Ошибка при удалении отзыва", e);
            return false;
        }
    }

    private void recalculateOrderAndDetails(OrderDetails orderDetails) {
        if (orderDetails == null) {
            return;
        }

        List<Review> reviews = Optional.ofNullable(orderDetails.getReviews()).orElse(Collections.emptyList());

        BigDecimal detailTotal = reviews.stream()
                .map(Review::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        orderDetails.setPrice(detailTotal);
        orderDetails.setAmount(reviews.size());
        orderDetailsService.save(orderDetails);

        Order order = orderDetails.getOrder();
        if (order != null) {
            order.setSum(detailTotal);
            order.setAmount(orderDetails.getAmount());
            orderDetailsService.saveOrder(order);
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
        if (order == null || orderDetails == null) {
            return;
        }

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

    private int calculateCounterNoPayValue(Order order, Company company) {
        if (order == null || company == null) {
            return 0;
        }
        return company.getCounterNoPay() + (order.getAmount() - company.getCounterNoPay());
    }

    // =========================================================================================================
    // ======================================== ЗАКАЗ UPDATE ====================================================
    // =========================================================================================================

    @Override
    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа");

        Order saveOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));

        log.info("Достали Заказ");
        boolean isChanged = false;

        Filial currentFilial = saveOrder.getFilial();
        Worker currentWorker = saveOrder.getWorker();
        Manager currentManager = saveOrder.getManager();
        OrderDetails firstDetail = getFirstDetail(saveOrder);
        Review firstReview = getFirstReview(saveOrder);
        Company company = saveOrder.getCompany();

        if (orderDTO.getFilial() != null && currentFilial != null &&
                !Objects.equals(orderDTO.getFilial().getId(), currentFilial.getId())) {
            log.info("Обновляем филиал заказа");

            Filial newFilial = convertFilialDTOToFilial(orderDTO.getFilial());
            saveOrder.setFilial(newFilial);

            List<Review> reviews = getAllReviews(saveOrder);
            for (Review review : reviews) {
                review.setFilial(newFilial);
                reviewService.save(review);
                log.info("Сменили филиал у отзыва в заказе");
            }

            isChanged = true;
        }

        try {
            Long dtoWorkerId = orderDTO.getWorker() != null ? orderDTO.getWorker().getWorkerId() : null;
            Long currentWorkerId = currentWorker != null ? currentWorker.getId() : null;
            Long firstReviewWorkerId = firstReview != null && firstReview.getWorker() != null
                    ? firstReview.getWorker().getId()
                    : null;

            if (!Objects.equals(dtoWorkerId, currentWorkerId) ||
                    (firstReview != null && !Objects.equals(dtoWorkerId, firstReviewWorkerId))) {

                log.info("Обновляем работника заказа");
                Worker newWorker = convertWorkerDTOToWorker(orderDTO.getWorker());
                saveOrder.setWorker(newWorker);

                for (OrderDetails detail : Optional.ofNullable(saveOrder.getDetails()).orElse(Collections.emptyList())) {
                    for (Review review : Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList())) {
                        review.setWorker(newWorker);
                    }
                }

                isChanged = true;
            }
        } catch (Exception e) {
            log.error("Ошибка при обновлении работника заказа: ", e);
        }

        if (orderDTO.getManager() != null && currentManager != null &&
                !Objects.equals(orderDTO.getManager().getManagerId(), currentManager.getId())) {
            log.info("Обновляем менеджера заказа");
            saveOrder.setManager(convertManagerDTOToManager(orderDTO.getManager()));
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())) {
            log.info("Обновляем статус выполнения Заказа");
            saveOrder.setComplete(orderDTO.isComplete());
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.getOrderComments(), saveOrder.getZametka())) {
            log.info("Обновляем комментарий заказа");
            saveOrder.setZametka(orderDTO.getOrderComments());
            isChanged = true;
        }

        if (company != null && !Objects.equals(orderDTO.getCommentsCompany(), company.getCommentsCompany())) {
            log.info("Обновляем комментарий компании");
            company.setCommentsCompany(orderDTO.getCommentsCompany());
            isChanged = true;
        }

        if (orderDTO.getCounter() != null && !Objects.equals(orderDTO.getCounter(), saveOrder.getCounter())) {
            log.info("Обновляем счетчик опубликованных текстов в заказе");
            saveOrder.setCounter(orderDTO.getCounter());
            isChanged = true;
        }

        if (isChanged) {
            log.info("3. Начали сохранять обновленный Заказ в БД");
            orderRepository.save(saveOrder);
            log.info("4. Сохранили обновленный Заказ в БД");
        } else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    }

    @Override
    @Transactional
    public void updateOrderToWorker(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа Для работника");

        Order saveOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));

        log.info("Достали Заказ");
        boolean isChanged = false;

        Company company = saveOrder.getCompany();
        if (company != null && !Objects.equals(orderDTO.getCommentsCompany(), company.getCommentsCompany())) {
            log.info("Обновляем комментарий компании");
            company.setCommentsCompany(orderDTO.getCommentsCompany());
            isChanged = true;
        }

        if (isChanged) {
            log.info("3. Начали сохранять обновленный Заказ в БД");
            orderRepository.save(saveOrder);
            log.info("4. Сохранили обновленный Заказ в БД");
        } else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    }

    // =========================================================================================================
    // ======================================== УДАЛЕНИЕ ЗАКАЗА =================================================
    // =========================================================================================================

    @Override
    @Transactional
    public boolean deleteOrder(Long orderId, Principal principal) {
        String userRole = getRole(principal);
        String username = principal != null ? principal.getName() : "unknown";

        log.info("Начало удаления заказа ID: {}, инициатор: {}, роль: {}", orderId, username, userRole);

        Order orderToDelete = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Заказ ID: {} не найден", orderId);
                    return new UsernameNotFoundException(String.format("Order '%d' not found", orderId));
                });

        if (canDeleteOrder(userRole, orderToDelete)) {
            try {
                List<OrderDetails> orderDetails = orderDetailsService.findByOrderId(orderId);
                log.info("Найдено {} деталей заказа для удаления", orderDetails.size());

                int totalDeletedReviews = 0;

                for (OrderDetails detail : orderDetails) {
                    List<Review> reviews = Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList());

                    if (!reviews.isEmpty()) {
                        List<Long> reviewIds = reviews.stream()
                                .map(Review::getId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                        log.info("Удаление отзывов для детали заказа ID: {}. Найдено отзывов: {}", detail.getId(), reviewIds.size());

                        if (!reviewIds.isEmpty()) {
                            reviewService.deleteAllByIdIn(reviewIds);
                            log.info("Успешно удалено {} отзывов для детали заказа ID: {}", reviewIds.size(), detail.getId());
                            totalDeletedReviews += reviewIds.size();
                        }
                    }
                }

                log.info("Всего удалено отзывов: {}", totalDeletedReviews);

                log.info("Удаление всех деталей заказа ID: {}", orderId);
                orderDetailsService.deleteAllByOrderId(orderId);
                log.info("Успешно удалено {} деталей заказа", orderDetails.size());

                log.info("Удаление заказа ID: {}", orderId);
                orderRepository.delete(orderToDelete);
                log.info("Заказ ID: {} успешно удален", orderId);

                log.info("Успешное завершение удаления заказа ID: {}. Удалено: заказ, {} деталей, {} отзывов",
                        orderId, orderDetails.size(), totalDeletedReviews);

                return true;

            } catch (Exception e) {
                log.error("Ошибка при удалении заказа ID: {}. Причина: {}", orderId, e.getMessage(), e);
                throw e;
            }
        }

        log.warn("Заказ ID: {} не удален. Недостаточно прав или некорректный статус. Роль пользователя: {}, статус заказа: {}",
                orderId, userRole, safeStatusTitle(orderToDelete));

        return false;
    }

    private boolean isAdminOrOwner(String role) {
        return ADMIN.equals(role) || OWNER.equals(role);
    }

    private boolean isNewlyCreatedOrder(Order order) {
        return STATUS_NEW.equals(safeStatusTitle(order));
    }

    private boolean canDeleteOrder(String role, Order orderToDelete) {
        return isAdminOrOwner(role) || (MANAGER.equals(role) && isNewlyCreatedOrder(orderToDelete));
    }

    private String getRole(Principal principal) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "";
        }

        Object authPrincipal = authentication.getPrincipal();
        if (authPrincipal instanceof UserDetails userDetails) {
            return userDetails.getAuthorities().stream()
                    .findFirst()
                    .map(Object::toString)
                    .orElse("");
        }

        return authentication.getAuthorities().stream()
                .findFirst()
                .map(Object::toString)
                .orElse("");
    }

    // =========================================================================================================
    // ======================================== СЧЕТЧИКИ ========================================================
    // =========================================================================================================

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
        if (review == null) {
            return null;
        }
        review.setWorker(newWorker);
        return reviewService.save(review);
    }

    // =========================================================================================================
    // ======================================== СМЕНА СТАТУСА ЗАКАЗА ============================================
    // =========================================================================================================

    @Override
    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title) throws Exception {
        try {
            Order order = orderRepository.findById(orderID)
                    .orElseThrow(() -> new NotFoundException("Order not found for orderID: " + orderID));

            return switch (title) {
                case STATUS_PAYMENT -> orderTransactionService.handlePaymentStatus(order);
                case STATUS_ARCHIVE -> handleArchiveStatus(order);
                case STATUS_TO_CHECK -> handleToCheckStatus(order);
                case STATUS_CORRECTION -> handleCorrectionStatus(order);
                case STATUS_PUBLIC -> handlePublicStatus(order);
                case STATUS_TO_PUBLISH -> handleToPublicStatus(order);
                default -> {
                    order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                    orderRepository.save(order);
                    yield true;
                }
            };

        } catch (Exception e) {
            log.error("При смене статуса произошли какие-то проблемы", e);
            throw e;
        }
    }

    private boolean handleToPublicStatus(Order order) {
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            String previousOrderStatus = safeStatusTitle(order);

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_PUBLISH));
            autoManageCompanyStatus(order, STATUS_TO_PUBLISH);
            assignBotsIfNeeded(order);

            List<Review> reviews = getAllReviews(order);
            if (reviews.isEmpty()) {
                log.warn("В заказе ID {} нет отзывов", order.getId());
            } else {
                botAssignmentService.checkAndNotifyAboutStubBots(reviews);
            }

            orderRepository.save(order);

            log.info("=== УСПЕШНЫЙ ПЕРЕВОД ЗАКАЗА ===");
            if (STATUS_ARCHIVE.equals(previousOrderStatus)) {
                log.info("Заказ ID {} переведен в статус 'К публикации' ИЗ АРХИВА", order.getId());

                if (hasWorkerWithTelegram(order)) {
                    String companyTitle = order.getCompany().getTitle();
                    telegramService.sendMessage(
                            order.getWorker().getUser().getTelegramChatId(),
                            companyTitle + ". Новый заказ из Архива. " +
                                    "\n https://o-ogo.ru/worker/new_orders"
                    );
                }
            } else {
                log.info("Заказ ID {} переведен в статус 'К публикации'", order.getId());
            }

            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'К публикации'", e);
        }
    }

    private boolean handleArchiveStatus(Order order) {
        log.info("=== АРХИВАЦИЯ ЗАКАЗА ID: {} ===", order.getId());

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_ARCHIVE));
        autoManageCompanyStatus(order, STATUS_ARCHIVE);

        List<Review> reviews = getAllReviews(order);
        if (!reviews.isEmpty()) {
            log.info("Отвязываем ботов от {} отзывов", reviews.size());
            for (Review review : reviews) {
                if (review.getBot() != null) {
                    log.debug("Отвязываем бота ID: {} от отзыва ID: {}",
                            review.getBot().getId(), review.getId());
                }
                review.setBot(null);
                reviewService.save(review);
            }
        }

        orderRepository.save(order);

        log.info("=== ЗАКАЗ ID {} УСПЕШНО АРХИВИРОВАН ===", order.getId());
        return true;
    }

    private boolean handleToCheckStatus(Order order) {
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'НА ПРОВЕРКУ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            assignBotsIfNeeded(order);
            autoManageCompanyStatus(order, STATUS_TO_CHECK);

            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;

            OrderDetails firstDetail = getFirstDetail(order);
            if (firstDetail == null) {
                log.warn("У заказа {} нет OrderDetails. Статус выставим без ссылки на проверку", order.getId());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                return true;
            }

            String message = order.getCompany().getTitle() + ". " + safeFilialTitle(order) + "\n\n" +
                    textService.findById(5) + "\n\n" +
                    "Ссылка на проверку отзывов: https://o-ogo.ru/review/editReviews/" + firstDetail.getId();

            if (!hasText(groupId)) {
                log.warn("⚠️ У компании {} отсутствует groupId. Статус выставлен без отправки сообщений",
                        order.getCompany().getTitle());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                log.info("✅ Заказ ID {} переведен в статус 'На проверку' (без отправки WhatsApp)", order.getId());
                return true;
            }

            log.info("Отправляем сообщение в WhatsApp для заказа ID: {}", order.getId());
            boolean result = sentMessageToGroup(STATUS_TO_CHECK, order, clientId, groupId, message, STATUS_IN_CHECK);

            if (result) {
                log.info("✅ Заказ ID {} переведен в статус 'На проверку' (сообщение отправлено)", order.getId());
            } else {
                log.error("❌ Ошибка при отправке сообщения для заказа ID: {}", order.getId());
            }

            return result;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'НА ПРОВЕРКУ' ===", e);
            try {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                log.warn("Статус заказа ID {} изменен на 'На проверку' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
        }
    }

    private boolean handleCorrectionStatus(Order order) {
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            assignBotsIfNeeded(order);
            autoManageCompanyStatus(order, STATUS_CORRECTION);

            if (hasWorkerWithTelegram(order)) {
                String companyTitle = order.getCompany().getTitle();
                String comments = order.getCompany().getCommentsCompany();
                telegramService.sendMessage(
                        order.getWorker().getUser().getTelegramChatId(),
                        companyTitle + " отправлен в Коррекцию - " + safeString(order.getZametka()) + " " + safeString(comments) +
                                "\n https://o-ogo.ru/worker/correct"
                );
                log.info("Уведомление о коррекции отправлено в Telegram");
            }

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
            orderRepository.save(order);

            log.info("✅ Заказ ID {} переведен в статус 'Коррекция'", order.getId());
            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===", e);
            try {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
                orderRepository.save(order);
                log.warn("Статус заказа ID {} изменен на 'Коррекция' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
        }
    }

    private boolean handlePublicStatus(Order order) {
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===");

            assignBotsIfNeeded(order);
            autoManageCompanyStatus(order, STATUS_PUBLIC);

            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;

            String message = order.getCompany().getTitle() + ". " + safeFilialTitle(order) + "\n\n" +
                    "Здравствуйте, ваш заказ выполнен, просьба оплатить. АЛЬФА-БАНК по счету " +
                    "https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR получатель: Сивохин И.И. " +
                    "ПРИШЛИТЕ ЧЕК, пожалуйста, как оплатите) К оплате: " + order.getSum() + " руб.";

            if (!hasText(groupId)) {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
                orderRepository.save(order);
                log.info("✅ Статус заказа {} установлен в '{}' без отправки в WhatsApp (отсутствует groupId)",
                        order.getId(), STATUS_PUBLIC);
                return true;
            }

            return sentMessageToGroup(STATUS_PUBLIC, order, clientId, groupId, message, STATUS_TO_PAY);

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'Публикация'", e);
        }
    }

    private void assignBotsIfNeeded(Order order) {
        try {
            if (!hasDetails(order)) {
                log.warn("У заказа ID {} нет OrderDetails", order != null ? order.getId() : null);
                return;
            }

            List<Review> reviews = getAllReviews(order);
            if (reviews.isEmpty()) {
                return;
            }

            long nullBotCount = reviews.stream()
                    .filter(review -> review.getBot() == null)
                    .count();

            if (nullBotCount > 0) {
                log.info("Найдено {} отзывов без ботов в заказе ID {}, назначаем...",
                        nullBotCount, order.getId());

                boolean botsAssigned = botAssignmentService.assignBotsToExistingReviews(
                        reviews, order.getFilial());

                if (botsAssigned) {
                    log.info("Боты успешно назначены для {} отзывов", nullBotCount);
                } else {
                    log.warn("Не удалось назначить боты для отзывов");
                }
            }

            botAssignmentService.checkAndNotifyAboutStubBots(reviews);

        } catch (Exception e) {
            log.error("Ошибка при проверке/назначении ботов: {}", e.getMessage(), e);
        }
    }

    private void autoManageCompanyStatus(Order changedOrder, String newOrderStatus) {
        try {
            log.info("🚀 === НАЧАЛО АВТОМАТИЧЕСКОГО УПРАВЛЕНИЯ СТАТУСОМ КОМПАНИИ ===");
            log.info("📦 Заказ ID: {} меняет статус на: {}", changedOrder.getId(), newOrderStatus);

            Company company = changedOrder.getCompany();
            if (company == null) {
                log.error("❌ Компания не найдена для заказа ID: {}", changedOrder.getId());
                return;
            }

            String currentCompanyStatus = company.getStatus() != null ? company.getStatus().getTitle() : "";
            log.info("🏢 Компания ID: {}, текущий статус: {}", company.getId(), currentCompanyStatus);

            boolean hasOtherActiveOrders = hasOtherActiveUnpaidOrders(company, changedOrder);
            log.info("🔍 Есть другие активные заказы: {}", hasOtherActiveOrders);

            if (STATUS_ARCHIVE.equals(newOrderStatus)) {
                if (!hasOtherActiveOrders) {
                    log.info("📌 ПРАВИЛО 1: Архивация заказа. Нет других активных заказов -> компания в 'Стоп'");
                    company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_STOP));
                    companyService.save(company);
                    log.info("✅ Статус компании изменен на: {}", company.getStatus().getTitle());
                } else {
                    log.info("📌 ПРАВИЛО 1: Архивация заказа. Есть другие активные заказы -> статус компании не меняем");
                }
            } else if (isActiveOrderStatus(newOrderStatus)) {
                if (STATUS_COMPANY_IN_STOP.equals(currentCompanyStatus) && !hasOtherActiveOrders) {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Компания в 'Стопе' и нет других активных заказов -> 'В работе'");
                    company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
                    companyService.save(company);
                    log.info("✅ Статус компании изменен на: {}", company.getStatus().getTitle());
                } else if (STATUS_COMPANY_IN_STOP.equals(currentCompanyStatus) && hasOtherActiveOrders) {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Компания в 'Стопе', но есть другие активные заказы -> оставляем 'Стоп'");
                } else {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Статус компании не требует изменений");
                }
            } else {
                log.info("📌 Статус заказа '{}' не влияет на статус компании", newOrderStatus);
            }

        } catch (Exception e) {
            log.error("🔥 ОШИБКА в autoManageCompanyStatus: {}", e.getMessage(), e);
        }
    }

    private boolean hasOtherActiveUnpaidOrders(Company company, Order currentOrder) {
        try {
            Collection<Order> companyOrders = company.getOrderList();
            if (companyOrders == null || companyOrders.isEmpty()) {
                log.info("У компании ID {} нет других заказов", company.getId());
                return false;
            }

            long otherActiveOrdersCount = companyOrders.stream()
                    .filter(order -> order != null && !Objects.equals(order.getId(), currentOrder.getId()))
                    .filter(order -> {
                        String orderStatus = safeStatusTitle(order);
                        boolean isActive = !STATUS_PAYMENT.equalsIgnoreCase(orderStatus)
                                && !STATUS_ARCHIVE.equalsIgnoreCase(orderStatus);
                        if (isActive) {
                            log.debug("Найден активный неоплаченный заказ ID: {}, статус: {}", order.getId(), orderStatus);
                        }
                        return isActive;
                    })
                    .count();

            log.info("У компании ID {} найдено {} других активных неоплаченных заказов (кроме заказа ID {})",
                    company.getId(), otherActiveOrdersCount, currentOrder.getId());

            return otherActiveOrdersCount > 0;

        } catch (Exception e) {
            log.error("Ошибка при проверке других активных заказов: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean isActiveOrderStatus(String status) {
        Set<String> activeStatuses = Set.of(
                STATUS_TO_PUBLISH,
                STATUS_PUBLIC,
                STATUS_TO_PAY,
                STATUS_TO_CHECK,
                STATUS_CORRECTION,
                STATUS_IN_CHECK,
                STATUS_NEW
        );
        return activeStatuses.contains(status);
    }

    private boolean sentMessageToGroup(String title, Order order, String clientId, String groupId, String message, String successStatus) {
        log.info("📨 Отправка сообщения в WhatsApp-группу:");
        log.info("🔹 Клиент: {}", clientId);
        log.info("🔹 Группа: {}", groupId);
        log.info("🔹 Сообщение: {}", message.replaceAll("\\s+", " ").trim());

        String result = whatsAppService.sendMessageToGroup(clientId, groupId, message);

        if (result != null && result.toLowerCase().contains("ok")) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(successStatus));
            log.info("✅ Статус заказа успешно обновлён на: {}", successStatus);
        } else {
            log.warn("⚠️ Сообщение в WhatsApp-группу не прошло: {}", result);

            String companyTitle = order.getCompany() != null ? order.getCompany().getTitle() : "Компания";
            String managerChatId = order.getManager() != null && order.getManager().getUser() != null
                    && order.getManager().getUser().getTelegramChatId() != null
                    ? String.valueOf(order.getManager().getUser().getTelegramChatId())
                    : null;

            if (STATUS_TO_CHECK.equals(title) && hasManagerWithTelegram(order) && hasText(managerChatId)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=В%20проверку";
                String text = companyTitle + " готов - На проверку\n" + url;
                telegramService.sendMessage(Long.parseLong(managerChatId), text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → В проверку", managerChatId);
            }

            if (STATUS_PUBLIC.equals(title) && hasManagerWithTelegram(order) && hasText(managerChatId)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
                String text = companyTitle + " Опубликован\n" + url;
                telegramService.sendMessage(Long.parseLong(managerChatId), text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → Опубликовано", managerChatId);
            }

            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
            log.info("🔄 Статус заказа установлен вручную: {}", title);
        }

        orderRepository.save(order);
        log.info("💾 Заказ сохранён: ID {}. Компания - {}", order.getId(),
                order.getCompany() != null ? order.getCompany().getTitle() : "null");

        return true;
    }

    private boolean hasManagerWithTelegram(Order order) {
        try {
            return order != null
                    && order.getManager() != null
                    && order.getManager().getUser() != null
                    && order.getManager().getUser().getTelegramChatId() != null
                    && hasDetails(order)
                    && order.getCompany() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasWorkerWithTelegram(Order order) {
        try {
            return order != null
                    && order.getWorker() != null
                    && order.getWorker().getUser() != null
                    && order.getWorker().getUser().getTelegramChatId() != null
                    && hasDetails(order)
                    && order.getCompany() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    protected void saveReviewsToArchive(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }

        for (Review review : reviews) {
            if (review != null && review.getId() != null) {
                reviewArchiveService.saveNewReviewArchive(review.getId());
            }
        }
    }

    @Override
    @Transactional
    public Company checkStatusToCompany(Company company) {
        if (company == null) {
            return null;
        }

        int result = 0;
        Collection<Order> orders = company.getOrderList();
        if (orders != null) {
            for (Order order : orders) {
                if (order != null && !order.isComplete()) {
                    result = 1;
                    break;
                }
            }
        }

        if (result == 0) {
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        }

        return company;
    }

    @Override
    @Transactional
    public boolean changeStatusAndOrderCounter(Long reviewId) throws Exception {
        try {
            Review review = reviewService.getReviewById(reviewId);
            Order order = validateAndRetrieveOrder(review, reviewId);

            log.info("Достали отзыв id={} для компании: {}", reviewId,
                    order.getCompany() != null ? order.getCompany().getTitle() : "null");

            if (review.getBot() != null) {
                updateBotCounterAndStatus(review.getBot());
                log.info("Увеличили кол-во публикаций у бота");
            } else {
                log.warn("У отзыва id={} нет бота, счетчик бота не обновлялся", reviewId);
            }

            review.setPublish(true);
            reviewService.save(review);
            log.info("Сохранили отзыв, публикация установлена в true");

            order.setCounter(order.getCounter() + 1);
            orderRepository.save(order);
            log.info("Обновили счётчик заказа: {}", order.getCounter());

            int actualPublished = countPublishedReviews(order);
            log.info("Фактическое количество опубликованных отзывов: {}", actualPublished);

            orderStatusCheckerService.validateCounterConsistency(order, actualPublished);
            orderStatusCheckerService.checkAndMarkOrderCompleted(order);

            return true;
        } catch (Exception e) {
            log.error("Ошибка при смене статуса отзыва id={}", reviewId, e);
            throw e;
        }
    }

    private Order validateAndRetrieveOrder(Review review, Long reviewId) {
        if (review == null) {
            throw new IllegalStateException("Отзыв не найден: id=" + reviewId);
        }

        OrderDetails details = review.getOrderDetails();
        if (details == null || details.getOrder() == null) {
            throw new IllegalStateException("OrderDetails или Order отсутствуют у отзыва id=" + reviewId);
        }

        Order order = orderRepository.findById(details.getOrder().getId()).orElse(null);
        if (order == null || review.isPublish()) {
            throw new IllegalStateException("Заказ не найден или отзыв уже опубликован. id=" + reviewId);
        }

        return order;
    }

    protected int countPublishedReviews(Order order) {
        return (int) getAllReviews(order).stream()
                .filter(Review::isPublish)
                .count();
    }

    public void updateBotCounterAndStatus(Bot bot) {
        try {
            if (bot == null) {
                return;
            }

            int currentCounter = bot.getCounter();
            bot.setCounter(currentCounter + 1);

            if (bot.getCounter() >= HIGH_COUNTER_THRESHOLD) {
                bot.setStatus(botService.changeStatus(HIGH_STATUS));
                bot.setActive(false);
            } else if (bot.getCounter() >= MEDIUM_COUNTER_THRESHOLD) {
                bot.setStatus(botService.changeStatus(MEDIUM_STATUS));
                bot.setActive(false);
            }

            botService.save(bot);
        } catch (Exception e) {
            log.error("Ошибка при обновлении бота id={}", bot != null ? bot.getId() : null, e);
            throw e;
        }
    }

    @Override
    public int countOrdersByWorkerAndStatus(Worker worker, String status) {
        return orderRepository.countByWorkerAndStatus(worker, status);
    }

    // =========================================================================================================
    // ======================================== АГРЕГАЦИИ =======================================================
    // =========================================================================================================

    @Override
    public Map<String, Pair<Long, Long>> getNewOrderAll(String statusNew, String statusCorrect) {
        List<Object[]> results = orderRepository.findAllIdByNewOrderAllStatus(statusNew, statusCorrect);

        Map<String, Pair<Long, Long>> workerStats = new HashMap<>();
        Map<String, Pair<Long, Long>> managerStats = new HashMap<>();

        for (Object[] row : results) {
            String type = (String) row[0];
            String fio = (String) row[1];
            long newOrders = ((Number) row[2]).longValue();
            long correctOrders = ((Number) row[3]).longValue();

            if ("operator".equals(type)) {
                workerStats.put(fio, Pair.of(newOrders, correctOrders));
            } else {
                managerStats.put(fio, Pair.of(newOrders, correctOrders));
            }
        }

        Map<String, Pair<Long, Long>> combinedStats = new HashMap<>(workerStats);
        combinedStats.putAll(managerStats);

        return combinedStats;
    }

    @Override
    public Map<String, Long> getAllOrdersToMonth(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = orderRepository.getAllOrdersToMonth(status, firstDayOfMonth, lastDayOfMonth);

        Map<String, Long> workerOrders = new HashMap<>();
        Map<String, Long> managerOrders = new HashMap<>();

        for (Object[] row : results) {
            String workerFio = (String) row[0];
            Long workerOrderCount = (Long) row[1];

            String managerFio = (String) row[2];
            Long managerOrderCount = (Long) row[3];

            if (workerFio != null) {
                workerOrders.merge(workerFio, workerOrderCount != null ? workerOrderCount : 0L, Long::sum);
            }
            if (managerFio != null) {
                managerOrders.merge(managerFio, managerOrderCount != null ? managerOrderCount : 0L, Long::sum);
            }
        }

        Map<String, Long> allOrders = new HashMap<>();
        allOrders.putAll(workerOrders);
        allOrders.putAll(managerOrders);
        return allOrders;
    }

    @Override
    public Map<String, Map<String, Long>> getAllOrdersToMonthByStatus(
            LocalDate firstDayOfMonth,
            LocalDate lastDayOfMonth,
            String orderInNew,
            String orderToCheck,
            String orderInCheck,
            String orderInCorrect,
            String orderInPublished,
            String orderInWaitingPay1,
            String orderInWaitingPay2,
            String orderNoPay
    ) {
        List<String> statuses = List.of(
                orderInNew,
                orderToCheck,
                orderInCheck,
                orderInCorrect,
                orderInPublished,
                orderInWaitingPay1,
                orderInWaitingPay2,
                orderNoPay
        );

        List<Object[]> results = orderRepository.getOrdersByStatusForUsers(
                statuses,
                firstDayOfMonth.minusMonths(2),
                lastDayOfMonth
        );

        Map<String, Map<String, Long>> ordersMap = new LinkedHashMap<>();
        Map<String, Map<String, Long>> managerOrders = new LinkedHashMap<>();
        Map<String, Map<String, Long>> workerOrders = new LinkedHashMap<>();

        for (Object[] row : results) {
            if (row.length < 4) {
                continue;
            }

            String fio = (String) row[0];
            String status = (String) row[1];
            Long count = row[2] != null ? (Long) row[2] : 0L;
            String role = (String) row[3];

            if ("manager".equals(role)) {
                managerOrders.computeIfAbsent(fio, k -> new LinkedHashMap<>()).put(status, count);
            } else {
                workerOrders.computeIfAbsent(fio, k -> new LinkedHashMap<>()).put(status, count);
            }
        }

        ordersMap.putAll(managerOrders);
        ordersMap.putAll(workerOrders);

        return ordersMap;
    }

    @Override
    public void save(Order order) {
        orderRepository.save(order);
    }

    // =========================================================================================================
    // ======================================== CONVERTER DTO ===================================================
    // =========================================================================================================

    private List<OrderDTOList> toOrderDTOList(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }

        return orders.stream()
                .map(this::toDTOListOrders)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private OrderDTOList toDTOListOrders(Order order) {
        if (order == null) {
            return null;
        }

        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged() != null ? order.getChanged() : now;
        long daysDifference = ChronoUnit.DAYS.between(changedDate, now);

        OrderDetails firstDetail = getFirstDetail(order);

        return OrderDTOList.builder()
                .id(order.getId())
                .companyId(order.getCompany() != null ? order.getCompany().getId() : null)
                .orderDetailsId(firstDetail != null ? firstDetail.getId() : null)
                .companyTitle(order.getCompany() != null ? order.getCompany().getTitle() : "Без компании")
                .companyComments(order.getCompany() != null ? safeString(order.getCompany().getCommentsCompany()) : "")
                .filialTitle(order.getFilial() != null ? safeString(order.getFilial().getTitle()) : "Без филиала")
                .filialUrl(order.getFilial() != null ? safeString(order.getFilial().getUrl()) : "")
                .status(safeStatusTitle(order))
                .sum(order.getSum())
                .companyUrlChat(order.getCompany() != null ? safeString(order.getCompany().getUrlChat()) : "")
                .companyTelephone(order.getCompany() != null ? safeString(order.getCompany().getTelephone()) : "")
                .managerPayText(order.getManager() != null ? safeString(order.getManager().getPayText()) : "")
                .amount(order.getAmount())
                .counter(order.getCounter())
                .workerUserFio(order.getWorker() != null && order.getWorker().getUser() != null
                        ? safeString(order.getWorker().getUser().getFio())
                        : "")
                .categoryTitle(order.getCompany() != null && order.getCompany().getCategoryCompany() != null
                        ? safeString(order.getCompany().getCategoryCompany().getCategoryTitle())
                        : "Не выбрано")
                .subCategoryTitle(order.getCompany() != null && order.getCompany().getSubCategory() != null
                        ? safeString(order.getCompany().getSubCategory().getSubCategoryTitle())
                        : "Не выбрано")
                .created(order.getCreated())
                .changed(order.getChanged())
                .payDay(order.getPayDay())
                .dayToChangeStatusAgo(daysDifference)
                .orderComments(order.getZametka() == null ? "нет заметок" : order.getZametka())
                .build();
    }

    private List<OrderDTO> convertToOrderDTOList(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }

        return orders.stream()
                .map(this::toDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private OrderDTO toDTO(Order order) {
        if (order == null) {
            return null;
        }

        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged() != null ? order.getChanged() : now;
        Period period = Period.between(changedDate, now);

        OrderDetails firstDetail = getFirstDetail(order);

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .sum(order.getSum())
                .created(order.getCreated())
                .changed(order.getChanged())
                .status(order.getStatus() != null ? convertToOrderDTO(order.getStatus()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .commentsCompany(order.getCompany() != null ? safeString(order.getCompany().getCommentsCompany()) : "")
                .filial(order.getFilial() != null ? convertToFilialDTO(order.getFilial()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .details(convertToDetailsDTOList(order.getDetails()))
                .complete(order.isComplete())
                .counter(order.getCounter())
                .dayToChangeStatusAgo(period.getDays())
                .orderDetailsId(firstDetail != null ? firstDetail.getId() : null)
                .orderComments(order.getZametka() == null ? "нет заметок" : order.getZametka())
                .groupId(order.getCompany() != null ? order.getCompany().getGroupId() : null)
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company) {
        if (company == null) {
            return null;
        }

        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .urlChat(company.getUrlChat())
                .manager(company.getManager() != null ? convertToManagerDTO(company.getManager()) : null)
                .workers(company.getWorkers() != null ? convertToWorkerDTOList(company.getWorkers()) : Collections.emptySet())
                .filials(company.getFilial() != null ? convertToFilialDTOList(company.getFilial()) : Collections.emptySet())
                .categoryCompany(company.getCategoryCompany() != null ? convertToCategoryDto(company.getCategoryCompany()) : null)
                .subCategory(company.getSubCategory() != null ? convertToSubCategoryDto(company.getSubCategory()) : null)
                .groupId(company.getGroupId())
                .build();
    }

    private CategoryDTO convertToCategoryDto(Category category) {
        if (category == null) {
            return null;
        }

        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    }

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        if (subCategory == null) {
            return null;
        }

        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0L);
        subCategoryDTO.setSubCategoryTitle(
                subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не выбрано"
        );
        return subCategoryDTO;
    }

    private ManagerDTO convertToManagerDTO(Manager manager) {
        if (manager == null) {
            return null;
        }

        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .clientId(manager.getClientId())
                .build();
    }

    private OrderStatusDTO convertToOrderDTO(OrderStatus orderStatus) {
        if (orderStatus == null) {
            return null;
        }

        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    }

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return Collections.emptySet();
        }

        return workers.stream()
                .map(this::convertToWorkerDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private WorkerDTO convertToWorkerDTO(Worker worker) {
        if (worker == null) {
            return null;
        }

        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials) {
        if (filials == null || filials.isEmpty()) {
            return Collections.emptySet();
        }

        return filials.stream()
                .map(this::convertToFilialDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private FilialDTO convertToFilialDTO(Filial filial) {
        if (filial == null) {
            return null;
        }

        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details) {
        if (details == null || details.isEmpty()) {
            return Collections.emptyList();
        }

        return details.stream()
                .map(this::convertToDetailsDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails) {
        if (orderDetails == null) {
            return null;
        }

        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(orderDetails.getProduct() != null ? convertToProductDTO(orderDetails.getProduct()) : null)
                .order(orderDetails.getOrder() != null ? convertToOrderDTO(orderDetails.getOrder()) : null)
                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product) {
        if (product == null) {
            return null;
        }

        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }

    private OrderDTO convertToOrderDTO(Order order) {
        if (order == null) {
            return null;
        }

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .groupId(order.getCompany() != null ? order.getCompany().getGroupId() : null)
                .build();
    }

    @Override
    public OrderDTO convertToOrderDTOToRepeat(Order order) {
        if (order == null) {
            return null;
        }

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .filial(order.getFilial() != null ? convertToFilialDTO(order.getFilial()) : null)
                .commentsCompany(order.getCompany() != null ? order.getCompany().getCommentsCompany() : "")
                .status(convertToStatusDTO(STATUS_NEW))
                .build();
    }

    private OrderStatusDTO convertToStatusDTO(String status) {
        return OrderStatusDTO.builder()
                .title(status)
                .build();
    }

    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Collections.emptyList();
        }

        return reviews.stream()
                .map(this::convertToReviewsDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ReviewDTO convertToReviewsDTO(Review review) {
        if (review == null) {
            return null;
        }

        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .build();
    }

    // =========================================================================================================
    // ======================================== CONVERTER ENTITY =================================================
    // =========================================================================================================

    private Worker convertWorkerDTOToWorker(WorkerDTO workerDTO) {
        if (workerDTO == null || workerDTO.getWorkerId() == null) {
            return null;
        }
        return workerService.getWorkerById(workerDTO.getWorkerId());
    }

    private Company convertCompanyDTOToCompany(CompanyDTO companyDTO) {
        if (companyDTO == null || companyDTO.getId() == null) {
            return null;
        }
        return companyService.getCompaniesById(companyDTO.getId());
    }

    private Manager convertManagerDTOToManager(ManagerDTO managerDTO) {
        if (managerDTO == null || managerDTO.getManagerId() == null) {
            return null;
        }
        return managerService.getManagerById(managerDTO.getManagerId());
    }

    private OrderStatus convertStatusDTOToStatus(OrderStatusDTO orderStatusDTO) {
        if (orderStatusDTO == null || orderStatusDTO.getTitle() == null) {
            return null;
        }
        return orderStatusService.getOrderStatusByTitle(orderStatusDTO.getTitle());
    }

    private Filial convertFilialDTOToFilial(FilialDTO filialDTO) {
        if (filialDTO == null || filialDTO.getId() == null) {
            return null;
        }
        return filialService.getFilial(filialDTO.getId());
    }

    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId) {
        Product product = productService.findById(productId);

        return Order.builder()
                .amount(orderDTO.getAmount())
                .complete(false)
                .worker(convertWorkerDTOToWorker(orderDTO.getWorker()))
                .company(convertCompanyDTOToCompany(orderDTO.getCompany()))
                .manager(convertManagerDTOToManager(orderDTO.getManager()))
                .filial(convertFilialDTOToFilial(orderDTO.getFilial()))
                .sum(product != null && product.getPrice() != null
                        ? product.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount()))
                        : BigDecimal.ZERO)
                .status(convertStatusDTOToStatus(orderDTO.getStatus()))
                .build();
    }

    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId) {
        Product product = productService.findById(productId);

        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product != null && product.getPrice() != null
                        ? product.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount()))
                        : BigDecimal.ZERO)
                .order(order)
                .product(product)
                .comment("")
                .build();
    }

    private List<Review> toEntityListReviewsFromDTO(OrderDTO orderDTO, OrderDetails orderDetails) {
        List<Review> reviewList = new ArrayList<>();

        Filial filial = convertFilialDTOToFilial(orderDTO.getFilial());
        List<Bot> bots = findAllBotsMinusFilial(orderDTO, filial);

        for (int i = 0; i < orderDTO.getAmount(); i++) {
            Review review = toEntityReviewFromDTO(orderDTO.getCompany(), orderDetails, orderDTO.getFilial(), bots);
            Review saved = reviewService.save(review);
            reviewList.add(saved);
        }

        return reviewList;
    }

    private Review toEntityReviewFromDTO(CompanyDTO companyDTO, OrderDetails orderDetails, FilialDTO filialDTO, List<Bot> bots) {
        SecureRandom random = new SecureRandom();
        Product product = orderDetails != null ? orderDetails.getProduct() : null;

        Bot selectedBot = (bots != null && !bots.isEmpty())
                ? bots.get(random.nextInt(bots.size()))
                : null;

        return Review.builder()
                .category(companyDTO != null ? convertCategoryDTOToCompany(companyDTO.getCategoryCompany()) : null)
                .subCategory(companyDTO != null ? convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()) : null)
                .text("Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(selectedBot)
                .filial(convertFilialDTOToFilial(filialDTO))
                .publish(false)
                .worker(orderDetails != null && orderDetails.getOrder() != null ? orderDetails.getOrder().getWorker() : null)
                .product(product)
                .price(product != null ? product.getPrice() : null)
                .build();
    }

    private Review toEntityReviewFromDTO(
            CompanyDTO companyDTO,
            OrderDetails orderDetails,
            FilialDTO filialDTO,
            List<Bot> bots,
            String textReview
    ) {
        SecureRandom random = new SecureRandom();
        Product product = orderDetails != null ? orderDetails.getProduct() : null;

        Bot selectedBot = (bots != null && !bots.isEmpty())
                ? bots.get(random.nextInt(bots.size()))
                : null;

        return Review.builder()
                .category(companyDTO != null ? convertCategoryDTOToCompany(companyDTO.getCategoryCompany()) : null)
                .subCategory(companyDTO != null ? convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()) : null)
                .text(textReview != null ? textReview : "Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(selectedBot)
                .filial(convertFilialDTOToFilial(filialDTO))
                .publish(false)
                .worker(orderDetails != null && orderDetails.getOrder() != null ? orderDetails.getOrder().getWorker() : null)
                .product(product)
                .price(product != null ? product.getPrice() : null)
                .build();
    }

    private List<Bot> findAllBotsMinusFilial(OrderDTO orderDTO, Filial filial) {
        if (filial == null || filial.getCity() == null || filial.getCity().getId() == null) {
            return new ArrayList<>();
        }

        List<Bot> bots = botService.getFindAllByFilialCityId(filial.getCity().getId());
        if (bots == null) {
            bots = new ArrayList<>();
        }

        List<Review> reviewListFilial = reviewService.findAllByFilial(filial);
        List<Bot> botsCompany = reviewListFilial == null
                ? Collections.emptyList()
                : reviewListFilial.stream()
                .map(Review::getBot)
                .filter(Objects::nonNull)
                .toList();

        bots.removeAll(botsCompany);
        return bots;
    }

    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO) {
        if (categoryDTO == null || categoryDTO.getId() == null) {
            return null;
        }
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    }

    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO) {
        if (subCategoryDTO == null || subCategoryDTO.getId() == null) {
            return null;
        }
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    }

    // =========================================================================================================
    // ======================================== SAFE HELPERS ====================================================
    // =========================================================================================================

    private OrderDetails getFirstDetail(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return null;
        }
        return order.getDetails().get(0);
    }

    private Review getFirstReview(Order order) {
        OrderDetails firstDetail = getFirstDetail(order);
        if (firstDetail == null || firstDetail.getReviews() == null || firstDetail.getReviews().isEmpty()) {
            return null;
        }
        return firstDetail.getReviews().get(0);
    }

    private List<Review> getAllReviews(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return Collections.emptyList();
        }

        return order.getDetails().stream()
                .filter(Objects::nonNull)
                .flatMap(detail -> Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean hasDetails(Order order) {
        return order != null && order.getDetails() != null && !order.getDetails().isEmpty();
    }

    private String safeStatusTitle(Order order) {
        if (order == null || order.getStatus() == null || order.getStatus().getTitle() == null) {
            return "";
        }
        return order.getStatus().getTitle();
    }

    private String safeFilialTitle(Order order) {
        return order != null && order.getFilial() != null
                ? safeString(order.getFilial().getTitle())
                : "Без филиала";
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Page<OrderDTOList> emptyOrderPage(int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        return new PageImpl<>(Collections.emptyList(), pageable, 0);
    }

    private User resolveUserFromPrincipal(Principal principal) {
        if (principal == null) {
            return null;
        }
        return userService.findByUserName(principal.getName()).orElse(null);
    }

    private Manager resolveManagerFromPrincipal(Principal principal) {
        User user = resolveUserFromPrincipal(principal);
        if (user == null) {
            return null;
        }
        return managerService.getManagerByUserId(user.getId());
    }

    private Worker resolveWorkerFromPrincipal(Principal principal) {
        User user = resolveUserFromPrincipal(principal);
        if (user == null) {
            return null;
        }
        return workerService.getWorkerByUserId(user.getId());
    }

    private List<Manager> resolveOwnerManagersFromPrincipal(Principal principal) {
        User user = resolveUserFromPrincipal(principal);
        if (user == null || user.getManagers() == null) {
            return Collections.emptyList();
        }
        return user.getManagers().stream().toList();
    }
}


//@Transactional
//public boolean changeStatusForOrder(Long orderID, String title) throws Exception { // смена статуса для заказа с проверкой на Оплачено
//    try {
//        Order order = orderRepository.findById(orderID).orElseThrow(() -> new NotFoundException("Order  not found for orderID: " + orderID));
//        if (title.equals(STATUS_PAYMENT)){
/// /                log.info("1. Пришел запрос на перевод статуса заказа в статус Оплачено");
/// /                log.info("1. Смотрим текущий статус заказа выполнен или нет - orderIsComplete: {}", order.isComplete());
/// /                log.info("1. Проверка счетчиков order.getAmount() <= order.getCounter(): {}", Objects.equals(order.getAmount(), order.getCounter()));
//
//            if (!order.isComplete() && Objects.equals(order.getAmount(), order.getCounter())){
//                log.info("2. Проверили, что заказ еще не бьл выполнен и что счетчкики совпадают");
//                if (zpService.save(order)){
//                    log.info("3. Сохранили ЗП");
//                    boolean chek = paymentCheckService.save(order);
//                    log.info(String.valueOf(chek));
//                    log.info("4. Сохранили Чек компании");
//                    Company company = companyService.getCompaniesById(order.getCompany().getId());
//
//                    try {
//                        company.setCounterPay(company.getCounterPay() + order.getAmount());
//                        log.info("счетчик: {} - {}", company.getCounterPay(), order.getAmount());
//                        company.setSumTotal(company.getSumTotal().add(order.getSum()));
//                        log.info("сумма: {}", company.getSumTotal().add(order.getSum()));
//                        log.info("5. Успешно установили суммы");
//                        order.setComplete(true);
//                        order.setPayDay(LocalDate.now());
//                        log.info("Дата оплаты: {}", order.getPayDay());
//                        orderRepository.save(order);
//                        log.info("6. Заказ обновлен и сохранен");
//                        companyService.save(checkStatusToCompany(company));
//                        log.info("7. Компания сохранена, статус сменен на Готов к Новому заказу");
/// /                             Создание нового заказа с отзывами
//                        if (createNewOrderWithReviews(company.getId(), order.getDetails().getFirst().getProduct().getId(), convertToOrderDTOToRepeat(order))) {
//                            log.info("8. Новый заказ создался автоматически - Успешно");
//                            log.info("8. Оплата поступила, ЗП начислена Менеджеру и Работнику");
//                        }
//                        else {
//                            log.info("8. Новый заказ создался автоматически - НЕ Успешно");
//                            throw new Exception("8. Новый заказ создался автоматически - НЕ успешно");
//                        }
//                        log.info("8. Новый заказ создался автоматически - Успешно");
//                        log.info("8. Оплата поступила, ЗП начислена Менеджеру и Работнику");
//                    }
//                    catch (Exception e) {
//                        log.error("Ошибка при обновлении данных компании не успешно установлены суммы", e);
//                        throw e;
//                    }
//                }
//                else {
//                    log.error("2. Оплата поступила, но при сохранении какие-то проблемы");
//                }
//            }
//            else {
//                log.info("3. Что-то пошло не так и выбросило в момент Зачисления");
//            }
//
//            log.info("2. Проверили, что заказ УЖЕ был выполнен и просто меняем статус");
//            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
//            orderRepository.save(order);
//            return true;
//        }
//        if (order.getStatus().getTitle().equals(STATUS_ARCHIVE)){
//            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
//            order.getCompany().setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
//            log.info("Сменили статус компании на В работе");
//            orderRepository.save(order);
//        }
//        if (title.equals(STATUS_ARCHIVE)) {
//            // Меняем статус заказа
//            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
//
//            Company company = order.getCompany();
//            Set<Order> orders = company.getOrderList();
//
//            boolean hasUnpaidOrders = orders.stream()
//                    .anyMatch(o -> !o.getStatus().getTitle().equalsIgnoreCase(STATUS_PAYMENT));
//
//            if (hasUnpaidOrders) {
//                company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_STOP));
//            }
//
//            orderRepository.save(order);
//            return true;
//        }
//        else {
//            if (STATUS_TO_CHECK.equals(title)) {
//                String clientId = order.getManager().getClientId();
//                String groupId = order.getCompany().getGroupId();
//                String message = textService.findById(5) + "\n\n" +
//                        "Ссылка на проверку отзывов: https://o-ogo.ru/review/editReviews/" + order.getDetails().getFirst().getId();
//
//                return sentMessageToGroup(title, order, clientId, groupId, message, STATUS_IN_CHECK);
//            }
//
//            if (STATUS_CORRECTION.equals(title)) {
//                if (hasWorkerWithTelegram(order)) {
//
//                    String companyTitle = order.getDetails().getFirst().getOrder().getCompany().getTitle();
//                    String comments = order.getDetails().getFirst().getOrder().getCompany().getCommentsCompany();
//                    telegramService.sendMessage(order.getWorker().getUser().getTelegramChatId(),
//                            companyTitle + " отправлен в Коррекцию - " + order.getZametka() + " " + comments + "\n "
//                                    + "https://o-ogo.ru/worker/correct");
//                }
//                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
//                orderRepository.save(order);
//                return true;
//            }
//            if (STATUS_PUBLIC.equals(title)) {
//                String clientId = order.getManager().getClientId();
//                String groupId = order.getCompany().getGroupId();
//                String message = order.getCompany().getTitle() + ". " + order.getFilial().getTitle() + "\n\n" +
//                        "Здравствуйте, ваш заказ выполнен, просьба оплатить.  АЛЬФА-БАНК по счету https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR получатель: Сивохин И.И.  ПРИШЛИТЕ ЧЕК, пожалуйста, как оплатите) К оплате: " +
//                        order.getSum() + " руб.";
//
//                return sentMessageToGroup(title, order, clientId, groupId, message, STATUS_TO_PAY);
//            }
//
//
////                order.setStatus(orderStatusService.getOrderStatusByTitle(title));
////                orderRepository.save(order);
////                return true;
//        }
//    } catch (Exception e) {
//        log.error("При смене статуса произошли какие-то проблемы", e);
//        throw e; // Исключение пробрасывается для отката транзакции
//    }
//} // смена статуса для заказа с проверкой на Оплачено
//


//@Override
//@Transactional
//public boolean changeStatusAndOrderCounter(Long reviewId) throws Exception {
//    boolean isChanged = false;
//    try {
//        Review review = reviewService.getReviewById(reviewId);
//        Order order = validateAndRetrieveOrder(review, reviewId);
//        log.info("2. Достали отзыв по id {} для компании: {}", reviewId, review.getOrderDetails().getOrder().getCompany().getTitle());
//        log.info("3. Заказ найден, и отзыв еще не опубликован. Продолжаем выполнение.");
//
////            reviewArchiveService.saveNewReviewArchive(reviewId);
//        log.info("4. Сохранили отзыв в архив");
//
//        updateBotCounterAndStatus(review.getBot());
//        log.info("5. Увеличили кол-во публикаций у бота");
//
//        review.setPublish(true);
//        log.info("6. Установили статус публикации отзыва на true");
//
//        reviewService.save(review);
//        log.info("7. Сохранили отзыв в базе данных");
//
//        order.setCounter(order.getCounter() + 1);
//        Order savedOrder = orderRepository.save(order);
//        log.info("8. Обновили счетчик публикаций заказа. Новый счетчик: {}", savedOrder.getCounter());
//
//        int reviewCounter = counterReviewIsPublish(savedOrder);
//
//        log.info("9. reviewCounter: {}", reviewCounter);
//
//        if (savedOrder.getCounter() != reviewCounter){
//            String textMail = "Компания: " + savedOrder.getCompany().getTitle() + ". Заказ № " + savedOrder.getId() + ". Работник " + savedOrder.getWorker().getUser().getFio();
//            log.info("9. !!!!!!!!!!! ЧТО-ТО НЕ ТАК !!!!!! Проверка savedOrder.getCounter() != reviewCounter не пройдена: savedOrder.getCounter() = {},  reviewCounter = {}", savedOrder.getCounter(), reviewCounter);
//            emailService.sendSimpleEmail("2.12nps@mail.ru", "Ошибка проверки счетчика", "Срочно проверь. Что-то пошло не так при нажатии кнопки опубликовать у отзыва. " + textMail);
//            isChanged = true;
//            log.info("isChanged {}", isChanged);
//            throw new IllegalStateException("Проблема с проверкой счетчиков, транзакция должна быть откатана");
//        }
//        checkOrderCounterAndAmount(savedOrder);
//        log.info("10. Проверили счетчик заказа на выполнение заказа - он еще не выполнен");
//
//        return true;
//    } catch (Exception e) {
//        log.error("Ошибка при выполнении метода changeStatusAndOrderCounter для отзыва с id {}", reviewId, e);
////            return false;
//        throw e; // Транзакция откатится при исключении
//    }
//}
//
//protected Order validateAndRetrieveOrder(Review review, Long reviewId) {
//
//    if (review == null) {
//        log.error("2. Отзыв с id {} не найден", reviewId);
//        throw new IllegalStateException("Проблема с отсутвием отзыва по ид, транзакция должна быть откатана");
////                return false;
//    }
//
//    OrderDetails orderDetails = review.getOrderDetails();
//    if (orderDetails == null || orderDetails.getOrder() == null) {
//        throw new IllegalStateException("OrderDetails или Order отсутствуют для отзыва с ID: " + reviewId);
//    }
//
//    Order order = orderRepository.findById(orderDetails.getOrder().getId()).orElse(null);
//    if (order == null || review.isPublish()) {
//        log.info("3. Проверка не пройдена: пустой order = {}, или отзыв уже опубликован publish = {}", order != null, review.isPublish());
//        throw new IllegalStateException("Проблема с отсутвием заказа по ид и статуса уже опубликован, транзакция должна быть откатана");
////                return false;
//    }
//    else return order;
//}
//
//
//@Transactional
//protected int counterReviewIsPublish(Order savedOrder){
//    int reviewCounter = 0;
//    List<Review> reviewList = savedOrder.getDetails().getFirst().getReviews();
//    for (Review review1 : reviewList) {
//        if (review1.isPublish()) {
//            reviewCounter++;
//        }
//    }
//    return reviewCounter;
//}
//
//@Transactional
//public void updateBotCounterAndStatus(Bot bot) {
//    try {
//        bot.setCounter(bot.getCounter() + 1);
//
//        if (bot.getCounter() >= HIGH_COUNTER_THRESHOLD) {
//            log.info("Меняем статус бота при достижении 20 отзывов");
//            bot.setStatus(botService.changeStatus(HIGH_STATUS));
//        } else if (bot.getCounter() >= MEDIUM_COUNTER_THRESHOLD) {
//            log.info("Меняем статус бота при достижении 10 отзывов");
//            bot.setStatus(botService.changeStatus(MEDIUM_STATUS));
//        }
//
//        botService.save(bot);
//    } catch (Exception e) {
//        log.error("Ошибка при обновлении счетчика и статуса у бота с id {}", bot.getId(), e);
//        throw e;
//    }
//}
//
//@Transactional
//protected void checkOrderCounterAndAmount(Order order) throws Exception {
//    try {
//        if (order.getAmount() <= order.getCounter()) {
//            changeStatusForOrder(order.getId(), STATUS_PUBLIC);
//            log.info("4. Счетчик совпадает с количеством заказа. Статус заказа с id {} сменен на Опубликовано", order.getId());
//            if (order.getManager() != null && order.getManager().getUser() != null) {
//                Long telegramChatId = order.getManager().getUser().getTelegramChatId();
//
//                if (telegramChatId != null && order.getCompany() != null && order.getCompany().getTitle() != null) {
//                    String resultBuilder =
//                            order.getCompany().getTitle() +
//                                    " опубликован. \n" +
//                                    "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
//
//                    telegramService.sendMessage(telegramChatId, resultBuilder);
//                }
//            }
//        } else {
//            log.info("4. Счетчик не совпадает с количеством заказа с id {}. Статус заказа не изменён", order.getId());
//        }
//    } catch (Exception e) {
//        log.error("Ошибка при проверке счетчиков заказа с id {}", order.getId(), e);
//        throw e;
//    }
//}
//
//
//
//public int countOrdersByWorkerAndStatus(Worker worker, String status) {
//    int count = orderRepository.countByWorkerAndStatus(worker, status);
////        System.out.println(worker.getUser().getFio() + " " + count);
//    return count;
//}