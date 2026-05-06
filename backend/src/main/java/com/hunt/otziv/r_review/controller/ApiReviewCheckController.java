package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review-check")
public class ApiReviewCheckController {

    private final OrderDetailsService orderDetailsService;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final CompanyService companyService;

    @GetMapping("/{orderDetailId}")
    public ReviewCheckResponse getReviewCheck(
            @PathVariable UUID orderDetailId,
            Authentication authentication
    ) {
        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}")
    public ReviewCheckResponse saveReviews(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication
    ) {
        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        updateReviews(orderDetails, request);
        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/approve")
    public ReviewCheckResponse approveReviews(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication
    ) throws Exception {
        ReviewCheckPermissions permissions = permissions(authentication);
        if (!permissions.canApprovePublication()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для разрешения публикации");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);
        OrderDetailsDTO updateDto = toUpdateDto(orderDetails, request);

        if (!orderService.changeStatusForOrder(order.getId(), "Публикация")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось перевести заказ в публикацию");
        }

        if (!reviewService.updateOrderDetailAndReviewAndPublishDate(updateDto)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось назначить даты публикации");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/correction")
    public ReviewCheckResponse sendToCorrection(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication
    ) throws Exception {
        ReviewCheckPermissions permissions = permissions(authentication);
        if (!permissions.canSendCorrection()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отправки на коррекцию");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);

        updateReviews(orderDetails, request);

        if (!orderService.changeStatusForOrder(order.getId(), "Коррекция")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отправить заказ на коррекцию");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/send-to-check")
    public ReviewCheckResponse sendToCheck(
            @PathVariable UUID orderDetailId,
            Authentication authentication
    ) throws Exception {
        ReviewCheckPermissions permissions = permissions(authentication);
        if (!permissions.canSendToCheck()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отправки на проверку");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);

        if (!orderService.changeStatusForOrder(order.getId(), "В проверку")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отправить заказ на проверку");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/pay-ok")
    public ReviewCheckResponse markPaid(
            @PathVariable UUID orderDetailId,
            Authentication authentication
    ) throws Exception {
        ReviewCheckPermissions permissions = permissions(authentication);
        if (!permissions.canMarkPaid()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отметки оплаты");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);

        if (order.getAmount() > order.getCounter()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нельзя отметить оплату: опубликованы не все отзывы");
        }

        if (!orderService.changeStatusForOrder(order.getId(), "Оплачено")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отметить оплату");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}/reviews/{reviewId}/note")
    public ReviewCheckResponse updateReviewNote(
            @PathVariable UUID orderDetailId,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewNoteUpdateRequest request,
            Authentication authentication
    ) {
        requireCanEditNotes(authentication);
        if (request == null || request.comment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка отзыва не указана");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);
        boolean belongsToOrderDetails = safeReviews(orderDetails).stream()
                .anyMatch(review -> Objects.equals(review.getId(), reviewId));
        if (!belongsToOrderDetails) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этой проверке");
        }

        if (!reviewService.updateReviewNote(order.getId(), reviewId, request.comment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}/order-note")
    public ReviewCheckResponse updateOrderNote(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckOrderNoteUpdateRequest request,
            Authentication authentication
    ) {
        requireCanEditNotes(authentication);
        if (request == null || request.orderComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка заказа не указана");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);
        order.setZametka(request.orderComments());
        orderService.save(order);

        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}/company-note")
    public ReviewCheckResponse updateCompanyNote(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckCompanyNoteUpdateRequest request,
            Authentication authentication
    ) {
        requireCanEditNotes(authentication);
        if (request == null || request.companyComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка компании не указана");
        }

        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Company company = requireOrder(orderDetails).getCompany();
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }

        company.setCommentsCompany(request.companyComments());
        companyService.save(company);

        return buildResponse(orderDetailId, authentication);
    }

    private void updateReviews(OrderDetails orderDetails, ReviewCheckUpdateRequest request) {
        OrderDetailsDTO updateDto = toUpdateDto(orderDetails, request);
        updateDto.getReviews().forEach(review -> reviewService.updateOrderDetailAndReview(updateDto, review, review.getId()));
    }

    private OrderDetailsDTO toUpdateDto(OrderDetails orderDetails, ReviewCheckUpdateRequest request) {
        if (request == null || request.reviews() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзывы не переданы");
        }

        List<Review> currentReviews = safeReviews(orderDetails);
        Set<Long> currentReviewIds = currentReviews.stream()
                .map(Review::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<ReviewDTO> reviews = request.reviews().stream()
                .map(item -> {
                    if (item == null || item.id() == null || !currentReviewIds.contains(item.id())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не относится к этой проверке");
                    }

                    Review current = currentReviews.stream()
                            .filter(review -> Objects.equals(review.getId(), item.id()))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден"));

                    return ReviewDTO.builder()
                            .id(current.getId())
                            .text(valueOrCurrent(item.text(), current.getText()))
                            .answer(valueOrCurrent(item.answer(), current.getAnswer()))
                            .publish(item.publish() != null ? item.publish() : current.isPublish())
                            .publishedDate(parseDateOrCurrent(item.publishedDate(), current.getPublishedDate()))
                            .url(valueOrCurrent(item.url(), current.getUrl()))
                            .build();
                })
                .toList();

        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .order(com.hunt.otziv.p_products.dto.OrderDTO.builder()
                        .id(requireOrder(orderDetails).getId())
                        .build())
                .comment(request.comment() != null ? request.comment() : safe(orderDetails.getComment()))
                .reviews(reviews)
                .build();
    }

    private ReviewCheckResponse buildResponse(UUID orderDetailId, Authentication authentication) {
        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        Order order = requireOrder(orderDetails);
        Company company = order.getCompany();
        Filial filial = order.getFilial();
        List<Review> reviews = safeReviews(orderDetails);
        ReviewCheckPermissions permissions = permissions(authentication);

        boolean approved = !reviews.isEmpty() && reviews.get(0).getPublishedDate() != null;
        String workerFio = workerFio(reviews, order);

        return new ReviewCheckResponse(
                orderDetails.getId(),
                order.getId(),
                company != null ? company.getId() : null,
                company != null ? safe(company.getTitle()) : "",
                filial != null ? safe(filial.getTitle()) : "",
                order.getStatus() != null ? safe(order.getStatus().getTitle()) : "",
                workerFio,
                permissions.canSeeInternalInfo() ? safe(order.getZametka()) : "",
                permissions.canSeeInternalInfo() && company != null ? safe(company.getCommentsCompany()) : "",
                safe(orderDetails.getComment()),
                orderDetails.getAmount(),
                order.getCounter(),
                order.getSum(),
                approved,
                reviews.stream()
                        .map(review -> toReviewResponse(review, orderDetails, permissions))
                        .toList(),
                permissions
        );
    }

    private ReviewCheckReviewResponse toReviewResponse(
            Review review,
            OrderDetails orderDetails,
            ReviewCheckPermissions permissions
    ) {
        Product product = review.getProduct() != null ? review.getProduct() : orderDetails.getProduct();
        Bot bot = review.getBot();

        return new ReviewCheckReviewResponse(
                review.getId(),
                safe(review.getText()),
                safe(review.getAnswer()),
                permissions.canSeeBot() && bot != null ? safe(bot.getFio()) : "",
                permissions.canSeeInternalInfo() ? safe(orderDetails.getComment()) : "",
                permissions.canSeeInternalInfo() && orderDetails.getOrder() != null ? safe(orderDetails.getOrder().getZametka()) : "",
                permissions.canSeeInternalInfo()
                        && orderDetails.getOrder() != null
                        && orderDetails.getOrder().getCompany() != null
                        ? safe(orderDetails.getOrder().getCompany().getCommentsCompany())
                        : "",
                product != null ? safe(product.getTitle()) : "",
                product != null && Boolean.TRUE.equals(product.getPhoto()),
                safe(review.getUrl()),
                dateValue(review.getPublishedDate()),
                review.isPublish()
        );
    }

    private ReviewCheckPermissions permissions(Authentication authentication) {
        boolean authenticated = isAuthenticated(authentication);
        boolean canSeeInternal = hasAnyRole(authentication, "WORKER", "MANAGER", "ADMIN", "OWNER");
        boolean canManage = hasAnyRole(authentication, "MANAGER", "ADMIN", "OWNER");
        boolean canEditNotes = canManage;
        boolean workerOnly = hasRole(authentication, "WORKER") && !canManage;

        return new ReviewCheckPermissions(
                authenticated,
                canSeeInternal,
                canSeeInternal,
                !workerOnly,
                true,
                !workerOnly,
                hasAnyRole(authentication, "WORKER", "ADMIN"),
                hasAnyRole(authentication, "MANAGER", "ADMIN", "OWNER"),
                canManage,
                canEditNotes
        );
    }

    private void requireCanEditNotes(Authentication authentication) {
        if (!permissions(authentication).canEditNotes()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для редактирования заметок");
        }
    }

    private List<Review> safeReviews(OrderDetails orderDetails) {
        if (orderDetails.getReviews() == null) {
            return List.of();
        }

        return orderDetails.getReviews().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Review::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private Order requireOrder(OrderDetails orderDetails) {
        if (orderDetails.getOrder() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        }

        return orderDetails.getOrder();
    }

    private String workerFio(List<Review> reviews, Order order) {
        String reviewWorker = reviews.stream()
                .map(Review::getWorker)
                .filter(Objects::nonNull)
                .map(Worker::getUser)
                .filter(Objects::nonNull)
                .map(User::getFio)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse("");

        if (!isBlank(reviewWorker)) {
            return reviewWorker;
        }

        Worker worker = order.getWorker();
        if (worker != null && worker.getUser() != null && !isBlank(worker.getUser().getFio())) {
            return worker.getUser().getFio();
        }

        Manager manager = order.getManager();
        if (manager != null && manager.getUser() != null) {
            return safe(manager.getUser().getFio());
        }

        return "";
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        for (String role : roles) {
            if (hasRole(authentication, role)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (!isAuthenticated(authentication)) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private LocalDate parseDateOrCurrent(String value, LocalDate current) {
        if (isBlank(value)) {
            return current;
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная дата публикации");
        }
    }

    private String valueOrCurrent(String value, String current) {
        return value != null ? value : safe(current);
    }

    private String dateValue(LocalDate date) {
        return date != null ? date.toString() : "";
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record ReviewCheckResponse(
            UUID orderDetailId,
            Long orderId,
            Long companyId,
            String companyTitle,
            String filialTitle,
            String status,
            String workerFio,
            String orderComments,
            String companyComments,
            String comment,
            int amount,
            int counter,
            BigDecimal sum,
            boolean approved,
            List<ReviewCheckReviewResponse> reviews,
            ReviewCheckPermissions permissions
    ) {
    }

    public record ReviewCheckReviewResponse(
            Long id,
            String text,
            String answer,
            String botName,
            String comment,
            String orderComments,
            String commentCompany,
            String productTitle,
            boolean productPhoto,
            String url,
            String publishedDate,
            boolean publish
    ) {
    }

    public record ReviewCheckPermissions(
            boolean authenticated,
            boolean canSeeInternalInfo,
            boolean canSeeBot,
            boolean canApprovePublication,
            boolean canSave,
            boolean canSendCorrection,
            boolean canSendToCheck,
            boolean canMarkPaid,
            boolean canOpenManagerLinks,
            boolean canEditNotes
    ) {
    }

    public record ReviewCheckUpdateRequest(
            String comment,
            List<ReviewCheckReviewUpdateRequest> reviews
    ) {
    }

    public record ReviewCheckReviewUpdateRequest(
            Long id,
            String text,
            String answer,
            Boolean publish,
            String publishedDate,
            String url
    ) {
    }

    public record ReviewCheckReviewNoteUpdateRequest(String comment) {
    }

    public record ReviewCheckOrderNoteUpdateRequest(String orderComments) {
    }

    public record ReviewCheckCompanyNoteUpdateRequest(String companyComments) {
    }
}
