package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
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
        List<ReviewDTOOne> ReviewDTOOnes = reviewPage.subList(start, end)
                .stream()
                .map(this::toReviewDTOOne)
                .collect(Collectors.toList());
        return new PageImpl<>(ReviewDTOOnes, pageable, reviewPage.size());
    }

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
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение даты публикации");

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
            LocalDate endDate = startDate.plusDays(30);

            long totalDays = ChronoUnit.DAYS.between(startDate, endDate);

            BigDecimal step = BigDecimal.valueOf(totalDays)
                    .divide(BigDecimal.valueOf(totalReviews - 1), 2, RoundingMode.HALF_UP);
            BigDecimal currentOffset = BigDecimal.ZERO;

            for (int i = 0; i < totalReviews; i++) {
                ReviewDTO reviewDTO = orderDetailsDTO.getReviews().get(i);
                long daysToAdd = currentOffset.setScale(0, RoundingMode.HALF_UP).longValue();
                LocalDate publishDate = startDate.plusDays(daysToAdd);

                checkUpdateReview(reviewDTO, publishDate);
                log.info("Обновили дату публикации отзыва №{}: {}", i + 1, publishDate);

                currentOffset = currentOffset.add(step);
            }

            if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
                log.info("Обновляем комментарий отзыва и Деталей Заказа");
                saveOrderDetails.setComment(orderDetailsDTO.getComment());
                orderDetailsService.save(saveOrderDetails);
            }

            log.info("Все прошло успешно, даты публикаций установлены равномерно, возвращаем TRUE");
            return true;
        } catch (Exception e) {
            log.error("Ошибка обновления данных, даты публикаций НЕ установлены: ", e);
            return false;
        }
    }


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


    @Override
    public void changeBot(Long botId) { // Замена бота
        log.info("3. Установили нового рандомного бота");
        reviewRepository.save(getReviewToChangeBot(botId));
        log.info("4. Сохранили нового бота в отзыве в БД");
    } // Замена бота

    @Override
    public void deActivateAndChangeBot(Long reviewId, Long botId) { // Деактивация бота
        try {
            Review review = reviewRepository.findById(reviewId).orElse(null);
            if (review == null) {
                log.warn("Отзыв с id {} не найден", reviewId);
                return;
            }

//            log.info("ОТПРАВКА СООБЩЕНИЯ О ДЕАКТИВАЦИИ");
            try {
                int botCount = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId()).size();
                if (botCount < 20) {
                    String textMail = "Город: " + review.getFilial().getCity().getTitle() +  ". Остаток у города: " + botCount;
                    emailService.sendSimpleEmail("o-company-server@mail.ru", "Мало аккаунтов у города", "Необходимо добавить ботов для: " + textMail);
                    log.info("ОТПРАВКА МЕЙЛА О МАЛОМ КОЛИЧЕСТВЕ БОТОВ - УСПЕХ");
                } else {
                    log.info("ПИСЬМО не отправлялось - у города достаточно аккаунтов");
                }
            } catch (Exception e){
                log.error("Сообщение о деактивации бота не отправилось", e);
            }

            botActiveToFalse(botId);
            log.info("4. Установили нового рандомного бота");

            reviewRepository.save(getReviewToChangeBot(reviewId));
            log.info("5. Сохранили нового бота в отзыве в БД");

        } catch (Exception e){
            log.error("Что-то пошло не так и бот не деактивирован", e);
        }
    } // Деактивация бота




    private Review getReviewToChangeBot(Long reviewId) { // Установка нового бота в отзыв
        Review review = reviewRepository.findById(reviewId).orElse(null);
        assert review != null;
        log.info("2. Достали отзыв по id{}", reviewId);
        List<Bot> bots = findAllBotsMinusFilial(review);
        log.info("3. Достали ботов минус филиал");
        var random = new SecureRandom();
        review.setBot(bots.get(random.nextInt(bots.size())));
        return review;
    } // Установка нового бота в отзыв

    private void botActiveToFalse(Long botId){ // Изменение статуса бота как НЕ активный
        try {
            Bot bot = botService.findBotById(botId);
            bot.setActive(false);
            botService.save(bot);
            log.info("3. Дективировали бота {}", botId);
        }
        catch (Exception e){
            log.error("e: ", e);
            log.info("Что-то пошло не так и деактивация бота не случилась");
        }
    } // Изменение статуса бота как НЕ активный

    private List<Bot> findAllBotsMinusFilial(Review review) {
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

        List<Bot> bots;
        try {
            bots = botService.getFindAllByFilialCityId(city.getId());
        } catch (Exception e) {
            log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
            return Collections.emptyList();
        }

        log.info("Боты вытащенные из базы по городу: {} кол-во: {}", city.getTitle(), bots != null ? bots.size() : 0);

        if (bots == null || bots.isEmpty()) {
            log.warn("Список ботов пуст или null");
            return Collections.emptyList();
        }

        List<Review> reviewListFilial;
        try {
            reviewListFilial = reviewRepository.findAllByFilial(filial);
        } catch (Exception e) {
            log.error("Ошибка при получении отзывов по филиалу: {}", filial.getId(), e);
            return Collections.emptyList();
        }

        if (reviewListFilial == null) {
            log.warn("Список отзывов филиала null");
            reviewListFilial = Collections.emptyList();
        }

        List<Bot> botsCompany = reviewListFilial.stream()
                .map(Review::getBot)
                .filter(Objects::nonNull)
                .toList();

        log.info("Боты уже использованные в этом городе (для удаления из списка): {}", botsCompany.size());

        bots.removeAll(botsCompany);
        log.info("Оставшиеся: {}", bots.size());

        return bots;
    }



//    ================================ ЗАМЕНА И БЛОИРОКА БОТОВ ДЛЯ ЗАКАЗА КОНЕЦ ========================================



    public ReviewDTOOne toReviewDTOOne(Review review){ // Взять дто отзыв по Id
        OrderDetails orderDetails = review.getOrderDetails();
        if (orderDetails == null) {
            log.warn("Review ID {} has no associated OrderDetails", review.getId());
            log.warn("Review ID {} has no associated OrderDetails", review.getOrderDetails().getId());

            return null; // или обработать это иначе
        }
        return ReviewDTOOne.builder()
                .id(review.getId())
                .companyId(review.getOrderDetails().getOrder().getCompany().getId())
                .commentCompany(review.getOrderDetails().getOrder().getCompany().getCommentsCompany())
                .orderDetailsId(review.getOrderDetails().getId())
                .orderId(review.getOrderDetails().getOrder().getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .category(review.getCategory() != null ? review.getCategory().getCategoryTitle() : "Нет категории")
                .subCategory(review.getSubCategory() != null ? review.getSubCategory().getSubCategoryTitle() : "Нет подкатегории")
                .botId(review.getBot() != null && review.getBot().getId() != null ? review.getBot().getId() : 0)
                .botFio(review.getBot() != null && review.getBot().getFio() != null? review.getBot().getFio() : "Добавьте ботов и нажмите сменить")
                .botLogin(review.getBot() != null && review.getBot().getLogin() != null? review.getBot().getLogin() : "none")
                .botPassword(review.getBot() != null && review.getBot().getPassword() != null? review.getBot().getPassword() : "none")
                .botCounter(review.getBot() != null && review.getBot().getCounter() != 0 ? review.getBot().getCounter() : 0)
                .companyTitle(review.getOrderDetails().getOrder().getCompany().getTitle())
                .productTitle(review.getOrderDetails().getProduct().getTitle())
                .filialCity(review.getFilial().getCity().getTitle())
                .filialTitle(review.getFilial().getTitle())
                .filialUrl(review.getFilial().getUrl())
                .workerFio(review.getWorker().getUser().getFio())
                .created(review.getCreated())
                .changed(review.getChanged())
                .publishedDate(review.getPublishedDate())
                .publish(review.isPublish())
                .vigul(review.isVigul())
                .comment(review.getOrderDetails().getComment())
                .orderComments(review.getOrderDetails().getOrder().getZametka())
                .product(review.getProduct())
                .url(review.getUrl())
                .build();
    }  // Взять дто отзыв по Id


    public ReviewDTO getReviewDTOById(Long reviewId){ // Взять дто отзыв по Id
        Review review = reviewRepository.findById(reviewId).orElse(null);
        assert review != null;
        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .created(review.getCreated())
                .changed(review.getChanged())
                .publishedDate(review.getPublishedDate())
                .publish(review.isPublish())
                .category(convertToCategoryDto(review.getCategory()))
                .subCategory(convertToSubCategoryDto(review.getSubCategory()))
                .bot(convertToBotDTO(review.getBot()))
                .botName(review.getBot().getFio())
                .filial(convertToFilialDTO(review.getFilial()))
                .orderDetails(convertToDetailsDTO(review.getOrderDetails()))
                .worker(convertToWorkerDTO(review.getWorker()))
                .comment(review.getOrderDetails().getComment())
                .orderDetailsId(review.getOrderDetails().getId())
                .product(review.getProduct())
                .price(review.getPrice())
                .url(review.getUrl())
                .build();
    } // Взять дто отзыв по Id

    //    ============================================== CONVERTER TO DTO ==============================================
    private List<ReviewDTO> convertToReviewDTOList(List<Review> reviews){ // Перевод отзыва в дто
            return reviews.stream().map(this::convertToReviewDTO).collect(Collectors.toList());
        } // Перевод отзыва в дто
    private ReviewDTO convertToReviewDTO(Review review){ // Перевод отзыва в дто
            assert review != null;
            return ReviewDTO.builder()
                    .id(review.getId())
                    .text(review.getText())
                    .answer(review.getAnswer())
                    .created(review.getCreated())
                    .changed(review.getChanged())
                    .publishedDate(review.getPublishedDate())
                    .publish(review.isPublish())
                    .category(convertToCategoryDto(review.getCategory()))
                    .subCategory(convertToSubCategoryDto(review.getSubCategory()))
                    .bot(convertToBotDTO(review.getBot()))
                    .filial(convertToFilialDTO(review.getFilial()))
                    .orderDetails(convertToDetailsDTO(review.getOrderDetails()))
                    .worker(convertToWorkerDTO(review.getWorker()))
                    .comment(review.getOrderDetails().getComment())
                    .orderDetailsId(review.getOrderDetails().getId())
                    .product(review.getProduct())
                    .url(review.getUrl())
                    .build();
        } // Перевод отзыва в дто
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
    private BotDTO convertToBotDTO(Bot bot){ // Перевод бота в дто
        log.info("Перевод Бота в дто");
        return BotDTO.builder()
                .id(bot.getId())
                .login(bot.getLogin())
                .password(bot.getPassword())
                .fio(bot.getFio())
                .active(bot.isActive())
                .counter(bot.getCounter())
                .status(bot.getStatus().getBotStatusTitle())
                .worker(bot.getWorker() != null ? bot.getWorker() : null)
                .build();
    } // Перевод бота в дто
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
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).filter(review -> !(review.isVigul()) && review.getBot().getCounter() < 2).toList(),pageNumber,pageSize);
    }  // Берем все заказы с поиском по названию компании или номеру

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Работника
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByWorkerAndPublishedDateAndPublish(worker, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).filter(review -> !(review.isVigul()) && review.getBot().getCounter() < 2).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Работника

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByManagersAndPublishedDateAndPublish(manager.getUser().getWorkers(), localDate);
        reviewPage = reviewRepository.findAll(reviewId).stream().filter(review -> review.getOrderDetails().getOrder().getManager().equals(manager)).collect(Collectors.toList());
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).filter(review -> !(review.isVigul()) && review.getBot().getCounter() < 2).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Менеджера

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Владельца
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        List<Long> reviewId;
        List<Review> reviewPage;
        reviewId = reviewRepository.findAllByOwnersAndPublishedDateAndPublish(workerList, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).filter(review -> !(review.isVigul()) && review.getBot().getCounter() < 2).toList(), pageNumber, pageSize);

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
