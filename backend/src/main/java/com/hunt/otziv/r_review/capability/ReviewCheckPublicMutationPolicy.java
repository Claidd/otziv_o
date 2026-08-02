package com.hunt.otziv.r_review.capability;

import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shared invariants for every public review-check write contract. */
@Component
public class ReviewCheckPublicMutationPolicy {

    private static final Set<String> CLIENT_MUTABLE_STATUSES = Set.of(
            "в проверку",
            "на проверке",
            "коррекция",
            "архив"
    );

    public boolean clientMutationAllowed(Order order) {
        String status = order != null && order.getStatus() != null ? safe(order.getStatus().getTitle()) : "";
        String normalizedStatus = status.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return CLIENT_MUTABLE_STATUSES.contains(normalizedStatus);
    }

    public void requireClientMutationAllowed(Order order) {
        if (!clientMutationAllowed(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Проверка отзывов уже закрыта для клиентских правок"
            );
        }
    }

    public void requireCompleteReviewSet(OrderDetails current, OrderDetailsDTO submitted) {
        Set<Long> currentIds = reviews(current).stream()
                .map(Review::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> submittedIds = submitted == null || submitted.getReviews() == null
                ? Set.of()
                : submitted.getReviews().stream()
                        .filter(Objects::nonNull)
                        .map(ReviewDTO::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (!submittedIds.equals(currentIds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для этого действия необходимо передать все отзывы проверки"
            );
        }
    }

    private List<Review> reviews(OrderDetails orderDetails) {
        return orderDetails == null || orderDetails.getReviews() == null
                ? List.of()
                : orderDetails.getReviews();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
