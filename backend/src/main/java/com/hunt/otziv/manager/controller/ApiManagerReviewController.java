package com.hunt.otziv.manager.controller;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.manager.dto.api.CompanyNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.OrderDetailsResponse;
import com.hunt.otziv.manager.dto.api.OrderNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewAnswerUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewEditorUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewTextUpdateRequest;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerReviewController {

    private final CompanyService companyService;
    private final OrderService orderService;
    private final ProductService productService;
    private final ReviewService reviewService;
    private final AutoTextService autoTextService;
    private final S3UploadService s3UploadService;
    private final BadReviewTaskService badReviewTaskService;
    private final ManagerBoardEditAssembler managerBoardEditAssembler;
    private final ManagerPermissionService managerPermissionService;

    @GetMapping("/orders/{orderId}/details")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse getOrderDetails(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse addOrderReview(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        if (!orderService.addNewReview(orderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не добавлен");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/change-text")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse changeOrderReviewText(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        if (!autoTextService.changeReviewText(reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не изменен");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse updateOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewEditorUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || isBlank(request.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }

        ReviewDTO current = requireReviewForOrder(orderId, reviewId);
        if (request.productId() != null && productService.findById(request.productId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Продукт не найден");
        }

        Product product = request.productId() != null ? Product.builder().id(request.productId()).build() : null;

        ReviewDTO reviewDTO = ReviewDTO.builder()
                .id(reviewId)
                .text(normalize(request.text()))
                .answer(normalize(request.answer()))
                .comment(normalize(request.comment()))
                .created(firstValue(request.created(), current.getCreated()))
                .changed(firstValue(request.changed(), current.getChanged()))
                .publishedDate(request.publishedDate())
                .publish(Boolean.TRUE.equals(request.publish()))
                .vigul(Boolean.TRUE.equals(request.vigul()))
                .botName(normalize(request.botName()))
                .botPassword(normalize(request.botPassword()))
                .orderDetailsId(current.getOrderDetailsId())
                .orderDetails(current.getOrderDetails())
                .product(product)
                .url(normalize(request.url()))
                .build();

        try {
            reviewService.updateReview(managerPermissionService.primaryReviewRole(authentication), reviewDTO, reviewId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не сохранен: " + exception.getMessage(), exception);
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/photo")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse uploadOrderReviewPhoto(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не выбран");
        }

        requireReviewForOrder(orderId, reviewId);
        Review review = reviewService.getReviewById(reviewId);
        if (review == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }

        String newUrl = s3UploadService.uploadFile(file, "reviews", review.getUrl(), review.getId());
        review.setUrl(newUrl);
        reviewService.save(review);

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @DeleteMapping("/orders/{orderId}/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse deleteOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        if (!orderService.deleteNewReview(orderId, reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не удален");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/text")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewText(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewTextUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || isBlank(request.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }

        if (!reviewService.updateReviewText(orderId, reviewId, request.text())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/answer")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewAnswer(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewAnswerUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.answer() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ на отзыв не указан");
        }

        if (!reviewService.updateReviewAnswer(orderId, reviewId, request.answer())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewNote(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.comment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка отзыва не указана");
        }

        if (!reviewService.updateReviewNote(orderId, reviewId, request.comment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderNote(
            @PathVariable Long orderId,
            @RequestBody OrderNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.orderComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка заказа не указана");
        }

        Order order = orderService.getOrder(orderId);
        order.setZametka(request.orderComments());
        orderService.save(order);

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/company-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderCompanyNote(
            @PathVariable Long orderId,
            @RequestBody CompanyNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.companyComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка компании не указана");
        }

        Order order = orderService.getOrder(orderId);
        Company company = order.getCompany();
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }

        company.setCommentsCompany(request.companyComments());
        companyService.save(company);

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse changeOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        reviewService.changeBot(reviewId);
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/bots/{botId}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse deactivateOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @PathVariable Long botId,
            Authentication authentication
    ) {
        reviewService.deActivateAndChangeBot(reviewId, botId);
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse publishOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) throws Exception {
        if (!orderService.changeStatusAndOrderCounter(reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не отмечен опубликованным");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/bad-review-tasks/{taskId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse cancelBadReviewTask(
            @PathVariable Long orderId,
            @PathVariable Long taskId,
            Authentication authentication
    ) {
        try {
            boolean belongsToOrder = badReviewTaskService.getTasksByOrderId(orderId).stream()
                    .anyMatch(task -> Objects.equals(task.getId(), taskId));
            if (!belongsToOrder) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Плохая задача не найдена в этом заказе");
            }
            badReviewTaskService.cancelTask(taskId);
            return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private <T> T firstValue(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ReviewDTO requireReviewForOrder(Long orderId, Long reviewId) {
        ReviewDTO review = reviewService.getReviewDTOById(reviewId);
        if (review == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }

        Long reviewOrderId = review.getOrderDetails() != null && review.getOrderDetails().getOrder() != null
                ? review.getOrderDetails().getOrder().getId()
                : null;
        if (!Objects.equals(orderId, reviewOrderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return review;
    }
}
