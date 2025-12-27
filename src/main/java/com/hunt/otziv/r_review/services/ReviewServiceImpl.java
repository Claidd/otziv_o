package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService{

    private final ReviewRepository reviewRepository;
    private final ReviewArchiveRepository reviewArchiveRepository;
    private final BotService botService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final OrderDetailsService orderDetailsService;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final UserService userService;
    private final EmailService emailService;
    private final ProductService productService;
    private final FilialRepository filialRepository;

    private static final Long STUB_BOT_ID = 1L; // ID бота-заглушки в базе


    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByPublishedDateAndPublish(localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    }  // Берем все заказы с поиском по названию компании или номеру

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Работника
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByWorkerAndPublishedDateAndPublish(worker, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Работника

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByManagersAndPublishedDateAndPublish(manager.getUser().getWorkers(), localDate);
        reviewPage = reviewRepository.findAll(reviewId).stream().filter(review -> review.getOrderDetails().getOrder().getManager().equals(manager)).collect(Collectors.toList());
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Менеджера

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Владельца
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByOwnersAndPublishedDateAndPublish(workerList, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Владельца


    private Page<ReviewDTOOne> getPageReviews(List<Review> reviewPage, int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("publishedDate").descending());
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), reviewPage.size());

        List<ReviewDTOOne> reviewDTOOnes = reviewPage.subList(start, end)
                .stream()
                .map(review -> {
                    try {
                        return toReviewDTOOne(review);
                    } catch (Exception e) {
                        log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                                review.getId(), e.getMessage(), e);
                        // Возвращаем DTO с минимальной информацией об ошибке
                        return ReviewDTOOne.builder()
                                .id(review.getId())
                                .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                                .botFio("ОШИБКА")
                                .text(review.getText() != null ? review.getText() : "")
                                .build();
                    }
                })
                .filter(Objects::nonNull) // Фильтруем null значения
                .collect(Collectors.toList());

        return new PageImpl<>(reviewDTOOnes, pageable, reviewPage.size());
    }

//    private Page<ReviewDTOOne> getPageReviews(List<Review> reviewPage, int pageNumber, int pageSize) {
//        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("publishedDate").descending());
//        int start = (int)pageable.getOffset();
//        int end = Math.min((start + pageable.getPageSize()), reviewPage.size());
//        List<ReviewDTOOne> ReviewDTOOnes = reviewPage.subList(start, end)
//                .stream()
//                .map(this::toReviewDTOOne)
//                .collect(Collectors.toList());
//        return new PageImpl<>(ReviewDTOOnes, pageable, reviewPage.size());
//    }

    @Override
    public List<Review> saveAll(List<Review> reviews) {
        return (List<Review>) reviewRepository.saveAll(reviews);
    }

    public Review save(Review review){ // Сохранить отзыв в БД
        if (!reviewRepository.existsByText(review.getText())) {
            log.info("1. Отзыв в БД отзывы сохранен");
            return reviewRepository.save(review);
        }
        if (review.getText().equals("Текст отзыва")){
//            System.out.println(review.getText());
            log.info("1. Отзыв в БД отзывы сохранен как шаблон");
            return reviewRepository.save(review);
        }
        log.info("1. Отзыв в БД отзывы НЕ сохранен, так как такой текст уже есть и это не шаблон");
        return review;
    } // Сохранить отзыв в БД

    public boolean deleteReview(Long reviewId){ // Удалить отзыв
        reviewRepository.delete(Objects.requireNonNull(reviewRepository.findById(reviewId).orElse(null)));
        return true;
    } // Удалить отзыв

    @Override
    public List<Review> getReviewsAllByOrderDetailsId(UUID orderDetailsId) { // Взять все отзывы по Id
        return reviewRepository.findAllByOrderDetailsId(orderDetailsId);
    } // Вхять все отзывы по Id

    public List<Review> getAllWorkerReviews(Long workerId){
        List<Long> reviewId = getReviewByWorkerId(workerId);
        return findAllByListId(reviewId);
    }

    @Override
    public int findAllByReviewListStatus(String username) {
        Worker worker = workerService.getWorkerByUserId(userService.findByUserName(username).orElseThrow().getId());
        LocalDate localDate = LocalDate.now();
        return reviewRepository.findAllByReviewsListStatus(localDate, worker);
    }

    @Override
    public List<Long> getReviewByWorkerId(Long workerId) {
        return reviewRepository.findAllIdByWorkerId(workerId);
    }

    @Override
    public List<Review> findAllByListId(List<Long> reviewId) {
        return reviewRepository.findAll(reviewId);
    }

    public List<ReviewDTOOne> getReviewsAllByOrderId(Long orderId){ // Взять все отзывы по Id заказа
        return reviewRepository.getAllByOrderId(orderId).stream().map(this::toReviewDTOOne).collect(Collectors.toList());
    } // Взять все отзывы по Id заказа


    //    ======================================== FILIAL UPDATE =========================================================
    // Обновить профиль отзыв - начало
    @Override
    @Transactional
    public void updateReview(String userRole, ReviewDTO reviewDTO, Long reviewId) { // Обновление отзывов
        log.info("2. Вошли в обновление данных Отзыв");
        Review saveReview = reviewRepository.findById(reviewId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        log.info("Достали Отзыв");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        log.info("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        log.info("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        log.info("comment: " + !Objects.equals(reviewDTO.getComment(), saveReview.getOrderDetails().getComment()));
        log.info("url: " + !Objects.equals(reviewDTO.getUrl(), saveReview.getUrl()));
        log.info("date publish: " + !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));
        log.info("date isPublish: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        log.info("product id: " + !Objects.equals(reviewDTO.getProduct().getId(), saveReview.getProduct().getId()));

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())){ /*Проверка смены названия*/
            log.info("Обновляем текст отзыва");
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getBotName(), saveReview.getBot().getFio())){ /*Проверка смены названия*/
            log.info("Обновляем Имя Бота");
            Bot bot = saveReview.getBot();
            bot.setFio(reviewDTO.getBotName());
            botService.save(bot);
//            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())){ /*Проверка смены работника*/
            log.info("Обновляем ответ на отзыв");
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getComment(), saveReview.getOrderDetails().getComment())){ /*Проверка статус заказа*/
            log.info("Обновляем комментарий отзыва");
            OrderDetails orderDetails = orderDetailsService.getOrderDetailById(reviewDTO.getOrderDetailsId());
            orderDetails.setComment(reviewDTO.getComment());
            orderDetailsService.save(orderDetails);
            isChanged = true;
        }

        if (!Objects.equals(reviewDTO.getUrl(), saveReview.getUrl())){ /*Проверка смены названия*/
            log.info("Обновляем url отзыва");
            saveReview.setUrl(reviewDTO.getUrl());
            isChanged = true;
        }

        if ((reviewDTO.getProduct() != null && saveReview.getProduct() != null &&
                !Objects.equals(reviewDTO.getProduct().getId(), saveReview.getProduct().getId()))
                || (reviewDTO.getProduct() != null && saveReview.getProduct() == null)
                || (reviewDTO.getProduct() == null && saveReview.getProduct() != null)) {

            log.info("Обновляем продукт отзыва");
            System.out.println(reviewDTO.getProduct());
            // 1. Обновляем продукт и цену у отзыва
            Product product = productService.findById(reviewDTO.getProduct().getId());
            saveReview.setProduct(product);
            saveReview.setPrice(product.getPrice());
            reviewRepository.save(saveReview);

            // 2. Пересчитываем сумму деталей
            // 3. Пересчитываем сумму всего заказа
            recalculateOrderAndDetailsPrice(reviewDTO.getOrderDetailsId());

        }

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
            if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())) { /*Проверка статус заказа*/
                log.info("Обновляем публикацию отзыва");
                saveReview.setPublish(reviewDTO.isPublish());
                isChanged = true;
            }
        }

        if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())){ /*Проверка даты публикации*/
            log.info("Обновляем дату публикации отзыва");
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный Отзыв в БД");
            reviewRepository.save(saveReview);
            log.info("4. Сохранили обновленный Отзыв в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Обновление отзывов

    private void recalculateOrderAndDetailsPrice(UUID orderDetailsId) {
        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailsId);

        BigDecimal detailTotal = orderDetails.getReviews().stream()
                .map(Review::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        orderDetails.setPrice(detailTotal);
        orderDetailsService.save(orderDetails);

        Order order = orderDetails.getOrder();
        order.setSum(orderDetails.getPrice());
        orderDetailsService.saveOrder(order);
    }




    @Transactional
    public void deleteReviewsByOrderId(Long reviewId){
        reviewRepository.deleteReviewByReviewId(reviewId);
    }

    @Override
    public List<Review> findAllByFilial(Filial filial) {
        return reviewRepository.findAllByFilial(filial);
    }

    @Override
    public void updateReviewByFilials(Set<Filial> filials, Long categoryId, Long subCategoryId) {
        List<Review> reviews = reviewRepository.findAllByFilials(filials);
        Iterable<ReviewArchive> reviewArchives = reviewArchiveRepository.findAll();
        for (Review review : reviews) {
            review.setCategory(categoryService.getCategoryByIdCategory(categoryId));
            review.setSubCategory(subCategoryService.getSubCategoryById(subCategoryId));
            reviewRepository.save(review);
        }
        for (ReviewArchive reviewArchive : reviewArchives) {
            for (Review review : reviews) {
                if (review.getText().equals(reviewArchive.getText()) && !reviewArchive.getText().equals("Текст отзыва")){
                    reviewArchive.setCategory(categoryService.getCategoryByIdCategory(categoryId));
                    reviewArchive.setSubCategory(subCategoryService.getSubCategoryById(subCategoryId));
                    reviewArchiveRepository.save(reviewArchive);
                }
            }
        }
    }

//    =====================================================================================================

    //    ======================================== ORDER DETAIL AND REVIEW UPDATE =========================================================
    // Обновить профиль отзыв - начало
    @Override
    @Transactional
    public void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId) { // Обновление Деталей и Отзывов
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа");
        Review saveReview = reviewRepository.findById(reviewId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        OrderDetails saveOrderDetails  = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
        log.info("Достали Отзыв");
        boolean isChanged = false;

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())){ /*Проверка смены названия*/
            log.info("Обновляем текст отзыва");
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())){ /*Проверка смены работника*/
            log.info("Обновляем ответ на отзыв");
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }
        if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())){ /*Проверка статус заказа*/
            log.info("Обновляем комментарий отзыва и Деталей Заказа");
            saveOrderDetails.setComment(orderDetailsDTO.getComment());
            orderDetailsService.save(saveOrderDetails);
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())){ /*Проверка статус заказа*/
            log.info("Обновляем публикацию отзыва");
            saveReview.setPublish(reviewDTO.isPublish());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())){ /*Проверка даты публикации*/
            log.info("Обновляем дату публикации отзыва");
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный Отзыв в БД");
            reviewRepository.save(saveReview);
            log.info("4. Сохранили обновленный Отзыв в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Обновление Деталей и Отзывов

//    =====================================================================================================

//    ============================== ORDER DETAIL AND REVIEW UPDATE AND SET PUBLISH DATE ===============================
@Override
@Transactional
public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) {
    log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение случайных дат публикации (1–6 дней, растяжка по диапазону)");

    try {
        OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());

        List<Review> reviews = saveOrderDetails.getReviews();
        if (reviews.isEmpty()) {
            log.error("Ошибка: список отзывов пуст");
            return false;
        }

        int totalReviews = orderDetailsDTO.getReviews().size();
        if (totalReviews == 0) {
            log.error("Ошибка: список отзывов в DTO пуст");
            return false;
        }

        int botCounter = reviews.getFirst().getBot().getCounter();
        LocalDate startDate = getLocalDate(botCounter);

        // Минимум 28 дней. Если отзывов больше — добавляем месяцы по 28 дней
        int monthsNeeded = (int) Math.ceil(totalReviews / 28.0);
        LocalDate endDate = startDate.plusDays(monthsNeeded * 28 - 1);
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate);

        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Список смещений дат (0 = startDate)
        List<Long> offsets = new ArrayList<>();

        // 1. Первый отзыв — в первые 0–2 дня
        offsets.add(random.nextLong(0, Math.min(3, totalDays + 1)));

        // 2. Последний отзыв — в последние 0–2 дня
        if (totalReviews > 1) {
            offsets.add(totalDays - random.nextLong(0, Math.min(3, totalDays + 1)));
        }

        // 3. Остальные — случайно по диапазону
        while (offsets.size() < totalReviews) {
            long offset = random.nextLong(0, totalDays + 1);
            if (!offsets.contains(offset)) {
                offsets.add(offset);
            }
        }

        // 4. Сортируем
        Collections.sort(offsets);

        // 5. Корректируем зазоры: минимум 1 день, максимум 6 дней
        for (int i = 1; i < offsets.size(); i++) {
            long prev = offsets.get(i - 1);
            long current = offsets.get(i);
            long gap = current - prev;

            if (gap < 1) {
                offsets.set(i, prev + 1);
            } else if (gap > 6) {
                offsets.set(i, prev + 6);
            }
        }

        // 6. Ещё раз сортируем на случай коррекции
        Collections.sort(offsets);

        // 7. Присваиваем даты публикаций
        for (int i = 0; i < totalReviews; i++) {
            ReviewDTO reviewDTO = orderDetailsDTO.getReviews().get(i);
            LocalDate publishDate = startDate.plusDays(offsets.get(i));

            checkUpdateReview(reviewDTO, publishDate);
            log.info("Обновили дату публикации отзыва №{}: {}", i + 1, publishDate);
        }

        // 8. Обновляем комментарий, если изменился
        if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
            log.info("Обновляем комментарий отзыва и Деталей Заказа");
            saveOrderDetails.setComment(orderDetailsDTO.getComment());
            orderDetailsService.save(saveOrderDetails);
        }

        log.info("Все прошло успешно: даты публикаций распределены с зазором 1–6 дней, первый и последний отзыв закреплены по краям диапазона");
        return true;

    } catch (Exception e) {
        log.error("Ошибка обновления данных, даты публикаций НЕ установлены: ", e);
        return false;
    }
}



//    @Override
//    @Transactional
//    public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) {
//        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение даты публикации");
//
//        try {
//            OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
//
//            List<Review> reviews = saveOrderDetails.getReviews();
//            if (reviews.isEmpty()) {
//                log.error("Ошибка: список отзывов пуст");
//                return false;
//            }
//
//            int totalReviews = orderDetailsDTO.getReviews().size();
//            if (totalReviews == 0) {
//                log.error("Ошибка: список отзывов в DTO пуст");
//                return false;
//            }
//
//            int botCounter = reviews.getFirst().getBot().getCounter();
//            LocalDate startDate = getLocalDate(botCounter);
//            LocalDate endDate = startDate.plusDays(30);
//
//            long totalDays = ChronoUnit.DAYS.between(startDate, endDate);
//
//            BigDecimal step = BigDecimal.valueOf(totalDays)
//                    .divide(BigDecimal.valueOf(totalReviews - 1), 2, RoundingMode.HALF_UP);
//            BigDecimal currentOffset = BigDecimal.ZERO;
//
//            for (int i = 0; i < totalReviews; i++) {
//                ReviewDTO reviewDTO = orderDetailsDTO.getReviews().get(i);
//                long daysToAdd = currentOffset.setScale(0, RoundingMode.HALF_UP).longValue();
//                LocalDate publishDate = startDate.plusDays(daysToAdd);
//
//                checkUpdateReview(reviewDTO, publishDate);
//                log.info("Обновили дату публикации отзыва №{}: {}", i + 1, publishDate);
//
//                currentOffset = currentOffset.add(step);
//            }
//
//            if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
//                log.info("Обновляем комментарий отзыва и Деталей Заказа");
//                saveOrderDetails.setComment(orderDetailsDTO.getComment());
//                orderDetailsService.save(saveOrderDetails);
//            }
//
//            log.info("Все прошло успешно, даты публикаций установлены равномерно, возвращаем TRUE");
//            return true;
//        } catch (Exception e) {
//            log.error("Ошибка обновления данных, даты публикаций НЕ установлены: ", e);
//            return false;
//        }
//    }


    // Улучшенная версия метода getLocalDate
    private LocalDate getLocalDate(int botCounter) {
        return botCounter < 2 ? LocalDate.now().plusDays(2) : LocalDate.now();
    }



//    =====================================================================================================

    private void checkUpdateReview(ReviewDTO reviewDTO, LocalDate localDate){ // Проверка обновлений отзыва
        Review saveReview = reviewRepository.findById(reviewDTO.getId()).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewDTO.getId())));
        log.info("Достали Отзыв");
        boolean isChanged = false;
        /*Временная проверка сравнений*/
//        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
//        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
//        System.out.println("publish date: " + (!saveReview.isPublish()));
//        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
//        System.out.println("date publish: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));

        if (!saveReview.isPublish()){ /*Проверка смены даты публикации*/
            log.info("Обновляем дату публикации");
            saveReview.setPublishedDate(localDate);
            isChanged = true;
        }

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())){ /*Проверка смены названия*/
            log.info("Обновляем текст отзыва");
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getUrl(), saveReview.getUrl())){ /*Проверка смены названия*/
            log.info("Обновляем url отзыва");
            saveReview.setUrl(reviewDTO.getUrl());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())){ /*Проверка смены работника*/
            log.info("Обновляем ответ на отзыв");
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())){ /*Проверка статус заказа*/
            log.info("Обновляем публикацию отзыва");
            saveReview.setPublish(reviewDTO.isPublish());
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный Отзыв в БД");
            reviewRepository.save(saveReview);
            log.info("4. Сохранили обновленный Отзыв в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    } // Проверка обновлений отзыва
//    =================================== ЗАМЕНА И БЛОИРОКА БОТОВ ДЛЯ ЗАКАЗА ===========================================



//    private Review getReviewToChangeBot(Long reviewId) { // Установка нового бота в отзыв
//        Review review = reviewRepository.findById(reviewId).orElse(null);
//        assert review != null;
//        log.info("2. Достали отзыв по id{}", reviewId);
//        List<Bot> bots = findAllBotsMinusFilial(review);
//        log.info("3. Достали ботов минус филиал");
//        var random = new SecureRandom();
//        review.setBot(bots.get(random.nextInt(bots.size())));
//        return review;
//    } // Установка нового бота в отзыв


//    private void botActiveToFalse(Long botId){ // Изменение статуса бота как НЕ активный
//        try {
//            Bot bot = botService.findBotById(botId);
//            bot.setActive(false);
//            botService.save(bot);
//            log.info("3. Дективировали бота {}", botId);
//        }
//        catch (Exception e){
//            log.error("e: ", e);
//            log.info("Что-то пошло не так и деактивация бота не случилась");
//        }
//    } // Изменение статуса бота как НЕ активный




//    private List<Bot> findAllBotsMinusFilial(Review review) {
//        if (review == null) {
//            log.error("Ошибка: review == null");
//            return Collections.emptyList();
//        }
//
//        Filial filial = review.getFilial();
//        if (filial == null) {
//            log.error("Ошибка: у review отсутствует filial");
//            return Collections.emptyList();
//        }
//
//        City city = filial.getCity();
//        if (city == null || city.getId() == null) {
//            log.error("Ошибка: у filial отсутствует город или его ID");
//            return Collections.emptyList();
//        }
//
//        List<Bot> bots;
//        try {
//            bots = botService.getFindAllByFilialCityId(city.getId());
//        } catch (Exception e) {
//            log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
//            return Collections.emptyList();
//        }
//
//        log.info("Боты вытащенные из базы по городу: {} кол-во: {}", city.getTitle(), bots != null ? bots.size() : 0);
//
//        if (bots == null || bots.isEmpty()) {
//            log.warn("Список ботов пуст или null");
//            return Collections.emptyList();
//        }
//
//        List<Review> reviewListFilial;
//        try {
//            reviewListFilial = reviewRepository.findAllByFilial(filial);
//        } catch (Exception e) {
//            log.error("Ошибка при получении отзывов по филиалу: {}", filial.getId(), e);
//            return Collections.emptyList();
//        }
//
//        if (reviewListFilial == null) {
//            log.warn("Список отзывов филиала null");
//            reviewListFilial = Collections.emptyList();
//        }
//
//        List<Bot> botsCompany = reviewListFilial.stream()
//                .map(Review::getBot)
//                .filter(Objects::nonNull)
//                .toList();
//
//        log.info("Боты уже использованные в этом городе (для удаления из списка): {}", botsCompany.size());
//
//        bots.removeAll(botsCompany);
//        log.info("Оставшиеся: {}", bots.size());
//
//        return bots;
//    }



//    ================================ ЗАМЕНА И БЛОИРОКА БОТОВ ДЛЯ ЗАКАЗА КОНЕЦ ========================================



        // ================================ ЗАМЕНА И БЛОКИРОВКА БОТОВ ========================================

        @Override
        public void changeBot(Long reviewId) {
            try {
                log.info("1. Начинаем замену бота для отзыва ID {}", reviewId);
                Review review = getReviewToChangeBot(reviewId);

                if (review.getBot() == null) {
                    log.warn("2. Для отзыва ID {} не удалось установить бота (список доступных пуст)", reviewId);
                } else if (review.getBot().getId() != null && review.getBot().getId() == 1L) {
                    log.warn("2. Для отзыва ID {} установлен бот-заглушка (нет доступных ботов)", reviewId);
                } else {
                    log.info("2. Установлен новый рандомный бот для отзыва ID {}", reviewId);
                }

                reviewRepository.save(review);
                log.info("3. Сохранили отзыв в БД");

            } catch (Exception e) {
                log.error("Ошибка при замене бота для отзыва ID {}: {}", reviewId, e.getMessage(), e);
                throw new RuntimeException("Не удалось заменить бота: " + e.getMessage());
            }
        }

        @Override
        public void deActivateAndChangeBot(Long reviewId, Long botId) {
            try {
                Review review = reviewRepository.findById(reviewId).orElse(null);
                if (review == null) {
                    log.warn("Отзыв с id {} не найден", reviewId);
                    throw new RuntimeException("Отзыв не найден");
                }

                // Получаем текущего бота отзыва для корректной деактивации
                Bot currentBot = review.getBot();
                Long currentBotId = currentBot != null ? currentBot.getId() : null;

                // Если передан botId = 0, но у отзыва есть реальный бот, используем ID реального бота
                if ((botId == null || botId == 0L) && currentBotId != null && currentBotId > 0) {
                    botId = currentBotId;
                    log.info("Используем ID реального бота отзыва: {}", botId);
                }

                // Отправка email о малом количестве ботов
                try {
                    if (review.getFilial() != null && review.getFilial().getCity() != null) {
                        List<Bot> cityBots = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId());
                        int botCount = cityBots != null ? cityBots.size() : 0;
                        if (botCount < 50) {
                            String textMail = "Город: " + review.getFilial().getCity().getTitle() + ". Остаток у города: " + botCount;
                            emailService.sendSimpleEmail("o-company-server@mail.ru", "Мало аккаунтов у города", "Необходимо добавить аккаунты для: " + textMail);
                            log.info("ОТПРАВКА МЕЙЛА О МАЛОМ КОЛИЧЕСТВЕ АККАУНТОВ - УСПЕХ");
                        } else {
                            log.info("ПИСЬМО не отправлялось - у города достаточно аккаунтов");
                        }
                    } else {
                        log.warn("Не удалось отправить письмо: у филиала или города отсутствуют данные");
                    }
                } catch (Exception e) {
                    log.error("Сообщение о деактивации бота не отправилось", e);
                }

                // Деактивируем старого бота, только если это не бот-заглушка (ID != 1) и ID > 0
                boolean deactivated = false;
                if (botId != null && botId != 1L && botId > 0) {
                    deactivated = botActiveToFalse(botId);
                    if (deactivated) {
                        log.info("4. Деактивировали старого бота ID {}", botId);
                    } else {
                        log.warn("4. Не удалось деактивировать старого бота ID {}", botId);
                    }
                } else {
                    log.info("4. Пропускаем деактивацию: бот ID {} является заглушкой или невалидным", botId);
                }

                // Ищем нового бота и устанавливаем его в текущий объект review
                List<Bot> availableBots = findAllBotsMinusFilial(review);
                log.info("5. Найдено доступных ботов: {}", availableBots.size());

                if (availableBots.isEmpty()) {
                    // Если нет доступных ботов, устанавливаем бота-заглушку
                    Bot stubBot = createStubBot();
                    review.setBot(stubBot);
                    log.warn("6. Установлен бот-заглушка для отзыва ID {} (нет доступных ботов)", reviewId);
                } else {
                    var random = new SecureRandom();
                    int randomIndex = random.nextInt(availableBots.size());
                    Bot selectedBot = availableBots.get(randomIndex);
                    review.setBot(selectedBot);
                    log.info("6. Установлен новый бот ID {} для отзыва ID {}", selectedBot.getId(), reviewId);
                }

                reviewRepository.save(review);
                log.info("7. Сохранили изменения в БД");

            } catch (Exception e) {
                log.error("Что-то пошло не так и бот не деактивирован", e);
                throw new RuntimeException("Ошибка при деактивации и смене бота: " + e.getMessage());
            }
        }

        private Review getReviewToChangeBot(Long reviewId) {
            Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
            if (reviewOptional.isEmpty()) {
                log.error("1. Отзыв с ID {} не найден", reviewId);
                throw new RuntimeException("Отзыв не найден");
            }

            Review review = reviewOptional.get();
            log.info("2. Достали отзыв по id {}", reviewId);

            List<Bot> bots = findAllBotsMinusFilial(review);
            log.info("3. Найдено доступных ботов: {}", bots.size());

            if (bots.isEmpty()) {
                log.error("4. Нет доступных ботов для отзыва ID {}", reviewId);
                // Устанавливаем бота-заглушку
                Bot stubBot = createStubBot();
                review.setBot(stubBot);
                log.info("5. Установлен бот-заглушка для отзыва ID {}", reviewId);
            } else {
                var random = new SecureRandom();
                int randomIndex = random.nextInt(bots.size());
                Bot selectedBot = bots.get(randomIndex);
                review.setBot(selectedBot);
                log.info("5. Установлен новый бот для отзыва ID {}: бот ID {}, имя: {}",
                        reviewId, selectedBot.getId(), selectedBot.getFio());
            }

            return review;
        }

        // Метод для создания бота-заглушки
        private Bot createStubBot() {
            try {
                Optional<Bot> stubBotOptional = Optional.ofNullable(botService.findBotById(STUB_BOT_ID));
                if (stubBotOptional.isPresent()) {
                    return stubBotOptional.get();
                } else {
                    log.warn("Бот-заглушка не найден в базе, создаем временного");
                    // СОЗДАЕМ временного бота-заглушку
                    Bot stubBot = new Bot();
                    stubBot.setId(STUB_BOT_ID);
                    stubBot.setFio("Нет доступных аккаунтов");
                    stubBot.setLogin("stub_account");
                    stubBot.setPassword("");
                    stubBot.setCounter(0);
                    stubBot.setActive(false);

                    // Создаем временный статус
                    StatusBot stubStatus = new StatusBot();
                    stubStatus.setBotStatusTitle("Заглушка");
                    stubBot.setStatus(stubStatus);

                    return stubBot;
                }
            } catch (Exception e) {
                log.error("Ошибка при получении бота-заглушки", e);
                // Даже при ошибке возвращаем бота-заглушку
                Bot stubBot = new Bot();
                stubBot.setId(STUB_BOT_ID);
                stubBot.setFio("Нет доступных аккаунтов");
                stubBot.setLogin("stub_account");
                stubBot.setPassword("");
                stubBot.setCounter(0);
                stubBot.setActive(false);
                return stubBot;
            }
        }


        private boolean botActiveToFalse(Long botId) {
            try {

                if (botId == null || botId <= 0 || STUB_BOT_ID.equals(botId)) {
                    log.debug("Пропускаем деактивацию бота с ID {} (невалидный ID или заглушка)", botId);
                    return false;
                }

                Optional<Bot> botOptional = Optional.ofNullable(botService.findBotById(botId));

                if (botOptional.isEmpty()) {
                    log.error("1. Бот с ID {} не найден", botId);
                    return false;
                }

                Bot bot = botOptional.get();
                boolean wasActive = bot.isActive();
                bot.setActive(false);

                try {
                    botService.save(bot);
                    log.info("2. Деактивировали бота {} (был активен: {})", botId, wasActive);
                    return true;
                } catch (Exception e) {
                    log.error("Ошибка при сохранении бота ID {}: {}", botId, e.getMessage());
                    return false;
                }

            } catch (Exception e) {
                log.error("3. Ошибка при деактивации бота {}: ", botId, e);
                return false;
            }
        }

        public List<Bot> findAllBotsMinusFilial(Review review) {
            if (review == null) {
                log.error("Ошибка: review == null");
                return Collections.emptyList();
            }

            Filial filial = review.getFilial();
            if (filial == null) {
                log.error("Ошибка: у review отсутствует filial");
                return Collections.emptyList();
            }

            City city = filial.getCity();
            if (city == null || city.getId() == null) {
                log.error("Ошибка: у filial отсутствует город или его ID");
                return Collections.emptyList();
            }

            log.info("Поиск ботов для отзыва ID {}, филиал ID {}, город: {}",
                    review.getId(), filial.getId(), city.getTitle());

            // Получаем всех ботов для города через репозиторий
            List<Bot> allBots;
            try {
                allBots = botService.getFindAllByFilialCityId(city.getId());
                log.info("2. всех ботов для города {} allBots - {} ", city.getId(), allBots.size());
            } catch (Exception e) {
                log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
                return Collections.emptyList();
            }

            if (allBots == null || allBots.isEmpty()) {
                log.warn("Список ботов пуст для города: {}", city.getTitle());
                return Collections.emptyList();
            }

            log.debug("Получено ботов из базы: {}", allBots.size());

            // 1. Получаем ID ботов, которые уже использовались в этом филиале (ВСЕ отзывы)
            Set<Long> usedBotIdsInThisFilial = getUsedBotIdsInFilial(filial, review.getId());
            log.info("Ботов уже использованных в этом филиале: {}", usedBotIdsInThisFilial.size());

            // 2. Получаем ID ботов, занятых в активных отзывах в других филиалах ТОГО ЖЕ ГОРОДА
            Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial, review.getId());
            log.info("Ботов занятых в активных отзывах других филиалов того же города: {} - это количество уникальных bot_id в этих отзывах", usedBotIdsGlobally.size());

            boolean vigul = review.isVigul();
            log.info("Отзыв ID {} имеет vigul = {}", review.getId(), vigul);

            // Этап 1: Идеальные боты - не использовались в этом филиале и не заняты в других
            List<Bot> idealBots = allBots.stream()
                    .filter(Objects::nonNull)
                    .filter(bot -> bot.getId() != null)
                    .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
                    .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
                    .filter(bot -> {
                        if (bot.getStatus() == null) return false;
                        String statusTitle = bot.getStatus().getBotStatusTitle();
                        return statusTitle != null && "Новый".equals(statusTitle.trim());
                    })
                    .collect(Collectors.toList());

            log.info("Идеальных ботов (не в этом филиале, не заняты в других): {}", idealBots.size());

            // Если есть идеальные боты, применяем фильтры vigul
            if (!idealBots.isEmpty()) {
                List<Bot> filteredBots = applyVigulFilters(idealBots, vigul);
                if (!filteredBots.isEmpty()) {
                    log.info("Используем идеальных ботов: {}", filteredBots.size());
                    return filteredBots;
                }
            }

            // Этап 2: Запасной режим - боты, занятые в других филиалах, но не в этом
            List<Bot> fallbackBots = allBots.stream()
                    .filter(Objects::nonNull)
                    .filter(bot -> bot.getId() != null)
                    .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
                    .filter(bot -> {
                        if (bot.getStatus() == null) return false;
                        String statusTitle = bot.getStatus().getBotStatusTitle();
                        return statusTitle != null && "Новый".equals(statusTitle.trim());
                    })
                    .collect(Collectors.toList());

            log.info("Запасных ботов (не в этом филиале, но могут быть заняты в других): {}", fallbackBots.size());

            if (!fallbackBots.isEmpty()) {
                List<Bot> filteredBots = applyVigulFilters(fallbackBots, vigul);
                if (!filteredBots.isEmpty()) {
                    log.warn("Используем запасных ботов (заняты в других филиалах): {}", filteredBots.size());
                    return filteredBots;
                }
            }

            // Этап 3: Все боты использованы - возвращаем пустой список
            log.error("ВСЕ боты уже использованы в этом филиале или не подходят по условиям vigul. Необходимо назначить бота-заглушку");
            return Collections.emptyList();
        }

        private Set<Long> getUsedBotIdsInFilial(Filial filial, Long currentReviewId) {
            Set<Long> usedBotIds = new HashSet<>();

            try {
                List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);

                if (allReviewsInFilial != null) {
                    for (Review existingReview : allReviewsInFilial) {
                        if (existingReview != null &&
                                existingReview.getId() != null &&
                                !existingReview.getId().equals(currentReviewId) &&
                                existingReview.getBot() != null &&
                                existingReview.getBot().getId() != null) {

                            usedBotIds.add(existingReview.getBot().getId());
                        }
                    }
                }
                log.info("Всего ботов, использованных в филиале {}: {}",
                        filial.getId(), usedBotIds.size());

            } catch (Exception e) {
                log.error("Ошибка при получении использованных ботов для филиала {}", filial.getId(), e);
            }

            return usedBotIds;
        }

        private Set<Long> getUsedBotIdsGlobally(Filial currentFilial, Long currentReviewId) {
            Set<Long> usedBotIds = new HashSet<>();

            try {
                City currentCity = currentFilial.getCity();
                if (currentCity == null || currentCity.getId() == null) {
                    log.warn("У текущего филиала ID {} не указан город", currentFilial.getId());
                    return usedBotIds;
                }

                // Находим все филиалы того же города
                List<Filial> filialsInSameCity = filialRepository.findByCityId(currentCity.getId());
                if (filialsInSameCity == null || filialsInSameCity.isEmpty()) {
                    return usedBotIds;
                }

                // Собираем ID всех филиалов того же города (кроме текущего)
                List<Long> otherFilialIdsInCity = filialsInSameCity.stream()
                        .filter(filial -> filial != null && filial.getId() != null)
                        .filter(filial -> !filial.getId().equals(currentFilial.getId()))
                        .map(Filial::getId)
                        .collect(Collectors.toList());

                if (otherFilialIdsInCity.isEmpty()) {
                    return usedBotIds;
                }

                // Находим активные отзывы в этих филиалах
                List<Review> activeReviewsInSameCity = reviewRepository
                        .findByPublishFalseAndBotIsNotNullAndFilialIdIn(otherFilialIdsInCity);

                if (activeReviewsInSameCity != null) {
                    for (Review existingReview : activeReviewsInSameCity) {
                        if (existingReview != null &&
                                existingReview.getId() != null &&
                                !existingReview.getId().equals(currentReviewId) &&
                                existingReview.getBot() != null &&
                                existingReview.getBot().getId() != null) {

                            usedBotIds.add(existingReview.getBot().getId());
                        }
                    }
                }

                log.info("Найдено активных отзывов с ботами в других филиалах того же города  {} - это количество записей в таблице Review, где publish = false и бот не null:",
                        activeReviewsInSameCity != null ? activeReviewsInSameCity.size() : 0);

            } catch (Exception e) {
                log.error("Ошибка при получении глобально использованных ботов", e);
            }

            return usedBotIds;
        }

        private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul) {
            if (!vigul) {
                log.info("Фильтрация для vigul=false");

                List<Bot> strictFiltered = baseBots.stream()
                        .filter(bot -> {
                            if (bot.getFio() == null) return false;
                            return "Впиши Имя Фамилию".equals(bot.getFio().trim());
                        })
                        .collect(Collectors.toList());
                log.info("Всего ботов: переданных в фильтр Впиши Имя Фамилию': {}", baseBots.size());
                log.info("Ботов с именем 'Впиши Имя Фамилию': {}", strictFiltered.size());

                if (!strictFiltered.isEmpty()) {
                    return strictFiltered;
                }

                log.warn("Нет ботов с именем 'Впиши Имя Фамилию', используем всех доступных ботов");
                return baseBots;

            } else {
                log.info("Фильтрация для vigul=true");

                List<Bot> strictFiltered = baseBots.stream()
                        .filter(bot -> {
                            Integer counter = bot.getCounter();
                            if (counter == null) counter = 0;
                            return counter >= 3;
                        })
                        .collect(Collectors.toList());

                log.info("Всего ботов: переданных в фильтр с counter >= 3: {}", baseBots.size());
                log.info("Ботов с counter >= 3: {}", strictFiltered.size());

                if (!strictFiltered.isEmpty()) {
                    return strictFiltered;
                }

                List<Bot> fallbackFiltered = baseBots.stream()
                        .filter(bot -> {
                            Integer counter = bot.getCounter();
                            if (counter == null) counter = 0;
                            return counter >= 0 && counter <= 2;
                        })
                        .collect(Collectors.toList());

                log.info("Ботов с counter от 0 до 2: {}", fallbackFiltered.size());

                if (!fallbackFiltered.isEmpty()) {
                    log.warn("Нет ботов с counter >= 3, используем ботов с counter от 0 до 2");
                    return fallbackFiltered;
                }

                log.warn("Нет ботов с подходящим counter, используем всех доступных ботов");
                return baseBots;
            }
        }

        private boolean isBotOverloaded(Bot bot) {
            if (bot == null || bot.getId() == null) {
                return false;
            }

            try {
                List<Review> botActiveReviews = reviewRepository.findByBotAndPublishFalse(bot);
                int maxActiveReviewsPerBot = 3;
                return botActiveReviews != null && botActiveReviews.size() >= maxActiveReviewsPerBot;
            } catch (Exception e) {
                log.error("Ошибка при проверке загруженности бота ID {}", bot.getId(), e);
                return false;
            }
        }

        public ReviewDTOOne toReviewDTOOne(Review review) {
            try {
                OrderDetails orderDetails = review.getOrderDetails();
                Bot bot = review.getBot();

                boolean isStubBot = bot != null && bot.getId() != null && STUB_BOT_ID.equals(bot.getId());

                String botFio;
                if (orderDetails == null) {
                    botFio = "НЕТ ЗАКАЗА";
                } else if (bot == null) {
                    botFio = "Добавьте аккаунты и нажмите сменить";
                } else if (isStubBot) {
                    botFio = "Нет доступных аккаунтов";
                } else {
                    botFio = Optional.ofNullable(bot.getFio())
                            .filter(name -> !name.trim().isEmpty())
                            .orElse("Бот без имени");
                }




                String companyTitle = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getCompany)
                        .map(Company::getTitle)
                        .orElse("НЕТ ЗАКАЗА");

                Long companyId = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getCompany)
                        .map(Company::getId)
                        .orElse(null);

                UUID orderDetailsId = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getId)
                        .orElse(null);

                Long orderId = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getId)
                        .orElse(null);

                String productTitle = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getProduct)
                        .map(Product::getTitle)
                        .orElse("НЕТ ПРОДУКТА");

                String comment = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getComment)
                        .orElse("");

                String orderComments = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getZametka)
                        .orElse("");

                String commentCompany = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getCompany)
                        .map(Company::getCommentsCompany)
                        .orElse("");


                String workerFio = Optional.ofNullable(review.getWorker())
                        .map(Worker::getUser)
                        .map(User::getFio)
                        .orElse("");

                if (workerFio.isEmpty()) {
                    workerFio = Optional.ofNullable(orderDetails)
                            .map(OrderDetails::getOrder)
                            .map(Order::getManager)
                            .map(Manager::getUser)
                            .map(User::getFio)
                            .orElse("");
                }

                String filialCity = Optional.ofNullable(review.getFilial())
                        .map(Filial::getCity)
                        .map(City::getTitle)
                        .orElse("");

                String filialTitle = Optional.ofNullable(review.getFilial())
                        .map(Filial::getTitle)
                        .orElse("");

                String filialUrl = Optional.ofNullable(review.getFilial())
                        .map(Filial::getUrl)
                        .orElse("");

                String category = Optional.ofNullable(review.getCategory())
                        .map(Category::getCategoryTitle)
                        .orElse("Нет категории");

                String subCategory = Optional.ofNullable(review.getSubCategory())
                        .map(SubCategory::getSubCategoryTitle)
                        .orElse("Нет подкатегории");

                LocalDate created = review.getCreated() != null ? review.getCreated() : LocalDate.now();
                LocalDate changed = review.getChanged() != null ? review.getChanged() : created;
                LocalDate publishedDate = review.getPublishedDate() != null ? review.getPublishedDate() : LocalDate.now();


                // БЕЗОПАСНОЕ получение данных бота с значениями по умолчанию
                Long botId = null;
                String botLogin = "";
                String botPassword = "";
                Integer botCounter = 0; // Важно: инициализируем 0

                if (bot != null) {
                    botId = bot.getId();
                    botLogin = Optional.ofNullable(bot.getLogin()).orElse("");
                    botPassword = Optional.ofNullable(bot.getPassword()).orElse("");
                    botCounter = Optional.ofNullable(bot.getCounter()).orElse(0); // Здесь исправление
                }
                return ReviewDTOOne.builder()
                        .id(review.getId())
                        .companyId(companyId)
                        .commentCompany(commentCompany)
                        .orderDetailsId(orderDetailsId)
                        .orderId(orderId)
                        .text(review.getText() != null ? review.getText() : "")
                        .answer(review.getAnswer() != null ? review.getAnswer() : "")
                        .category(category)
                        .subCategory(subCategory)
                        .botId(botId)  // Теперь безопасно
                        .botFio(botFio)
                        .botLogin(botLogin)
                        .botPassword(botPassword)
                        .botCounter(botCounter)
                        .companyTitle(companyTitle)
                        .productTitle(productTitle)
                        .filialCity(filialCity)
                        .filialTitle(filialTitle)
                        .filialUrl(filialUrl)
                        .workerFio(workerFio)
                        .created(created)
                        .changed(changed)
                        .publishedDate(publishedDate)
                        .publish(review.isPublish())
                        .vigul(review.isVigul())
                        .comment(comment)
                        .orderComments(orderComments)
                        .product(review.getProduct())
                        .url(review.getUrl() != null ? review.getUrl() : "")
                        .build();

            } catch (Exception e) {
                log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                        review != null ? review.getId() : "null", e.getMessage(), e);

                return ReviewDTOOne.builder()
                        .id(review != null ? review.getId() : 0L)
                        .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                        .botFio("ОШИБКА")
                        .text(review != null && review.getText() != null ? review.getText() : "Не удалось загрузить данные отзыва")
                        .build();
            }
        }



























//    @Override
//    public void changeBot(Long reviewId) {
//        // Замена бота
//        try {
//            log.info("1. Начинаем замену бота для отзыва ID {}", reviewId);
//            Review review = getReviewToChangeBot(reviewId);
//
//            if (review.getBot() != null && review.getBot().getId() == -1L) {
//                log.warn("2. Для отзыва ID {} установлен бот-заглушка (нет доступных ботов)", reviewId);
//            } else if (review.getBot() != null) {
//                log.info("2. Установлен новый рандомный бот для отзыва ID {}", reviewId);
//            } else {
//                log.warn("2. Не удалось установить бота для отзыва ID {}", reviewId);
//            }
//
//            reviewRepository.save(review);
//            log.info("3. Сохранили отзыв в БД");
//
//        } catch (Exception e) {
//            log.error("Ошибка при замене бота для отзыва ID {}: {}", reviewId, e.getMessage(), e);
//            throw new RuntimeException("Не удалось заменить бота: " + e.getMessage());
//        }
//    }
//
//    @Override
//    public void deActivateAndChangeBot(Long reviewId, Long botId) {
//        // Деактивация бота
//        try {
//            Review review = reviewRepository.findById(reviewId).orElse(null);
//            if (review == null) {
//                log.warn("Отзыв с id {} не найден", reviewId);
//                throw new RuntimeException("Отзыв не найден");
//            }
//
//            // Получаем текущего бота отзыва для корректной деактивации
//            Bot currentBot = review.getBot();
//            Long currentBotId = currentBot != null ? currentBot.getId() : null;
//
//            // Если передан botId = 0, но у отзыва есть реальный бот, используем ID реального бота
//            if ((botId == null || botId == 0L) && currentBotId != null && currentBotId > 0) {
//                botId = currentBotId;
//                log.info("Используем ID реального бота отзыва: {}", botId);
//            }
//
//            // Отправка email о малом количестве ботов
//            try {
//                // Проверяем, что у филиала есть город
//                if (review.getFilial() != null && review.getFilial().getCity() != null) {
//                    int botCount = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId()).size();
//                    if (botCount < 50) {
//                        String textMail = "Город: " + review.getFilial().getCity().getTitle() + ". Остаток у города: " + botCount;
//                        emailService.sendSimpleEmail("o-company-server@mail.ru", "Мало аккаунтов у города", "Необходимо добавить аккаунты для: " + textMail);
//                        log.info("ОТПРАВКА МЕЙЛА О МАЛОМ КОЛИЧЕСТВЕ АККАУНТОВ - УСПЕХ");
//                    } else {
//                        log.info("ПИСЬМО не отправлялось - у города достаточно аккаунтов");
//                    }
//                } else {
//                    log.warn("Не удалось отправить письмо: у филиала или города отсутствуют данные");
//                }
//            } catch (Exception e) {
//                log.error("Сообщение о деактивации бота не отправилось", e);
//            }
//
//            // Деактивируем старого бота, только если это не бот-заглушка (ID != -1) и ID > 0
//            boolean deactivated = false;
//            if (botId != null && botId != -1L && botId > 0) {
//                deactivated = botActiveToFalse(botId);
//                if (deactivated) {
//                    log.info("4. Деактивировали старого бота ID {}", botId);
//                } else {
//                    log.warn("4. Не удалось деактивировать старого бота ID {}", botId);
//                }
//            } else {
//                log.info("4. Пропускаем деактивацию: бот ID {} является заглушкой или невалидным", botId);
//            }
//
//            // Получаем отзыв с новым ботом (или без бота, если нет доступных)
//            Review updatedReview = getReviewToChangeBot(reviewId);
//
//            if (updatedReview.getBot() == null) {
//                log.warn("5. Не удалось найти свободных ботов для отзыва ID {}", reviewId);
//            } else if (updatedReview.getBot().getId() != null && updatedReview.getBot().getId() == -1L) {
//                log.warn("5. Для отзыва ID {} установлен бот-заглушка (нет доступных ботов)", reviewId);
//            } else {
//                log.info("5. Установили нового рандомного бота для отзыва ID {}", reviewId);
//            }
//
//            reviewRepository.save(updatedReview);
//            log.info("6. Сохранили изменения в БД");
//
//        } catch (Exception e) {
//            log.error("Что-то пошло не так и бот не деактивирован", e);
//            throw new RuntimeException("Ошибка при деактивации и смене бота: " + e.getMessage());
//        }
//    }
//
//    private Review getReviewToChangeBot(Long reviewId) {
//        // Установка нового бота в отзыв
//        Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
//        if (reviewOptional.isEmpty()) {
//            log.error("1. Отзыв с ID {} не найден", reviewId);
//            throw new RuntimeException("Отзыв не найден");
//        }
//
//        Review review = reviewOptional.get();
//        log.info("2. Достали отзыв по id {}", reviewId);
//
//        List<Bot> bots = findAllBotsMinusFilial(review);
//        log.info("3. Найдено доступных ботов: {}", bots.size());
//
//        if (bots.isEmpty()) {
//            log.error("4. Нет доступных ботов для отзыва ID {}", reviewId);
//
//            // Создаем и устанавливаем бота-заглушку
//            Bot stubBot = createStubBot();
//            review.setBot(stubBot);
//            log.info("5. Установлен бот-заглушка для отзыва ID {}", reviewId);
//            return review;
//        }
//
//        var random = new SecureRandom();
//        int randomIndex = random.nextInt(bots.size());
//        Bot selectedBot = bots.get(randomIndex);
//        review.setBot(selectedBot);
//
//        log.info("5. Установлен новый бот для отзыва ID {}: бот ID {}, имя: {}",
//                reviewId, selectedBot.getId(), selectedBot.getFio());
//
//        return review;
//    }
//
//    // Метод для создания бота-заглушки (если нужно)
//    private Bot createStubBot() {
//        Bot stubBot = new Bot();
//        stubBot.setId(-1L); // ID -1 указывает на заглушку
//        stubBot.setFio("Нет доступных аккаунтов");
//        stubBot.setLogin("no_bots_available");
//        stubBot.setPassword("");
//        stubBot.setCounter(0);
//        stubBot.setActive(false);
//        return stubBot;
//    }
//
//    private boolean botActiveToFalse(Long botId) {
//        // Изменение статуса бота как НЕ активный
//        try {
//            // Проверяем, не является ли бот заглушкой или невалидным
//            if (botId == null || botId <= 0) {
//                log.debug("Пропускаем деактивацию бота с ID {} (невалидный ID или заглушка)", botId);
//                return false;
//            }
//
//            Optional<Bot> botOptional;
//            try {
//                botOptional = Optional.ofNullable(botService.findBotById(botId));
//            } catch (Exception e) {
//                log.error("Ошибка при поиске бота ID {}: {}", botId, e.getMessage());
//                return false;
//            }
//
//            if (botOptional.isEmpty()) {
//                log.error("1. Бот с ID {} не найден", botId);
//                return false;
//            }
//
//            Bot bot = botOptional.get();
//            boolean wasActive = bot.isActive();
//            bot.setActive(false);
//
//            try {
//                botService.save(bot);
//                log.info("2. Деактивировали бота {} (был активен: {})", botId, wasActive);
//                return true;
//            } catch (Exception e) {
//                log.error("Ошибка при сохранении бота ID {}: {}", botId, e.getMessage());
//                return false;
//            }
//
//        } catch (Exception e) {
//            log.error("3. Ошибка при деактивации бота {}: ", botId, e);
//            log.info("Что-то пошло не так и деактивация бота не случилась");
//            return false;
//        }
//    }
//
//    public List<Bot> findAllBotsMinusFilial(Review review) {
//        // Проверка входных параметров
//        if (review == null) {
//            log.error("Ошибка: review == null");
//            return Collections.emptyList();
//        }
//
//        Filial filial = review.getFilial();
//        if (filial == null) {
//            log.error("Ошибка: у review отсутствует filial");
//            return Collections.emptyList();
//        }
//
//        City city = filial.getCity();
//        if (city == null || city.getId() == null) {
//            log.error("Ошибка: у filial отсутствует город или его ID");
//            return Collections.emptyList();
//        }
//
//        log.info("Поиск ботов для отзыва ID {}, филиал ID {}, город: {}",
//                review.getId(), filial.getId(), city.getTitle());
//
//        // Получаем всех ботов для города
//        List<Bot> allBots;
//        try {
//            allBots = botService.getFindAllByFilialCityId(city.getId());
//        } catch (Exception e) {
//            log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
//            return Collections.emptyList();
//        }
//
//        if (allBots == null || allBots.isEmpty()) {
//            log.warn("Список ботов пуст для города: {}", city.getTitle());
//            return Collections.emptyList();
//        }
//
//        log.debug("Получено ботов из базы: {}", allBots.size());
//
//        // 1. Получаем ID ботов, которые уже использовались в этом филиале (ВСЕ отзывы)
//        Set<Long> usedBotIdsInThisFilial = getUsedBotIdsInFilial(filial, review.getId());
//        log.info("Ботов уже использованных в этом филиале: {}", usedBotIdsInThisFilial.size());
//
//        // 2. Получаем ID ботов, занятых в активных отзывах в других филиалах
//        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial, review.getId());
//        log.info("Ботов занятых в активных отзывах других филиалов: {}", usedBotIdsGlobally.size());
//
//        // Получаем значение vigul
//        boolean vigul = review.isVigul();
//        log.info("Отзыв ID {} имеет vigul = {}", review.getId(), vigul);
//
//        // Этап 1: Идеальные боты - не использовались в этом филиале и не заняты в других
//        List<Bot> idealBots = allBots.stream()
//                .filter(Objects::nonNull)
//                .filter(bot -> bot.getId() != null)
//                // Не использовались в этом филиале
//                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
//                // Не заняты в активных отзывах других филиалов
//                .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
//                .filter(bot -> {
//                    if (bot.getStatus() == null) return false;
//                    String statusTitle = bot.getStatus().getBotStatusTitle();
//                    return statusTitle != null && "Новый".equals(statusTitle.trim());
//                })
//                .collect(Collectors.toList());
//
//        log.info("Идеальных ботов (не в этом филиале, не заняты в других): {}", idealBots.size());
//
//        // Если есть идеальные боты, применяем фильтры vigul
//        if (!idealBots.isEmpty()) {
//            List<Bot> filteredBots = applyVigulFilters(idealBots, vigul);
//            if (!filteredBots.isEmpty()) {
//                log.info("Используем идеальных ботов: {}", filteredBots.size());
//                return filteredBots;
//            }
//        }
//
//        // Этап 2: Запасной режим - боты, занятые в других филиалах, но не в этом
//        List<Bot> fallbackBots = allBots.stream()
//                .filter(Objects::nonNull)
//                .filter(bot -> bot.getId() != null)
//                // КРИТИЧЕСКИ ВАЖНО: все равно исключаем ботов, которые уже были в этом филиале
//                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
//                .filter(bot -> {
//                    if (bot.getStatus() == null) return false;
//                    String statusTitle = bot.getStatus().getBotStatusTitle();
//                    return statusTitle != null && "Новый".equals(statusTitle.trim());
//                })
//                .collect(Collectors.toList());
//
//        log.info("Запасных ботов (не в этом филиале, но могут быть заняты в других): {}", fallbackBots.size());
//
//        if (!fallbackBots.isEmpty()) {
//            List<Bot> filteredBots = applyVigulFilters(fallbackBots, vigul);
//            if (!filteredBots.isEmpty()) {
//                log.warn("Используем запасных ботов (заняты в других филиалах): {}", filteredBots.size());
//                return filteredBots;
//            }
//        }
//
//        // Этап 3: Все боты использованы - возвращаем пустой список
//        log.error("ВСЕ боты уже использованы в этом филиале или не подходят по условиям vigul. Необходимо назначить бота-заглушку");
//        return Collections.emptyList();
//    }
//
//    /**
//     * Получает ID ботов, которые уже использовались в этом филиале (все отзывы, включая опубликованные)
//     */
//    private Set<Long> getUsedBotIdsInFilial(Filial filial, Long currentReviewId) {
//        Set<Long> usedBotIds = new HashSet<>();
//
//        try {
//            // Ищем ВСЕ отзывы этого филиала (и активные, и опубликованные)
//            List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);
//
//            if (allReviewsInFilial != null) {
//                for (Review existingReview : allReviewsInFilial) {
//                    // Исключаем текущий отзыв (при редактировании)
//                    if (existingReview != null &&
//                            existingReview.getId() != null &&
//                            !existingReview.getId().equals(currentReviewId) &&
//                            existingReview.getBot() != null &&
//                            existingReview.getBot().getId() != null) {
//
//                        usedBotIds.add(existingReview.getBot().getId());
//                        log.debug("Бот ID {} уже использовался в отзыве ID {} этого филиала",
//                                existingReview.getBot().getId(), existingReview.getId());
//                    }
//                }
//            }
//            log.info("Всего ботов, использованных в филиале {}: {}",
//                    filial.getId(), usedBotIds.size());
//
//        } catch (Exception e) {
//            log.error("Ошибка при получении использованных ботов для филиала {}", filial.getId(), e);
//        }
//
//        return usedBotIds;
//    }
//
//    /**
//     * Получает ID ботов, занятых в активных отзывах других филиалов
//     */
//    /**
//     * Получает ID ботов, занятых в активных отзывах других филиалов того же города
//     */
//    private Set<Long> getUsedBotIdsGlobally(Filial currentFilial, Long currentReviewId) {
//        Set<Long> usedBotIds = new HashSet<>();
//
//        try {
//            // Получаем город текущего филиала
//            City currentCity = currentFilial.getCity();
//            if (currentCity == null || currentCity.getId() == null) {
//                log.warn("У текущего филиала ID {} не указан город, пропускаем поиск занятых ботов", currentFilial.getId());
//                return usedBotIds;
//            }
//
//            log.debug("Ищем активные отзывы в городе ID: {}, название: {}",
//                    currentCity.getId(), currentCity.getTitle());
//
//            // 1. Находим все филиалы того же города
//            List<Filial> filialsInSameCity = filialRepository.findByCityId(currentCity.getId());
//            if (filialsInSameCity == null || filialsInSameCity.isEmpty()) {
//                log.debug("Нет других филиалов в городе ID {}", currentCity.getId());
//                return usedBotIds;
//            }
//
//            // 2. Собираем ID всех филиалов того же города (кроме текущего)
//            List<Long> otherFilialIdsInCity = filialsInSameCity.stream()
//                    .filter(filial -> filial != null && filial.getId() != null)
//                    .filter(filial -> !filial.getId().equals(currentFilial.getId()))
//                    .map(Filial::getId)
//                    .collect(Collectors.toList());
//
//            log.debug("Найдено других филиалов в том же городе: {}", otherFilialIdsInCity.size());
//
//            if (otherFilialIdsInCity.isEmpty()) {
//                log.debug("В городе только текущий филиал, нет других филиалов для проверки");
//                return usedBotIds;
//            }
//
//            // 3. Находим активные отзывы в этих филиалах
//            List<Review> activeReviewsInSameCity;
//            try {
//                // Используем метод репозитория, который ищет по списку ID филиалов
//                activeReviewsInSameCity = reviewRepository.findByPublishFalseAndBotIsNotNullAndFilialIdIn(otherFilialIdsInCity);
//
//                // ИЛИ если такого метода нет, можно использовать существующий метод и фильтровать в коде:
//                // activeReviewsInSameCity = reviewRepository.findByPublishFalseAndBotIsNotNull().stream()
//                //         .filter(review -> review.getFilial() != null &&
//                //                review.getFilial().getId() != null &&
//                //                otherFilialIdsInCity.contains(review.getFilial().getId()))
//                //         .collect(Collectors.toList());
//            } catch (Exception e) {
//                log.error("Ошибка при поиске активных отзывов в том же городе", e);
//                return usedBotIds;
//            }
//
//            if (activeReviewsInSameCity != null) {
//                for (Review existingReview : activeReviewsInSameCity) {
//                    // Исключаем текущий отзыв (при редактировании)
//                    if (existingReview != null &&
//                            existingReview.getId() != null &&
//                            !existingReview.getId().equals(currentReviewId) &&
//                            existingReview.getBot() != null &&
//                            existingReview.getBot().getId() != null) {
//
//                        usedBotIds.add(existingReview.getBot().getId());
//                        log.debug("Бот ID {} занят в активном отзыве ID {} другого филиала того же города",
//                                existingReview.getBot().getId(), existingReview.getId());
//                    }
//                }
//            }
//
//            log.info("Найдено активных отзывов с ботами в других филиалах того же города: {}",
//                    activeReviewsInSameCity != null ? activeReviewsInSameCity.size() : 0);
//            log.info("Ботов занятых в активных отзывах других филиалов того же города: {}", usedBotIds.size());
//
//        } catch (Exception e) {
//            log.error("Ошибка при получении глобально использованных ботов", e);
//        }
//
//        return usedBotIds;
//    }
//
//    /**
//     * Выделенный метод для применения фильтров по vigul
//     */
//    private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul) {
//        if (!vigul) {
//            // Для vigul = false
//            log.info("Фильтрация для vigul=false");
//
//            // Сначала ищем ботов с именем "Впиши Имя Фамилию"
//            List<Bot> strictFiltered = baseBots.stream()
//                    .filter(bot -> {
//                        if (bot.getFio() == null) {
//                            log.debug("Бот ID {} без имени", bot.getId());
//                            return false;
//                        }
//                        boolean hasCorrectName = "Впиши Имя Фамилию".equals(bot.getFio().trim());
//                        if (!hasCorrectName) {
//                            log.debug("Бот ID {} имеет имя '{}'", bot.getId(), bot.getFio());
//                        }
//                        return hasCorrectName;
//                    })
//                    .collect(Collectors.toList());
//
//            log.info("Ботов с именем 'Впиши Имя Фамилию': {}", strictFiltered.size());
//
//            if (!strictFiltered.isEmpty()) {
//                log.info("Используем ботов с именем 'Впиши Имя Фамилию'");
//                return strictFiltered;
//            }
//
//            log.warn("Нет ботов с именем 'Впиши Имя Фамилию', используем всех доступных ботов со статусом 'Новый'");
//            return baseBots;
//
//        } else {
//            // Для vigul = true
//            log.info("Фильтрация для vigul=true");
//
//            // Сначала ищем ботов с counter >= 3
//            List<Bot> strictFiltered = baseBots.stream()
//                    .filter(bot -> {
//                        Integer counter = bot.getCounter();
//                        if (counter == null) {
//                            counter = 0;
//                            log.debug("Бот ID {} без counter, считаем 0", bot.getId());
//                        }
//                        boolean hasEnoughCounter = counter >= 3;
//                        if (!hasEnoughCounter) {
//                            log.debug("Бот ID {} имеет counter={}", bot.getId(), counter);
//                        }
//                        return hasEnoughCounter;
//                    })
//                    .collect(Collectors.toList());
//
//            log.info("Ботов с counter >= 3: {}", strictFiltered.size());
//
//            if (!strictFiltered.isEmpty()) {
//                log.info("Используем ботов с counter >= 3");
//                return strictFiltered;
//            }
//
//            // Если нет ботов с counter >= 3, ищем с counter 0-2
//            List<Bot> fallbackFiltered = baseBots.stream()
//                    .filter(bot -> {
//                        Integer counter = bot.getCounter();
//                        if (counter == null) {
//                            counter = 0;
//                            log.debug("Бот ID {} без counter, считаем 0", bot.getId());
//                        }
//                        boolean hasValidCounter = counter >= 0 && counter <= 2;
//                        if (!hasValidCounter) {
//                            log.debug("Бот ID {} имеет counter={} (не в диапазоне 0-2)", bot.getId(), counter);
//                        }
//                        return hasValidCounter;
//                    })
//                    .collect(Collectors.toList());
//
//            log.info("Ботов с counter от 0 до 2: {}", fallbackFiltered.size());
//
//            if (!fallbackFiltered.isEmpty()) {
//                log.warn("Нет ботов с counter >= 3, используем ботов с counter от 0 до 2");
//                return fallbackFiltered;
//            }
//
//            // Если совсем нет ботов с подходящим counter, используем всех
//            log.warn("Нет ботов с подходящим counter, используем всех доступных ботов со статусом 'Новый'");
//            return baseBots;
//        }
//    }
//
//    // Дополнительный метод для проверки, не "перегружен" ли бот
//    private boolean isBotOverloaded(Bot bot) {
//        try {
//            // Находим, сколько активных отзывов у этого бота
//            List<Review> botActiveReviews = reviewRepository.findByBotAndPublishFalse(bot);
//
//            // Если у бота больше N активных отзывов, считаем его перегруженным
//            int maxActiveReviewsPerBot = 3; // Настройте по необходимости
//            return botActiveReviews != null && botActiveReviews.size() >= maxActiveReviewsPerBot;
//
//        } catch (Exception e) {
//            log.error("Ошибка при проверке загруженности бота ID {}", bot.getId(), e);
//            return false;
//        }
//    }
//
//    public ReviewDTOOne toReviewDTOOne(Review review) {
//        try {
//            OrderDetails orderDetails = review.getOrderDetails();
//            Bot bot = review.getBot();
//
//            // Проверяем, является ли бот заглушкой (ID = -1)
//            boolean isStubBot = bot != null && bot.getId() != null && bot.getId() == -1L;
//
//            // ====================== ЛОГИКА ДЛЯ ИМЕНИ БОТА ======================
//            String botFio;
//
//            if (orderDetails == null) {
//                // Если нет заказа - "НЕТ ЗАКАЗА"
//                botFio = "НЕТ ЗАКАЗА";
//            } else if (bot == null) {
//                // Если есть заказ, но бота нет - "Добавьте ботов и нажмите сменить"
//                botFio = "Добавьте аккаунты и нажмите сменить";
//            } else if (isStubBot) {
//                // Если это бот-заглушка - "Нет доступных ботов"
//                botFio = "Нет доступных аккаунтов";
//            } else {
//                // Если это обычный бот - берем его имя
//                botFio = Optional.ofNullable(bot.getFio())
//                        .filter(name -> !name.trim().isEmpty())
//                        .orElse("Бот без имени");
//            }
//            // ====================== КОНЕЦ ЛОГИКИ ДЛЯ ИМЕНИ БОТА ======================
//
//            // ====================== ДАННЫЕ О КОМПАНИИ И ЗАКАЗЕ ======================
//            String companyTitle = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getOrder)
//                    .map(Order::getCompany)
//                    .map(Company::getTitle)
//                    .orElse("НЕТ ЗАКАЗА");
//
//            Long companyId = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getOrder)
//                    .map(Order::getCompany)
//                    .map(Company::getId)
//                    .orElse(null);
//
//            UUID orderDetailsId = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getId)
//                    .orElse(null);
//
//            Long orderId = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getOrder)
//                    .map(Order::getId)
//                    .orElse(null);
//
//            String productTitle = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getProduct)
//                    .map(Product::getTitle)
//                    .orElse("НЕТ ПРОДУКТА");
//
//            String comment = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getComment)
//                    .orElse("");
//
//            String orderComments = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getOrder)
//                    .map(Order::getZametka)
//                    .orElse("");
//
//            String commentCompany = Optional.ofNullable(orderDetails)
//                    .map(OrderDetails::getOrder)
//                    .map(Order::getCompany)
//                    .map(Company::getCommentsCompany)
//                    .orElse("");
//            // ====================== КОНЕЦ ДАННЫХ О КОМПАНИИ И ЗАКАЗЕ ======================
//
//            // ====================== ДАННЫЕ БОТА ======================
//            Long botId;
//            String botLogin;
//            String botPassword;
//            Integer botCounter;
//
//            if (isStubBot) {
//                // Для бота-заглушки устанавливаем специальные значения
//                botId = -1L;
//                botLogin = "stub";
//                botPassword = "stub";
//                botCounter = 0;
//            } else if (bot != null) {
//                // Для обычного бота
//                botId = Optional.ofNullable(bot.getId()).orElse(0L);
//                botLogin = Optional.ofNullable(bot.getLogin()).orElse("none");
//                botPassword = Optional.ofNullable(bot.getPassword()).orElse("none");
//                botCounter = Optional.ofNullable(bot.getCounter()).orElse(0);
//            } else {
//                // Если бота нет (null)
//                botId = 0L;
//                botLogin = "none";
//                botPassword = "none";
//                botCounter = 0;
//            }
//            // ====================== КОНЕЦ ДАННЫХ БОТА ======================
//
//            // ====================== ДАННЫЕ РАБОТНИКА ======================
//            String workerFio = Optional.ofNullable(review.getWorker())
//                    .map(Worker::getUser)
//                    .map(User::getFio)
//                    .orElse("");
//
//            // Если worker не найден в Review, пробуем найти в Order
//            if (workerFio.isEmpty()) {
//                workerFio = Optional.ofNullable(orderDetails)
//                        .map(OrderDetails::getOrder)
//                        .map(Order::getManager)
//                        .map(Manager::getUser)
//                        .map(User::getFio)
//                        .orElse("");
//            }
//            // ====================== КОНЕЦ ДАННЫХ РАБОТНИКА ======================
//
//            // ====================== ДАННЫЕ ФИЛИАЛА ======================
//            String filialCity = Optional.ofNullable(review.getFilial())
//                    .map(Filial::getCity)
//                    .map(City::getTitle)
//                    .orElse("");
//
//            String filialTitle = Optional.ofNullable(review.getFilial())
//                    .map(Filial::getTitle)
//                    .orElse("");
//
//            String filialUrl = Optional.ofNullable(review.getFilial())
//                    .map(Filial::getUrl)
//                    .orElse("");
//            // ====================== КОНЕЦ ДАННЫХ ФИЛИАЛА ======================
//
//            // ====================== КАТЕГОРИИ ======================
//            String category = Optional.ofNullable(review.getCategory())
//                    .map(Category::getCategoryTitle)
//                    .orElse("Нет категории");
//
//            String subCategory = Optional.ofNullable(review.getSubCategory())
//                    .map(SubCategory::getSubCategoryTitle)
//                    .orElse("Нет подкатегории");
//            // ====================== КОНЕЦ КАТЕГОРИЙ ======================
//
//            // ====================== ДАТЫ ======================
//            // Безопасное получение дат с значениями по умолчанию
//            LocalDate created = review.getCreated() != null ? review.getCreated() : LocalDate.now();
//            LocalDate changed = review.getChanged() != null ? review.getChanged() : created;
//            LocalDate publishedDate = review.getPublishedDate() != null ? review.getPublishedDate() : LocalDate.now();
//            // ====================== КОНЕЦ ДАТ ======================
//
//            // ====================== СОЗДАНИЕ DTO ======================
//            return ReviewDTOOne.builder()
//                    .id(review.getId())
//                    .companyId(companyId)
//                    .commentCompany(commentCompany)
//                    .orderDetailsId(orderDetailsId)
//                    .orderId(orderId)
//                    .text(review.getText() != null ? review.getText() : "")
//                    .answer(review.getAnswer() != null ? review.getAnswer() : "")
//                    .category(category)
//                    .subCategory(subCategory)
//                    .botId(botId)
//                    .botFio(botFio)
//                    .botLogin(botLogin)
//                    .botPassword(botPassword)
//                    .botCounter(botCounter)
//                    .companyTitle(companyTitle)
//                    .productTitle(productTitle)
//                    .filialCity(filialCity)
//                    .filialTitle(filialTitle)
//                    .filialUrl(filialUrl)
//                    .workerFio(workerFio)
//                    .created(created)
//                    .changed(changed)
//                    .publishedDate(publishedDate)
//                    .publish(review.isPublish())
//                    .vigul(review.isVigul())
//                    .comment(comment)
//                    .orderComments(orderComments)
//                    .product(review.getProduct())
//                    .url(review.getUrl() != null ? review.getUrl() : "")
//                    .build();
//            // ====================== КОНЕЦ СОЗДАНИЯ DTO ======================
//
//        } catch (Exception e) {
//            log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
//                    review != null ? review.getId() : "null", e.getMessage(), e);
//
//            // Возвращаем DTO с информацией об ошибке
//            return ReviewDTOOne.builder()
//                    .id(review != null ? review.getId() : 0L)
//                    .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
//                    .botFio("ОШИБКА")
//                    .text(review != null && review.getText() != null ? review.getText() : "Не удалось загрузить данные отзыва")
//                    .build();
//        }
//    }


    public ReviewDTO getReviewDTOById(Long reviewId) {
        Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
        if (reviewOptional.isEmpty()) {
            log.error("Отзыв с ID {} не найден", reviewId);
            return null;
        }

        Review review = reviewOptional.get();
        return convertToReviewDTO(review);
    } // Взять дто отзыв по Id

    private ReviewDTO convertToReviewDTO(Review review) {
        if (review == null) {
            log.error("Попытка преобразования null Review в DTO");
            return null;
        }

        OrderDetails orderDetails = review.getOrderDetails();
        Bot bot = review.getBot();

        // ОДНА И ТА ЖЕ логика для имени бота
        boolean isStubBot = bot != null && bot.getId() != null && STUB_BOT_ID.equals(bot.getId());
        String botName = getBotName(orderDetails, bot, isStubBot);

        // Безопасное создание BotDTO
        BotDTO botDTO = null;
        if (bot != null && !isStubBot) {
            botDTO = convertToBotDTO(bot);
        }

        // Безопасное получение данных
        String comment = "";
        UUID orderDetailsId = null;
        if (orderDetails != null) {
            comment = Optional.ofNullable(orderDetails.getComment()).orElse("");
            orderDetailsId = orderDetails.getId();
        }

        return ReviewDTO.builder()
                .id(review.getId())
                .text(Optional.ofNullable(review.getText()).orElse(""))
                .answer(Optional.ofNullable(review.getAnswer()).orElse(""))
                .created(Optional.ofNullable(review.getCreated()).orElse(LocalDate.now()))
                .changed(Optional.ofNullable(review.getChanged()).orElse(LocalDate.now()))
                .publishedDate(Optional.ofNullable(review.getPublishedDate()).orElse(LocalDate.now()))
                .publish(review.isPublish())
                .category(convertToCategoryDto(review.getCategory()))
                .subCategory(convertToSubCategoryDto(review.getSubCategory()))
                .bot(botDTO)
                .botName(botName)
                .filial(convertToFilialDTO(review.getFilial()))
                .orderDetails(convertToDetailsDTO(orderDetails))
                .worker(convertToWorkerDTO(review.getWorker()))
                .comment(comment)
                .orderDetailsId(orderDetailsId)
                .product(review.getProduct())
                .price(review.getPrice())
                .url(Optional.ofNullable(review.getUrl()).orElse(""))
                .build();
    }

    // Единый метод для получения имени бота
    private String getBotName(OrderDetails orderDetails, Bot bot, boolean isStubBot) {
        if (orderDetails == null) {
            return "НЕТ ЗАКАЗА";
        } else if (bot == null) {
            return "Добавьте аккаунты и нажмите сменить";
        } else if (isStubBot) {
            return "Нет доступных аккаунтов";
        } else {
            return Optional.ofNullable(bot.getFio())
                    .filter(name -> !name.trim().isEmpty())
                    .orElse("Бот без имени");
        }
    }

    private BotDTO convertToBotDTO(Bot bot) {
        if (bot == null) {
            return null;
        }

        // Если это бот-заглушка, возвращаем специальный DTO
        if (STUB_BOT_ID.equals(bot.getId())) {
            return BotDTO.builder()
                    .id(STUB_BOT_ID)
                    .login("stub")
                    .password("stub")
                    .fio("Нет доступных аккаунтов")
                    .active(false)
                    .counter(0)
                    .status("Заглушка")
                    .worker(null)
                    .build();
        }

        // Для обычного бота с безопасным получением данных
        return BotDTO.builder()
                .id(bot.getId())
                .login(Optional.ofNullable(bot.getLogin()).orElse(""))
                .password(Optional.ofNullable(bot.getPassword()).orElse(""))
                .fio(Optional.ofNullable(bot.getFio()).orElse("Аккаунт без имени"))
                .active(bot.isActive())
                .counter(Optional.of(bot.getCounter()).orElse(0)) // Здесь тоже исправляем
                .status(bot.getStatus() != null ?
                        Optional.ofNullable(bot.getStatus().getBotStatusTitle()).orElse("Неизвестен") :
                        "Неизвестен")
                .worker(bot.getWorker())
                .build();
    }// Перевод отзыва в дто




//    public ReviewDTO getReviewDTOById(Long reviewId){ // Взять дто отзыв по Id
//        Review review = reviewRepository.findById(reviewId).orElse(null);
//        assert review != null;
//        return ReviewDTO.builder()
//                .id(review.getId())
//                .text(review.getText())
//                .answer(review.getAnswer())
//                .created(review.getCreated())
//                .changed(review.getChanged())
//                .publishedDate(review.getPublishedDate())
//                .publish(review.isPublish())
//                .category(convertToCategoryDto(review.getCategory()))
//                .subCategory(convertToSubCategoryDto(review.getSubCategory()))
//                .bot(convertToBotDTO(review.getBot()))
//                .botName(review.getBot() != null && review.getBot().getFio() != null? review.getBot().getFio() : "Добавьте ботов и нажмите сменить")
//                .filial(convertToFilialDTO(review.getFilial()))
//                .orderDetails(convertToDetailsDTO(review.getOrderDetails()))
//                .worker(convertToWorkerDTO(review.getWorker()))
//                .comment(review.getOrderDetails().getComment())
//                .orderDetailsId(review.getOrderDetails().getId())
//                .product(review.getProduct())
//                .price(review.getPrice())
//                .url(review.getUrl())
//                .build();
//    } // Взять дто отзыв по Id

    //    ============================================== CONVERTER TO DTO ==============================================
    private List<ReviewDTO> convertToReviewDTOList(List<Review> reviews){ // Перевод отзыва в дто
            return reviews.stream().map(this::convertToReviewDTO).collect(Collectors.toList());
        } // Перевод отзыва в дто
//    private ReviewDTO convertToReviewDTO(Review review){ // Перевод отзыва в дто
//            assert review != null;
//            return ReviewDTO.builder()
//                    .id(review.getId())
//                    .text(review.getText())
//                    .answer(review.getAnswer())
//                    .created(review.getCreated())
//                    .changed(review.getChanged())
//                    .publishedDate(review.getPublishedDate())
//                    .publish(review.isPublish())
//                    .category(convertToCategoryDto(review.getCategory()))
//                    .subCategory(convertToSubCategoryDto(review.getSubCategory()))
//                    .bot(convertToBotDTO(review.getBot()))
//                    .filial(convertToFilialDTO(review.getFilial()))
//                    .orderDetails(convertToDetailsDTO(review.getOrderDetails()))
//                    .worker(convertToWorkerDTO(review.getWorker()))
//                    .comment(review.getOrderDetails().getComment())
//                    .orderDetailsId(review.getOrderDetails().getId())
//                    .product(review.getProduct())
//                    .url(review.getUrl())
//                    .build();
//        } // Перевод отзыва в дто
    private CategoryDTO convertToCategoryDto(Category category) { // Перевод категории в дто
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    } // Перевод категории в дто
    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) { // Перевод подкатегории в дто
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        return subCategoryDTO;
    } // Перевод подкатегории в дто
    private FilialDTO convertToFilialDTO(Filial filial){ // Перевод филиала в дто
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    } // Перевод филиала в дто
//    private BotDTO convertToBotDTO(Bot bot){ // Перевод бота в дто
//        log.info("Перевод Бота в дто");
//        return BotDTO.builder()
//                .id(bot.getId())
//                .login(bot.getLogin())
//                .password(bot.getPassword())
//                .fio(bot.getFio())
//                .active(bot.isActive())
//                .counter(bot.getCounter())
//                .status(bot.getStatus().getBotStatusTitle())
//                .worker(bot.getWorker() != null ? bot.getWorker() : null)
//                .build();
//    } // Перевод бота в дто
    private WorkerDTO convertToWorkerDTO(Worker worker){ // Перевод работника в дто
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    } // Перевод работника в дто
    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details){ // Перевод деталей в дто
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    } // Перевод деталей в дто
    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){ // Перевод деталей в дто
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .comment(orderDetails.getComment())
                .build();
    } // Перевод деталей в дто
    private ProductDTO convertToProductDTO(Product product){ // Перевод продуктов в дто
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    } // Перевод продуктов в дто
    private OrderDTO convertToOrderDTO(Order order){ // Перевод заказа в дто
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .build();
    } // Перевод заказа в дто
    private CompanyDTO convertToCompanyDTO(Company company){ // Перевод компании в дто
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .build();
    } // Перевод компании в дто
    public Review getReviewById(Long reviewId){  // Взять отзыв по Id
        Review review = reviewRepository.findById(reviewId).orElse(null);
        assert review != null;
        return review;
    } // Взять отзыв по Id
    //    ============================================== CONVERTER TO DTO ==============================================

    //    ============================================ CONVERTER TO ENTITY =============================================
    private Category convertCategoryDTOToCategory(CategoryDTO categoryDTO){ // Перевод категории дто в сущность
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // Перевод категории дто в сущность
    private SubCategory convertSubCompanyDTOToSubCategory(SubCategoryDTO subCategoryDTO){ // Перевод подкатегории дто в сущность
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    } // Перевод подкатегории дто в сущность
    //    ============================================ CONVERTER TO ENTITY =============================================






    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByPublishedDateAndPublish(localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(
                reviewPage.stream()
                        .sorted(Comparator.comparing(Review::getPublishedDate))
                        .filter(review ->
                                !review.isVigul() &&
                                        // Если бот есть, проверяем counter < 2, если бота нет - пропускаем
                                        (review.getBot() == null || review.getBot().getCounter() < 2)
                        )
                        .toList(),
                pageNumber,
                pageSize
        );
    }  // Берем все заказы с поиском по названию компании или номеру

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Работника
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByWorkerAndPublishedDateAndPublish(worker, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(
                reviewPage.stream()
                        .sorted(Comparator.comparing(Review::getPublishedDate))
                        .filter(review ->
                                !review.isVigul() &&
                                        // Если бот есть, проверяем counter < 2, если бота нет - пропускаем
                                        (review.getBot() == null || review.getBot().getCounter() < 2)
                        )
                        .toList(),
                pageNumber,
                pageSize
        );
    } // Берем все отзывы с датой для Работника

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByManagersAndPublishedDateAndPublish(manager.getUser().getWorkers(), localDate);
        // ВАЖНО: Фильтруем только те отзывы, которые имеют orderDetails
        // ИЗМЕНЕНИЕ: Показываем все отзывы, даже без orderDetails
        reviewPage = reviewRepository.findAll(reviewId).stream()
                .filter(review -> {
                    // Если нет orderDetails - все равно показываем
                    if (review.getOrderDetails() == null) {
                        return true;
                    }

                    // Если orderDetails есть, проверяем менеджера
                    // Добавляем проверки на все звенья цепочки
                    if (review.getOrderDetails().getOrder() == null) {
                        // Заказ есть, но детали заказа null
                        return true;
                    }

                    if (review.getOrderDetails().getOrder().getManager() == null) {
                        // Заказ есть, но менеджер не назначен
                        return true;
                    }

                    // Проверяем, принадлежит ли менеджеру
                    return review.getOrderDetails().getOrder().getManager().equals(manager);
                })
                .toList();
        return getPageReviews(
                reviewPage.stream()
                        .sorted(Comparator.comparing(Review::getPublishedDate))
                        .filter(review ->
                                !review.isVigul() &&
                                        // Если бот есть, проверяем counter < 2, если бота нет - пропускаем
                                        (review.getBot() == null || review.getBot().getCounter() < 2)
                        )
                        .toList(),
                pageNumber,
                pageSize
        );
    } // Берем все отзывы с датой для Менеджера

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Владельца
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByOwnersAndPublishedDateAndPublish(workerList, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(
                reviewPage.stream()
                        .sorted(Comparator.comparing(Review::getPublishedDate))
                        .filter(review ->
                                !review.isVigul() &&
                                        // Если бот есть, проверяем counter < 2, если бота нет - пропускаем
                                        (review.getBot() == null || review.getBot().getCounter() < 2)
                        )
                        .toList(),
                pageNumber,
                pageSize
        );

    }

    @Override
    public void changeNagulReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        assert review != null;
        review.setVigul(true);
        reviewRepository.save(review);
    }

    public int countOrdersByWorkerAndStatusPublish(Worker worker, LocalDate localDate){
        int count = reviewRepository.countByWorkerAndStatusPublish(worker, localDate);
//        System.out.println(worker.getUser().getFio() + " " + count);
        return count;
    }

    public int countOrdersByWorkerAndStatusVigul(Worker worker, LocalDate localDate){
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByWorkerAndPublishedDateAndPublish(worker, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        int count = reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).filter(review -> !(review.isVigul()) && review.getBot().getCounter() < 2).toList().size();
//        System.out.println(worker.getUser().getFio() + " " + count);
        return count;
    }

    @Override
    public Map<String, Pair<Long, Long>> getAllPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate) {
        Map<String, Pair<Long, Long>> results = reviewRepository.findAllByPublishAndVigul(firstDayOfMonth, localDate, localDate.plusDays(2)).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0], // ФИО (работник) или ФИО (менеджер)
                        row -> Pair.of(((Number) row[2]).longValue(), ((Number) row[1]).longValue()) // totalReviews и vigulCount
                ));
//        System.out.println(results);

        return results;
    }


//    @Override
//    public Map<String, Pair<Long, Long>> getAllPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate) {
//        List<Object[]> results = reviewRepository.findAllByPublishAndVigul(firstDayOfMonth, localDate);
//
//        return results.stream()
//                .collect(Collectors.toMap(
//                        row -> (String) row[0],  // ФИО
//                        row -> Pair.of((Long) row[1], (Long) row[2]) // Количество всего, количество с isVigul = false
//                ));
//    }






    @Override
    public Map<String, Long> getAllReviewsToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = reviewRepository.getAllReviewsToMonth(firstDayOfMonth, lastDayOfMonth);

        // Создадим две карты: одну для работников, другую для менеджеров
        Map<String, Long> workerReviews = new HashMap<>();
        Map<String, Long> managerReviews = new HashMap<>();

        // Проходим по результатам и заполняем карты
        for (Object[] row : results) {
            String workerFio = (String) row[0];  // ФИО работника
            Long workerReviewCount = (Long) row[1];  // Количество отзывов работника

            String managerFio = (String) row[2];  // ФИО менеджера
            Long managerReviewCount = (Long) row[3];  // Количество отзывов менеджера

            // Обновляем карту работников
            workerReviews.merge(workerFio, workerReviewCount, Long::sum);

            // Обновляем карту менеджеров
            managerReviews.merge(managerFio, managerReviewCount, Long::sum);
        }

//         Для отладки выводим результаты
//        System.out.println("Отзывы по работникам: " + workerReviews);
//        System.out.println("Отзывы по менеджерам: " + managerReviews);

        // Возвращаем карту с результатами по работникам и менеджерам
        Map<String, Long> allReviews = new HashMap<>();
        allReviews.putAll(workerReviews);
        allReviews.putAll(managerReviews);
        return allReviews;
    }



}
