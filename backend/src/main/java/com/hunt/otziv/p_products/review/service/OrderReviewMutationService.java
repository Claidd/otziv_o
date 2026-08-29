package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.service.BotAssignmentService;
import com.hunt.otziv.p_products.service.ProductService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.bot.model.ReviewBotAssignmentMode;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstDetail;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderReviewMutationService {

    private static final long DEFAULT_ADDED_REVIEW_PRODUCT_ID = 2L;

    private final BotAssignmentService botAssignmentService;
    private final ReviewService reviewService;
    private final ProductService productService;
    private final CompanyService companyService;
    private final ReviewAccountWalkScheduleService accountWalkScheduleService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;
    private final OrderPayableRecalculationService payableRecalculationService;

    @Transactional
    public boolean addNewReview(Long orderId) {
        try {
            log.info("1. Зашли в добавление нового отзыва");

            Order saveOrder = orderAggregateMutationLockService.lock(orderId);
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId);
            assignmentMutationGuardService.assertOrder(orderId);

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

            Product product = productService.findById(DEFAULT_ADDED_REVIEW_PRODUCT_ID);
            if (product == null || product.getPrice() == null) {
                log.error(
                        "Не удалось добавить отзыв к заказу {}: продукт {} отсутствует или не имеет цены",
                        orderId,
                        DEFAULT_ADDED_REVIEW_PRODUCT_ID
                );
                return false;
            }

            Review draftReview = createNewReview(saveCompany, orderDetails, saveOrder, product);
            var selectedBot = botAssignmentService.assignBotForReviewChange(
                    draftReview,
                    Set.of(),
                    ReviewBotAssignmentMode.DEFAULT_ORDER_ASSIGNMENT
            );
            draftReview.setBot(selectedBot);
            botAssignmentService.updateReviewVigulBasedOnBotCounter(draftReview, selectedBot);
            accountWalkScheduleService.synchronizeAfterAccountChange(draftReview);
            Review review = reviewService.save(draftReview);
            log.info("3. Создали новый отзыв");

            List<Review> newList = Optional.ofNullable(orderDetails.getReviews()).orElse(new ArrayList<>());
            newList.add(review);
            orderDetails.setReviews(newList);

            payableRecalculationService.recalculate(orderDetails);
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

    @Transactional
    public boolean deleteNewReview(Long orderId, Long reviewId) {
        try {
            Order saveOrder = orderAggregateMutationLockService.lock(orderId);
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId);
            assignmentMutationGuardService.assertOrder(orderId);

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
            Review review = newList.stream()
                    .filter(Objects::nonNull)
                    .filter(candidate -> Objects.equals(reviewId, candidate.getId()))
                    .findFirst()
                    .orElse(null);
            if (review == null) {
                log.warn("Отзыв с ID '{}' не относится к заказу '{}'", reviewId, orderId);
                return false;
            }
            if (!reviewService.deleteReviewFromLockedOrder(orderId, reviewId)) {
                log.warn("Привязка отзыва '{}' к заказу '{}' изменилась", reviewId, orderId);
                return false;
            }

            newList.remove(review);
            orderDetails.setReviews(newList);

            payableRecalculationService.recalculate(orderDetails);
            log.info("2. Пересчитали детали и заказ");

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

    private Review createNewReview(Company company, OrderDetails orderDetails, Order order, Product product) {
        return Review.builder()
                .category(company != null ? company.getCategoryCompany() : null)
                .subCategory(company != null ? company.getSubCategory() : null)
                .text("Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(null)
                .filial(order != null ? order.getFilial() : null)
                .publish(false)
                .worker(order != null ? order.getWorker() : null)
                .product(product)
                .price(product != null ? product.getPrice() : null)
                .build();
    }

}
