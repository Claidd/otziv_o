package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.p_products.review.exception.PublicationApprovalException;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.service.ReviewService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Service
@RequiredArgsConstructor
public class OrderPublicationApprovalService {

    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String FIX_REVIEW_TEXTS =
            "откройте заказ, заполните корректный текст каждого отзыва и повторите одобрение";
    private static final String FIX_PUBLICATION_DATES =
            "откройте заказ, проверьте назначенные аккаунты и даты; после исправления повторите одобрение";

    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final BusinessAuditService businessAuditService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Transactional(readOnly = true)
    public void validateExistingOrder(Long orderId) {
        validate(orderId, existingDetails(orderId));
    }

    @Transactional
    public void approveExistingOrder(Long orderId, String auditDetails) {
        approvePreparedOrder(orderId, List.of(), auditDetails);
    }

    @Transactional
    public void approvePreparedOrder(Long orderId, List<OrderDetailsDTO> details, String auditDetails) {
        lockAggregate(orderId);
        List<OrderDetailsDTO> completeDetails = completeOrderDetails(orderId, details);
        validate(orderId, completeDetails);
        assignDates(orderId, completeDetails);

        try {
            if (!orderStatusTransitionService.changeStatusForOrder(orderId, STATUS_TO_PUBLISH)) {
                throw failure(
                        orderId,
                        "статус заказа не удалось изменить на «Публикация»",
                        "обновите страницу, проверьте текущий статус заказа и повторите одобрение"
                );
            }
        } catch (PublicationApprovalException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PublicationApprovalException(
                    orderId,
                    "статус заказа не удалось изменить на «Публикация»: " + concise(exception),
                    "обновите страницу, проверьте текущий статус заказа и повторите одобрение",
                    exception
            );
        }

        recordApproval(orderId, completeDetails, auditDetails);
    }

    @Transactional
    public void repairMissingDates(Long orderId, String auditDetails) {
        lockAggregate(orderId);
        List<OrderDetailsDTO> details = existingDetails(orderId);
        validate(orderId, details);
        boolean hasMissingDates = details.stream()
                .flatMap(detail -> safeReviews(detail).stream())
                .anyMatch(review -> !review.isPublish() && review.getPublishedDate() == null);
        if (!hasMissingDates) {
            return;
        }
        assignDates(orderId, details);
        businessAuditService.recordSafely(
                "publication_dates_repaired",
                "order",
                orderId,
                orderId,
                null,
                null,
                STATUS_TO_PUBLISH,
                safe(auditDetails)
        );
    }

    private List<OrderDetailsDTO> existingDetails(Long orderId) {
        return canonicalDetails(orderId);
    }

    /**
     * A publication status belongs to the whole order, while a public review link represents one
     * order detail. Merge the freshly prepared detail with the canonical remaining details so a
     * partial request cannot publish an order whose other cards are not ready.
     */
    private List<OrderDetailsDTO> completeOrderDetails(Long orderId, List<OrderDetailsDTO> preparedDetails) {
        List<OrderDetailsDTO> canonicalDetails = canonicalDetails(orderId);
        Map<java.util.UUID, OrderDetailsDTO> preparedById = new HashMap<>();
        if (preparedDetails != null) {
            for (OrderDetailsDTO detail : preparedDetails) {
                if (detail == null || detail.getId() == null) {
                    throw failure(orderId, "передана карточка без ID", "обновите страницу и повторите одобрение");
                }
                if (preparedById.putIfAbsent(detail.getId(), detail) != null) {
                    throw failure(orderId, "одна карточка передана повторно", "обновите страницу и повторите одобрение");
                }
            }
        }

        List<OrderDetailsDTO> complete = canonicalDetails.stream()
                .map(canonical -> {
                    OrderDetailsDTO prepared = preparedById.remove(canonical.getId());
                    return prepared != null ? prepared : canonical;
                })
                .toList();
        if (!preparedById.isEmpty()) {
            throw failure(
                    orderId,
                    "передана карточка из другого заказа",
                    "обновите страницу и повторите одобрение"
            );
        }
        return complete;
    }

    private List<OrderDetailsDTO> canonicalDetails(Long orderId) {
        if (orderId == null) {
            throw failure(null, "не указан ID заказа", "обновите страницу и повторите действие");
        }
        List<OrderDetailsDTO> details = orderDetailsService.getOrderDetailDTOsByOrderIdForReviewCheck(orderId);
        if (details == null || details.isEmpty()) {
            throw failure(
                    orderId,
                    "у заказа отсутствуют карточки с отзывами",
                    "добавьте карточку продукта и отзывы либо отправьте заказ в коррекцию"
            );
        }
        return details;
    }

    private void validate(Long orderId, List<OrderDetailsDTO> details) {
        if (details == null || details.isEmpty()) {
            throw failure(
                    orderId,
                    "у заказа отсутствуют карточки с отзывами",
                    "добавьте карточку продукта и отзывы либо отправьте заказ в коррекцию"
            );
        }
        for (OrderDetailsDTO detail : details) {
            List<ReviewDTO> reviews = safeReviews(detail);
            if (reviews.isEmpty()) {
                throw failure(
                        orderId,
                        "в одной из карточек заказа отсутствуют отзывы",
                        "добавьте отзывы в карточку или удалите пустую карточку и повторите одобрение"
                );
            }
            if (reviews.stream().anyMatch(review -> review == null || isBlankOrPlaceholder(review.getText()))) {
                throw failure(orderId, "есть пустой или шаблонный текст отзыва", FIX_REVIEW_TEXTS);
            }
        }
    }

    private void assignDates(Long orderId, List<OrderDetailsDTO> details) {
        try {
            if (!reviewService.updateOrderDetailsAndReviewsAndPublishDates(orderId, details)) {
                throw failure(orderId, "не удалось назначить даты публикации", FIX_PUBLICATION_DATES);
            }
        } catch (PublicationApprovalException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PublicationApprovalException(
                    orderId,
                    "не удалось назначить даты публикации: " + concise(exception),
                    FIX_PUBLICATION_DATES,
                    exception
            );
        }
    }

    private void lockAggregate(Long orderId) {
        if (orderId == null) {
            throw failure(null, "не указан ID заказа", "обновите страницу и повторите действие");
        }
        orderAggregateMutationLockService.lock(orderId);
    }

    private void recordApproval(Long orderId, List<OrderDetailsDTO> details, String auditDetails) {
        for (OrderDetailsDTO detail : details) {
            businessAuditService.recordSafely(
                    "publication_allowed",
                    "order_detail",
                    detail.getId(),
                    orderId,
                    null,
                    null,
                    STATUS_TO_PUBLISH,
                    "reviews=" + safeReviews(detail).size() + appendAuditDetails(auditDetails)
            );
        }
    }

    private List<ReviewDTO> safeReviews(OrderDetailsDTO detail) {
        return detail == null || detail.getReviews() == null ? List.of() : detail.getReviews();
    }

    private PublicationApprovalException failure(Long orderId, String problem, String solution) {
        return new PublicationApprovalException(orderId, problem, solution);
    }

    private String appendAuditDetails(String details) {
        String normalized = safe(details);
        return normalized.isBlank() ? "" : ";" + normalized;
    }

    private String concise(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? "" : safe(current.getMessage());
        return message.isBlank()
                ? (current == null ? "неизвестная ошибка" : current.getClass().getSimpleName())
                : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
