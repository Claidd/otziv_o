package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderDeletionService {

    private final OrderRepository orderRepository;
    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final OrderDeletionPolicy orderDeletionPolicy;

    @Transactional
    public boolean deleteOrder(Long orderId, Principal principal) {
        String userRole = getRole();
        String username = principal != null ? principal.getName() : "unknown";

        log.info("Начало удаления заказа ID: {}, инициатор: {}, роль: {}", orderId, username, userRole);

        Order orderToDelete = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Заказ ID: {} не найден", orderId);
                    return new UsernameNotFoundException(String.format("Order '%d' not found", orderId));
                });

        if (orderDeletionPolicy.canDelete(userRole, orderToDelete)) {
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

    private String getRole() {
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
}
