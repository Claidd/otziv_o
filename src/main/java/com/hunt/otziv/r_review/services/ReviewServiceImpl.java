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
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDate;
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


    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(int pageNumber, int pageSize){ // Берем все заказы с поиском по названию компании или номеру
        List<Long> reviewId;
        List<Review> reviewPage;
        LocalDate localDate = LocalDate.now();
        reviewId = reviewRepository.findAllByPublishedDateAndPublish(localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    }  // Берем все заказы с поиском по названию компании или номеру

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Работника
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        LocalDate localDate = LocalDate.now();
        reviewId = reviewRepository.findAllByWorkerAndPublishedDateAndPublish(worker, localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Работника

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Менеджера
        Manager manager = managerService.getManagerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        List<Long> reviewId;
        List<Review> reviewPage;
        LocalDate localDate = LocalDate.now();
        reviewId = reviewRepository.findAllByManagersAndPublishedDateAndPublish(manager.getUser().getWorkers(), localDate);
        reviewPage = reviewRepository.findAll(reviewId);
        return getPageReviews(reviewPage.stream().sorted(Comparator.comparing(Review::getPublishedDate)).toList(),pageNumber,pageSize);
    } // Берем все отзывы с датой для Менеджера

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(Principal principal, int pageNumber, int pageSize) { // Берем все отзывы с датой для Владельца
        List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        List<Long> reviewId;
        List<Review> reviewPage;
        LocalDate localDate = LocalDate.now();
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


    public Review save(Review review){ // Сохранить отзыв в БД
        if (!reviewRepository.existsByText(review.getText())) {
            log.info("1. Отзыв в БД отзывы сохранен");
            return reviewRepository.save(review);
        }
        if (review.getText().equals("Текст отзыва")){
            System.out.println(review.getText());
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
    public void updateReview(ReviewDTO reviewDTO, Long reviewId) { // Обновление отзывов
        log.info("2. Вошли в обновление данных Отзыв");
        Review saveReview = reviewRepository.findById(reviewId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        log.info("Достали Отзыв");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
//        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
//        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
//        System.out.println("comment: " + !Objects.equals(reviewDTO.getComment(), saveReview.getOrderDetails().getComment()));
//        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
//        System.out.println("date publish: " + !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));

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
        if (!Objects.equals(reviewDTO.getComment(), saveReview.getOrderDetails().getComment())){ /*Проверка статус заказа*/
            log.info("Обновляем комментарий отзыва");
            OrderDetails orderDetails = orderDetailsService.getOrderDetailById(reviewDTO.getOrderDetailsId());
            orderDetails.setComment(reviewDTO.getComment());
            orderDetailsService.save(orderDetails);
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
    } // Обновление отзывов

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

        /*Временная проверка сравнений*/
//        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
//        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
//        System.out.println("comment: " + !Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment()));
//        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
//        System.out.println("date publish: " + !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));

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
    public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) { // Обновление Деталей и Отзывов, Разрешение к публикации
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение даты публикации");
        log.info("{}", orderDetailsDTO);
        log.info("{}", orderDetailsDTO.getAmount());
        try {
            OrderDetails saveOrderDetails  = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
            int plusDays = (30 / orderDetailsDTO.getAmount());
            LocalDate localDate = LocalDate.now();

            for (ReviewDTO reviewDTO : orderDetailsDTO.getReviews()) {
                checkUpdateReview(reviewDTO, localDate);
                localDate = localDate.plusDays(plusDays);
                log.info(" Обновили дату " + localDate);

            }
            /*Замена комментария*/
            log.info("comment: " + !Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment()));
            if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())){ /*Проверка статус заказа*/
                log.info("Обновляем комментарий отзыва и Деталей Заказа");
                saveOrderDetails.setComment(orderDetailsDTO.getComment());
                orderDetailsService.save(saveOrderDetails);
            }
            log.info("Все прошло успешно вернулось TRUE");
            return true;
        }
        catch (Exception e){
            log.error("Все прошло успешно вернулось FALSE: {}", e.getMessage());
            return false;
        }
    } // Обновление Деталей и Отзывов, Разрешение к публикации

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

//    =================================== ЗАМЕНА И БЛОИРОКА БОТОВ ДЛЯ ЗАКАЗА ===========================================

    } // Проверка обновлений отзыва
    @Override
    public void changeBot(Long botId) { // Замена бота
        log.info("3. Установили нового рандомного бота");
        reviewRepository.save(getReviewToChangeBot(botId));
        log.info("4. Сохранили нового бота в отзыве в БД");
    } // Замена бота

    @Override
    public void deActivateAndChangeBot(Long reviewId, Long botId) { // метод деактивации бота
        try {
            botActiveToFalse(botId);
            log.info("4. Установили нового рандомного бота");
            reviewRepository.save(getReviewToChangeBot(reviewId));
            log.info("5. Сохранили нового бота в отзыве в БД");
        }
        catch (Exception e){
            System.out.println(e);
            log.info("Что-то пошло не так и бот не деактивирован");
        }
    } // Деактивация бота

    private Review getReviewToChangeBot(Long reviewId) { // Установка нового бота в отзыв
//        Review review = reviewRepository.findById(reviewId).orElse(null);
//        assert review != null;
//        log.info("2. Достали отзыв по id{}", reviewId);
//        List<Bot> bots = findAllBotsMinusFilial(review);
//        log.info("3. Достали ботов минус филиал");
//        var random = new SecureRandom();
//        review.setBot(bots.get(random.nextInt(bots.size())));
//        return review;

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
            System.out.println(e);
            log.info("Что-то пошло не так и деактивация бота не случилась");
        }
    } // Изменение статуса бота как НЕ активный

    private List<Bot> findAllBotsMinusFilial(Review review){ // Найти всех ботов за исключением тех, что уже есть у филиала
//        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(review.getOrderDetails().getOrder().getWorker().getId());
        List<Bot> bots = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId());
        System.out.println("Боты вытащенные из базы по определнному городу: " + bots.size());
        List<Review> reviewListFilial = reviewRepository.findAllByFilial(review.getFilial());
        List<Bot> botsCompany = reviewListFilial.stream().map(Review::getBot).toList();
        System.out.println("Боты вытащенные из базы по определнному городу для удаления: " +  botsCompany.size());
        bots.removeAll(botsCompany);
        System.out.println("Оставшиеся: " + bots.size());
        return bots;
    } // Найти всех ботов за исключением тех, что уже есть у филиала

//    ================================ ЗАМЕНА И БЛОИРОКА БОТОВ ДЛЯ ЗАКАЗА КОНЕЦ ========================================



    public ReviewDTOOne toReviewDTOOne(Review review){ // Взять дто отзыв по Id
        return ReviewDTOOne.builder()
                .id(review.getId())
                .companyId(review.getOrderDetails().getOrder().getCompany().getId())
                .commentCompany(review.getOrderDetails().getOrder().getCompany().getCommentsCompany())
                .orderDetailsId(review.getOrderDetails().getId())
                .orderId(review.getOrderDetails().getOrder().getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .category(review.getCategory().getCategoryTitle())
                .subCategory(review.getSubCategory().getSubCategoryTitle())
                .botId(review.getBot() != null && review.getBot().getId() != null ? review.getBot().getId() : 0)
                .botFio(review.getBot() != null && review.getBot().getFio() != null? review.getBot().getFio() : "Добавьте ботов и нажмите сменить")
                .botLogin(review.getBot() != null && review.getBot().getLogin() != null? review.getBot().getLogin() : "none")
                .botPassword(review.getBot() != null && review.getBot().getPassword() != null? review.getBot().getPassword() : "none")
                .botCounter(review.getBot() != null && review.getBot().getCounter() != 0 ? review.getBot().getCounter() : 0)
                .companyTitle(review.getOrderDetails().getOrder().getCompany().getTitle())
                .productTitle(review.getOrderDetails().getProduct().getTitle())
                .filialTitle(review.getFilial().getTitle())
                .filialUrl(review.getFilial().getUrl())
                .workerFio(review.getWorker().getUser().getFio())
                .created(review.getCreated())
                .changed(review.getChanged())
                .publishedDate(review.getPublishedDate())
                .publish(review.isPublish())
                .comment(review.getOrderDetails().getComment())
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
                .filial(convertToFilialDTO(review.getFilial()))
                .orderDetails(convertToDetailsDTO(review.getOrderDetails()))
                .worker(convertToWorkerDTO(review.getWorker()))
                .comment(review.getOrderDetails().getComment())
                .orderDetailsId(review.getOrderDetails().getId())
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
}
