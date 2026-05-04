package com.hunt.otziv.p_products.utils;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class OrderReviewGraph {

    private OrderReviewGraph() {
    }

    public static OrderDetails getFirstDetail(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return null;
        }
        return order.getDetails().get(0);
    }

    public static Review getFirstReview(Order order) {
        OrderDetails firstDetail = getFirstDetail(order);
        if (firstDetail == null || firstDetail.getReviews() == null || firstDetail.getReviews().isEmpty()) {
            return null;
        }
        return firstDetail.getReviews().get(0);
    }

    public static List<Review> getAllReviews(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return Collections.emptyList();
        }

        return order.getDetails().stream()
                .filter(Objects::nonNull)
                .flatMap(detail -> Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList()).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static boolean hasDetails(Order order) {
        return order != null && order.getDetails() != null && !order.getDetails().isEmpty();
    }

    public static String safeStatusTitle(Order order) {
        if (order == null || order.getStatus() == null || order.getStatus().getTitle() == null) {
            return "";
        }
        return order.getStatus().getTitle();
    }

    public static String safeFilialTitle(Order order) {
        return order != null && order.getFilial() != null
                ? safeString(order.getFilial().getTitle())
                : "Без филиала";
    }

    public static String safeString(String value) {
        return value == null ? "" : value;
    }
}
