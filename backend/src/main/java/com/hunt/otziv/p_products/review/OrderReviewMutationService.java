package com.hunt.otziv.p_products.review;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstDetail;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderReviewMutationService {

    private final OrderRepository orderRepository;
    private final OrderDetailsService orderDetailsService;
    private final BotService botService;
    private final ReviewService reviewService;
    private final CompanyService companyService;

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

    private Review createNewReview(Company company, OrderDetails orderDetails, Order order) {
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
}
