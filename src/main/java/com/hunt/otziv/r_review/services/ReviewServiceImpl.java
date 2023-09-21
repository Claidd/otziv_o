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
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService{

    private final ReviewRepository reviewRepository;
    private final BotService botService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final OrderDetailsService orderDetailsService;


    public Review save(Review review){
       return reviewRepository.save(review);
    }



    public boolean deleteReview(Long reviewId){
        reviewRepository.delete(Objects.requireNonNull(reviewRepository.findById(reviewId).orElse(null)));
        return true;
    }

    @Override
    public List<Review> getReviewsAllByOrderId(Long id) {
        return reviewRepository.findAllByOrderDetailsId(id);
    }



    //    ======================================== FILIAL UPDATE =========================================================
    // Обновить профиль отзыв - начало
    @Override
    @Transactional
    public void updateReview(ReviewDTO reviewDTO, Long reviewId) {
        log.info("2. Вошли в обновление данных Отзыв");
        Review saveReview = reviewRepository.findById(reviewId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        log.info("Достали Отзыв");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        System.out.println("comment: " + !Objects.equals(reviewDTO.getComment(), saveReview.getOrderDetails().getComment()));
        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        System.out.println("date publish: " + !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));

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
    }

//    =====================================================================================================

    //    ======================================== ORDER DETAIL AND REVIEW UPDATE =========================================================
    // Обновить профиль отзыв - начало
    @Override
    @Transactional
    public void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId) {
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа");
        Review saveReview = reviewRepository.findById(reviewId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        OrderDetails saveOrderDetails  = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
        log.info("Достали Отзыв");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        System.out.println("comment: " + !Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment()));
        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        System.out.println("date publish: " + !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));

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
    }

//    =====================================================================================================

//    ============================== ORDER DETAIL AND REVIEW UPDATE AND SET PUBLISH DATE ===============================
    // Обновить профиль отзыв - начало
    @Override
    @Transactional
    public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) {
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение даты публикации");
        System.out.println(orderDetailsDTO);
        System.out.println(orderDetailsDTO.getAmount());
        int plusDays = (30 / orderDetailsDTO.getAmount());
        LocalDate localDate = LocalDate.now();
        System.out.println(localDate);
        System.out.println(plusDays);
        try {
            OrderDetails saveOrderDetails  = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
            for (ReviewDTO reviewDTO : orderDetailsDTO.getReviews()) {

                checkUpdateReview(reviewDTO, localDate);
                log.info("Начинаем обновлять дату");
                localDate = localDate.plusDays(plusDays);
                log.info(" Обновили дату");
                System.out.println(localDate);
            }
            /*Замена комментария*/
            System.out.println("comment: " + !Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment()));
            if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())){ /*Проверка статус заказа*/
                log.info("Обновляем комментарий отзыва и Деталей Заказа");
                saveOrderDetails.setComment(orderDetailsDTO.getComment());
                orderDetailsService.save(saveOrderDetails);
            }
            log.info("Все прошло успешно вернулось TRUE");
            return true;
        }
        catch (Exception e){
            log.info("Все прошло успешно вернулось FALSE");
            return false;
        }


    }

//    =====================================================================================================

    private void checkUpdateReview(ReviewDTO reviewDTO, LocalDate localDate){
        Review saveReview = reviewRepository.findById(reviewDTO.getId()).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewDTO.getId())));
        log.info("Достали Отзыв");
        boolean isChanged = false;
        /*Временная проверка сравнений*/
        System.out.println(reviewDTO);

        System.out.println("text: " + !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        System.out.println("answer: " + !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        System.out.println("publish date: " + (!saveReview.isPublish()));
        System.out.println("active: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        System.out.println("date publish: " + !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));

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
    }

    @Override
    public void changeBot(Long id) {
        Review review = reviewRepository.findById(id).orElse(null);
        log.info("2. Достали отзыв по id" + id);
        if (review != null){
            List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(review.getOrderDetails().getOrder().getWorker().getId());
            var random = new SecureRandom();
            review.setBot(bots.get(random.nextInt(bots.size())));
            log.info("3. Установили нового рандомного бота");
            reviewRepository.save(review);
            log.info("4. Сохранили нового бота в отзыве в БД");
        }
        else {
            return;
        }
    }



    @Override
    public void deActivateAndChangeBot(Long reviewId, Long botId) {
        try {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        log.info("2. Достали отзыв по id" + reviewId);

            if (review != null){
                Bot bot = botService.findBotById(botId);
                bot.setActive(false);
                botService.save(bot);
                log.info("3. Дективировали бота" + reviewId);
                List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(review.getOrderDetails().getOrder().getWorker().getId());
                var random = new SecureRandom();
                review.setBot(bots.get(random.nextInt(bots.size())));
                log.info("4. Установили нового рандомного бота");
                reviewRepository.save(review);
                log.info("5. Сохранили нового бота в отзыве в БД");
            }
            else {
                log.info("Что-то пошло не так и бот не деактивирован");
            }
        }
        catch (Exception e){
            System.out.println(e);
            log.info("Что-то пошло не так и бот не деактивирован");
        }
    }

    //    ============================================== CONVERTER TO DTO ==============================================
    public ReviewDTO getReviewDTOById(Long reviewId){
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

    private FilialDTO convertToFilialDTO(Filial filial){
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private BotDTO convertToBotDTO(Bot bot){
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
    }

    private WorkerDTO convertToWorkerDTO(Worker worker){
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
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
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .comment(orderDetails.getComment())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product){
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }
    private OrderDTO convertToOrderDTO(Order order){
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company){
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .build();
    }

    public Review getReviewById(Long reviewId){
        Review review = reviewRepository.findById(reviewId).orElse(null);
        assert review != null;
        return review;
    }
    //    ============================================== CONVERTER TO DTO ==============================================



    //    ============================================ CONVERTER TO ENTITY =============================================
    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    }
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    }
    //    ============================================ CONVERTER TO ENTITY =============================================
}
