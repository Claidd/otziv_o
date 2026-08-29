package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rebuilds every materialized order total from its reviews after a payable mutation.
 */
@Service
@RequiredArgsConstructor
public class OrderPayableRecalculationService {

    private final OrderDetailsService orderDetailsService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculate(OrderDetails changedDetail) {
        if (changedDetail == null || changedDetail.getOrder() == null) {
            return;
        }

        Order order = changedDetail.getOrder();
        List<OrderDetails> details = orderDetails(order, changedDetail);
        BigDecimal orderTotal = BigDecimal.ZERO;
        int orderAmount = 0;

        for (OrderDetails detail : details) {
            if (detail == null) {
                continue;
            }
            List<Review> reviews = Optional.ofNullable(detail.getReviews())
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(Objects::nonNull)
                    .toList();
            BigDecimal detailTotal = reviews.stream()
                    .map(Review::getPrice)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            detail.setAmount(reviews.size());
            detail.setPrice(detailTotal);
            homogeneousProduct(reviews).ifPresent(detail::setProduct);
            orderDetailsService.save(detail);

            orderAmount += reviews.size();
            orderTotal = orderTotal.add(detailTotal);
        }

        order.setAmount(orderAmount);
        order.setSum(orderTotal);
        orderDetailsService.saveOrder(order);
        if (order.getId() != null) {
            eventPublisher.publishEvent(new OrderPayableChangedEvent(order.getId()));
        }
    }

    private List<OrderDetails> orderDetails(Order order, OrderDetails changedDetail) {
        List<OrderDetails> details = new ArrayList<>();
        if (order.getDetails() != null) {
            details.addAll(order.getDetails());
        }
        if (details.isEmpty() && order.getId() != null) {
            details.addAll(orderDetailsService.getOrderDetailsForReviewCheckByOrderId(order.getId()));
        }
        if (details.stream().noneMatch(detail -> sameDetail(detail, changedDetail))) {
            details.add(changedDetail);
        }
        return details;
    }

    private boolean sameDetail(OrderDetails left, OrderDetails right) {
        if (left == right) {
            return true;
        }
        return left != null
                && right != null
                && left.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    private Optional<Product> homogeneousProduct(List<Review> reviews) {
        if (reviews.isEmpty() || reviews.stream().anyMatch(review -> review.getProduct() == null)) {
            return Optional.empty();
        }
        Product first = reviews.getFirst().getProduct();
        Long firstId = first.getId();
        boolean same = reviews.stream().allMatch(review -> sameProduct(first, firstId, review.getProduct()));
        return same ? Optional.of(first) : Optional.empty();
    }

    private boolean sameProduct(Product first, Long firstId, Product candidate) {
        if (candidate == null) {
            return false;
        }
        return firstId != null
                ? Objects.equals(firstId, candidate.getId())
                : first == candidate;
    }
}
