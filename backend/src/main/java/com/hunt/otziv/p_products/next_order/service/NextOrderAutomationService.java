package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.service.OrderCreationService;
import com.hunt.otziv.r_review.model.Review;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class NextOrderAutomationService {

    private static final Comparator<ReviewTerm> REVIEW_TERM_COMPARATOR = Comparator
            .comparing(ReviewTerm::effectiveFilialId, Comparator.nullsFirst(Long::compareTo))
            .thenComparing(ReviewTerm::productId)
            .thenComparing(ReviewTerm::price);

    private final NextOrderRequestRepository requestRepository;
    private final NextOrderRequestService requestService;
    private final OrderCreationService creationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createNextOrder(Long requestId) {
        // Scheduler/startup recovery may race the original AFTER_COMMIT event.
        // Serialize the whole idempotent decision on the durable request row.
        NextOrderRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Next order request not found: " + requestId));

        if (request.getStatus() == NextOrderRequestStatus.CREATED) {
            log.info("Заявка {} уже закрыта созданным заказом", requestId);
            return;
        }
        if (request.getStatus() == NextOrderRequestStatus.CANCELED) {
            log.info("Заявка {} отменена, следующий заказ не создаем", requestId);
            return;
        }

        Order sourceOrder = request.getSourceOrder();
        if (sourceOrder == null) {
            throw new IllegalStateException("У заявки " + requestId + " нет исходного заказа");
        }

        Long companyId = sourceOrder.getCompany() != null ? sourceOrder.getCompany().getId() : null;
        Long filialId = sourceOrder.getFilial() != null ? sourceOrder.getFilial().getId() : null;
        Long workerId = sourceOrder.getWorker() != null ? sourceOrder.getWorker().getId() : null;
        Set<Long> filialIds = requestService.orderFilialIds(sourceOrder);
        List<Order> existingActiveOrders = requestService.findActiveOrdersForFilials(companyId, filialIds, filialId, workerId);
        Optional<Order> matchingActiveOrder = existingActiveOrders.stream()
                .filter(activeOrder -> hasSameReviewTerms(sourceOrder, activeOrder))
                .findFirst();
        if (matchingActiveOrder.isPresent()) {
            Order activeOrder = matchingActiveOrder.get();
            request.setCreatedOrder(activeOrder);
            request.setErrorMessage(null);
            requestRepository.save(request);
            requestService.markCreatedIfOpen(requestId);
            log.warn(
                    "Заявка {} закрыта: для компании {}, филиала {} и исполнителя {} уже есть активный заказ {}",
                    requestId,
                    companyId,
                    filialId,
                    workerId,
                    activeOrder.getId()
            );
            return;
        }
        if (!existingActiveOrders.isEmpty()) {
            log.warn(
                    "Заявка {} не привязана к {} активным заказам компании {}: карточки, филиалы или агрегаты отличаются от исходного заказа {}",
                    requestId,
                    existingActiveOrders.size(),
                    companyId,
                    sourceOrder.getId()
            );
        }

        requestService.markAttemptStarted(requestId);

        OrderDTO repeatOrder = creationService.convertToOrderDTOToRepeat(sourceOrder);
        boolean created = creationService.createRepeatedOrderWithReviews(sourceOrder, repeatOrder);
        if (!created) {
            throw new IllegalStateException("createRepeatedOrderWithReviews вернул false для заявки " + requestId);
        }

        requestService.markCreatedIfOpen(requestId);
    }

    private boolean hasSameReviewTerms(Order sourceOrder, Order activeOrder) {
        List<Review> sourceReviews = reviews(sourceOrder);
        List<Review> activeReviews = reviews(activeOrder);
        if (!hasSameOrderRouting(sourceOrder, activeOrder)
                || sourceReviews.isEmpty()
                || sourceReviews.size() != activeReviews.size()
                || !hasConsistentAggregates(activeOrder)) {
            return false;
        }

        List<ReviewTerm> sourceTerms = reviewTerms(sourceOrder, sourceReviews);
        List<ReviewTerm> activeTerms = reviewTerms(activeOrder, activeReviews);
        if (sourceTerms.stream().anyMatch(term -> !term.isComplete())
                || activeTerms.stream().anyMatch(term -> !term.isComplete())) {
            return false;
        }
        return canonicalTerms(sourceTerms).equals(canonicalTerms(activeTerms));
    }

    private boolean hasSameOrderRouting(Order sourceOrder, Order activeOrder) {
        if (sourceOrder == null || activeOrder == null) {
            return false;
        }
        Long sourceFilialId = sourceOrder.getFilial() == null ? null : sourceOrder.getFilial().getId();
        Long activeFilialId = activeOrder.getFilial() == null ? null : activeOrder.getFilial().getId();
        Long sourceWorkerId = sourceOrder.getWorker() == null ? null : sourceOrder.getWorker().getId();
        Long activeWorkerId = activeOrder.getWorker() == null ? null : activeOrder.getWorker().getId();
        return Objects.equals(sourceFilialId, activeFilialId)
                && Objects.equals(sourceWorkerId, activeWorkerId);
    }

    private boolean hasConsistentAggregates(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return false;
        }

        int orderAmount = 0;
        BigDecimal orderTotal = BigDecimal.ZERO;
        for (var detail : order.getDetails()) {
            if (detail == null || detail.getReviews() == null || detail.getReviews().stream().anyMatch(Objects::isNull)) {
                return false;
            }
            List<Review> reviews = detail.getReviews();
            if (reviews.stream().anyMatch(review -> review.getPrice() == null)) {
                return false;
            }
            BigDecimal detailTotal = reviews.stream()
                    .map(Review::getPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (detail.getAmount() != reviews.size() || !sameMoney(detail.getPrice(), detailTotal)) {
                return false;
            }
            orderAmount += reviews.size();
            orderTotal = orderTotal.add(detailTotal);
        }

        return order.getAmount() == orderAmount && sameMoney(order.getSum(), orderTotal);
    }

    private List<ReviewTerm> reviewTerms(Order order, List<Review> reviews) {
        Long orderFilialId = order.getFilial() == null ? null : order.getFilial().getId();
        return reviews.stream()
                .map(review -> {
                    Long reviewFilialId = review.getFilial() == null ? null : review.getFilial().getId();
                    Long effectiveFilialId = reviewFilialId != null ? reviewFilialId : orderFilialId;
                    Long productId = review.getProduct() == null ? null : review.getProduct().getId();
                    BigDecimal price = review.getPrice() == null ? null : review.getPrice().stripTrailingZeros();
                    return new ReviewTerm(effectiveFilialId, productId, price);
                })
                .toList();
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private List<ReviewTerm> canonicalTerms(List<ReviewTerm> terms) {
        return terms.stream()
                .sorted(REVIEW_TERM_COMPARATOR)
                .toList();
    }

    private List<Review> reviews(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return List.of();
        }
        return order.getDetails().stream()
                .filter(Objects::nonNull)
                .flatMap(detail -> Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList()).stream())
                .filter(Objects::nonNull)
                .toList();
    }

    private record ReviewTerm(Long effectiveFilialId, Long productId, BigDecimal price) {
        private boolean isComplete() {
            return productId != null && price != null;
        }
    }
}
