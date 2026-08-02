package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheck;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheckReview;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.capability.service.ReviewCheckMutationLockService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckPublicMutationPolicy;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.r_review.utils.ReviewPublicationDatePolicy.MAX_FUTURE_DAYS;
import static com.hunt.otziv.r_review.utils.ReviewPublicationDatePolicy.maxAllowedDate;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review-check")
public class ApiReviewCheckController {

    private static final int MAX_PUBLIC_TEXT_LENGTH = 5_000;
    private static final int MAX_PUBLIC_URL_LENGTH = 2_048;
    private static final int MAX_PUBLIC_REVIEW_ITEMS = 1_000;
    private static final int MAX_AUDIT_DETAIL_VALUE_LENGTH = 256;

    private final OrderDetailsService orderDetailsService;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final CompanyService companyService;
    private final ReviewCheckArchiveService reviewCheckArchiveService;
    private final BusinessAuditService businessAuditService;
    private final OrderPublicationApprovalService publicationApprovalService;
    private final ReviewCheckMutationLockService mutationLockService;
    private final ManagerAccessService managerAccessService;
    private final WebhookClientIpResolver clientIpResolver;
    private final ReviewCheckPublicMutationPolicy publicMutationPolicy;

    @GetMapping("/{orderDetailId}")
    public ReviewCheckResponse getReviewCheck(
            @PathVariable UUID orderDetailId,
            Authentication authentication
    ) {
        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckResponse saveReviews(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        OrderDetails orderDetails = reviewCheckDetailsForAction(orderDetailId, "Коррекция", authentication);
        requireLiveClientMutationAllowed(orderDetails, authentication);
        updateReviews(
                orderDetails,
                request,
                permissionsForOrder(requireOrder(orderDetails), authentication).canSeeInternalInfo()
        );
        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}/reviews/{reviewId}/text")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckReviewResponse updateReviewText(
            @PathVariable UUID orderDetailId,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewTextUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        if (request == null || isBlank(request.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }
        requireMaxLength(request.text(), MAX_PUBLIC_TEXT_LENGTH, "Текст отзыва слишком длинный");

        OrderDetails orderDetails = reviewCheckDetailsForAction(orderDetailId, "Коррекция", authentication);
        requireLiveClientMutationAllowed(orderDetails, authentication);
        Order order = requireOrder(orderDetails);
        requireReviewInDetails(orderDetails, reviewId);

        if (!reviewService.updateReviewTextFromSharedCheck(order.getId(), reviewId, request.text())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildReviewResponse(orderDetailId, reviewId, authentication);
    }

    @PutMapping("/{orderDetailId}/reviews/{reviewId}/answer")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckReviewResponse updateReviewAnswer(
            @PathVariable UUID orderDetailId,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewAnswerUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        if (request == null || request.answer() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Замечание к отзыву не указано");
        }
        requireMaxLength(request.answer(), MAX_PUBLIC_TEXT_LENGTH, "Замечание к отзыву слишком длинное");

        OrderDetails orderDetails = reviewCheckDetailsForAction(orderDetailId, "Коррекция", authentication);
        requireLiveClientMutationAllowed(orderDetails, authentication);
        Order order = requireOrder(orderDetails);
        requireReviewInDetails(orderDetails, reviewId);

        if (!reviewService.updateReviewAnswerFromSharedCheck(order.getId(), reviewId, request.answer())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildReviewResponse(orderDetailId, reviewId, authentication);
    }

    @PostMapping("/{orderDetailId}/approve")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckResponse approveReviews(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) throws Exception {
        mutationLockService.lock(orderDetailId);
        OrderDetails orderDetails = reviewCheckDetailsForAction(orderDetailId, "На проверке", authentication);
        requireLiveClientMutationAllowed(orderDetails, authentication);
        Order order = requireOrder(orderDetails);
        ReviewCheckPermissions permissions = permissionsForOrder(order, authentication);
        if (!permissions.canApprovePublication()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для разрешения публикации");
        }
        OrderDetailsDTO updateDto = toUpdateDto(orderDetails, request, permissions.canSeeInternalInfo());
        publicMutationPolicy.requireCompleteReviewSet(orderDetails, updateDto);
        validateReviewTextsReadyForAction(updateDto, "Нельзя разрешить публикацию: заполните текст всех отзывов");
        saveUpdateDto(updateDto);

        publicationApprovalService.approvePreparedOrder(
                order.getId(),
                List.of(updateDto),
                approvalAuditDetails(authentication, servletRequest)
        );

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/correction")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckResponse sendToCorrection(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckUpdateRequest request,
            Authentication authentication
    ) throws Exception {
        mutationLockService.lock(orderDetailId);
        OrderDetails orderDetails = reviewCheckDetailsForAction(orderDetailId, "Коррекция", authentication);
        requireLiveClientMutationAllowed(orderDetails, authentication);
        Order order = requireOrder(orderDetails);
        ReviewCheckPermissions permissions = permissionsForOrder(order, authentication);
        if (!permissions.canSendCorrection()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отправки на коррекцию");
        }

        updateReviews(orderDetails, request, permissions.canSeeInternalInfo());

        if (!orderService.changeStatusForOrder(order.getId(), "Коррекция")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отправить заказ на коррекцию");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/send-to-check")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckResponse sendToCheck(
            @PathVariable UUID orderDetailId,
            @RequestBody(required = false) ReviewCheckUpdateRequest request,
            Authentication authentication
    ) throws Exception {
        mutationLockService.lock(orderDetailId);
        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        ReviewCheckPermissions permissions = permissionsForOrder(order, authentication);
        if (!permissions.canSendToCheck()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отправки на проверку");
        }

        if (request != null && request.reviews() != null) {
            OrderDetailsDTO updateDto = toUpdateDto(orderDetails, request);
            publicMutationPolicy.requireCompleteReviewSet(orderDetails, updateDto);
            validateReviewTextsReadyForAction(updateDto, "Нельзя отправить заказ на проверку: заполните текст всех отзывов");
            saveUpdateDto(updateDto);
        }

        if (!orderService.changeStatusForOrder(order.getId(), "В проверку")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отправить заказ на проверку");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PostMapping("/{orderDetailId}/pay-ok")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckResponse markPaid(
            @PathVariable UUID orderDetailId,
            Authentication authentication
    ) throws Exception {
        mutationLockService.lock(orderDetailId);
        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        ReviewCheckPermissions permissions = permissionsForOrder(order, authentication);
        if (!permissions.canMarkPaid()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отметки оплаты");
        }

        if (order.getAmount() > order.getCounter()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нельзя отметить оплату: опубликованы не все отзывы");
        }

        if (!orderService.changeStatusForOrder(order.getId(), "Оплачено")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось отметить оплату");
        }

        return buildResponse(orderDetailId, authentication);
    }

    @PutMapping("/{orderDetailId}/reviews/{reviewId}/note")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckReviewResponse updateReviewNote(
            @PathVariable UUID orderDetailId,
            @PathVariable Long reviewId,
            @RequestBody ReviewCheckReviewNoteUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        if (request == null || request.comment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка отзыва не указана");
        }

        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        requireCanEditNotes(order, authentication);
        requireReviewInDetails(orderDetails, reviewId);

        if (!reviewService.updateReviewNote(order.getId(), reviewId, request.comment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildReviewResponse(orderDetailId, reviewId, authentication);
    }

    @PutMapping("/{orderDetailId}/order-note")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckNotesResponse updateOrderNote(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckOrderNoteUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        if (request == null || request.orderComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка заказа не указана");
        }

        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        requireCanEditNotes(order, authentication);
        order.setZametka(request.orderComments());
        orderService.save(order);

        return new ReviewCheckNotesResponse(
                safe(order.getZametka()),
                order.getCompany() != null ? safe(order.getCompany().getCommentsCompany()) : ""
        );
    }

    @PutMapping("/{orderDetailId}/company-note")
    @Transactional(rollbackFor = Exception.class)
    public ReviewCheckNotesResponse updateCompanyNote(
            @PathVariable UUID orderDetailId,
            @RequestBody ReviewCheckCompanyNoteUpdateRequest request,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        if (request == null || request.companyComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка компании не указана");
        }

        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        requireCanEditNotes(order, authentication);
        Company company = order.getCompany();
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }

        company.setCommentsCompany(request.companyComments());
        companyService.save(company);

        return new ReviewCheckNotesResponse(safe(order.getZametka()), safe(company.getCommentsCompany()));
    }

    private void updateReviews(
            OrderDetails orderDetails,
            ReviewCheckUpdateRequest request,
            boolean allowPublicationFields
    ) {
        OrderDetailsDTO updateDto = toUpdateDto(orderDetails, request, allowPublicationFields);
        saveUpdateDto(updateDto);
    }

    private void saveUpdateDto(OrderDetailsDTO updateDto) {
        reviewService.updateOrderDetailAndReviews(updateDto);
    }

    private void validateReviewTextsReadyForAction(OrderDetailsDTO updateDto, String errorMessage) {
        if (updateDto.getReviews() == null || updateDto.getReviews().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }

        boolean hasInvalidReviewText = updateDto.getReviews().stream()
                .anyMatch(review -> review == null || isBlankOrPlaceholder(review.getText()));
        if (hasInvalidReviewText) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    private OrderDetailsDTO toUpdateDto(OrderDetails orderDetails, ReviewCheckUpdateRequest request) {
        return toUpdateDto(orderDetails, request, true);
    }

    private OrderDetailsDTO toUpdateDto(
            OrderDetails orderDetails,
            ReviewCheckUpdateRequest request,
            boolean allowPublicationFields
    ) {
        if (request == null || request.reviews() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзывы не переданы");
        }
        if (request.comment() != null) {
            requireMaxLength(
                    request.comment(),
                    MAX_PUBLIC_TEXT_LENGTH,
                    "Комментарий к проверке слишком длинный"
            );
        }

        List<Review> currentReviews = safeReviews(orderDetails);
        if (request.reviews().size() > MAX_PUBLIC_REVIEW_ITEMS
                || request.reviews().size() > currentReviews.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Передано слишком много отзывов");
        }
        Map<Long, Review> currentReviewsById = new HashMap<>();
        for (Review currentReview : currentReviews) {
            if (currentReview.getId() != null) {
                currentReviewsById.put(currentReview.getId(), currentReview);
            }
        }
        Set<Long> submittedReviewIds = new HashSet<>();

        List<ReviewDTO> reviews = request.reviews().stream()
                .map(item -> {
                    if (item == null
                            || item.id() == null
                            || !submittedReviewIds.add(item.id())
                            || !currentReviewsById.containsKey(item.id())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не относится к этой проверке");
                    }

                    Review current = currentReviewsById.get(item.id());

                    if (item.text() != null) {
                        requireMaxLength(item.text(), MAX_PUBLIC_TEXT_LENGTH, "Текст отзыва слишком длинный");
                    }
                    if (item.answer() != null) {
                        requireMaxLength(item.answer(), MAX_PUBLIC_TEXT_LENGTH, "Замечание к отзыву слишком длинное");
                    }
                    if (allowPublicationFields
                            && item.url() != null
                            && !Objects.equals(item.url(), current.getUrl())) {
                        validateChangedReviewUrl(item.url());
                    }

                    return ReviewDTO.builder()
                            .id(current.getId())
                            .text(valueOrCurrent(item.text(), current.getText()))
                            .answer(valueOrCurrent(item.answer(), current.getAnswer()))
                            .publish(allowPublicationFields && item.publish() != null ? item.publish() : current.isPublish())
                            .publishedDate(allowPublicationFields
                                    ? parseDateOrCurrent(item.publishedDate(), current.getPublishedDate())
                                    : current.getPublishedDate())
                            .url(allowPublicationFields ? valueOrCurrent(item.url(), current.getUrl()) : safe(current.getUrl()))
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
        return findLiveReviewCheckDetails(orderDetailId)
                .map(orderDetails -> buildLiveResponse(orderDetails, authentication))
                .orElseGet(() -> buildArchivedResponse(orderDetailId, authentication));
    }

    private ReviewCheckResponse buildLiveResponse(OrderDetails orderDetails, Authentication authentication) {
        Order order = requireOrder(orderDetails);
        Company company = order.getCompany();
        Filial filial = order.getFilial();
        List<Review> reviews = safeReviews(orderDetails);
        ReviewCheckPermissions permissions = livePermissions(order, authentication);

        boolean approved = isApprovedForPublication(reviews);
        String workerFio = workerFio(reviews, order);

        return new ReviewCheckResponse(
                orderDetails.getId(),
                permissions.canOpenManagerLinks() ? order.getId() : null,
                permissions.canOpenManagerLinks() && company != null ? company.getId() : null,
                company != null ? safe(company.getTitle()) : "",
                filial != null ? safe(filial.getTitle()) : "",
                order.getStatus() != null ? safe(order.getStatus().getTitle()) : "",
                permissions.canSeeInternalInfo() ? workerFio : "",
                permissions.canSeeInternalInfo() ? safe(order.getZametka()) : "",
                permissions.canSeeInternalInfo() && company != null ? safe(company.getCommentsCompany()) : "",
                safe(orderDetails.getComment()),
                orderDetails.getAmount(),
                order.getCounter(),
                order.getSum(),
                approved,
                reviews.stream()
                        .map(review -> toReviewResponse(review, orderDetails, order, permissions))
                        .toList(),
                permissions
        );
    }

    private ReviewCheckResponse buildArchivedResponse(UUID orderDetailId, Authentication authentication) {
        ArchivedReviewCheck archived = reviewCheckArchiveService.findByOrderDetailId(orderDetailId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена"));
        ReviewCheckPermissions permissions = archivedPermissions(authentication, archived);
        boolean canSeeCurrentCompanyComments = permissions.canSeeInternalInfo()
                && managerAccessService.canAccessCompany(archived.companyId(), authentication);
        boolean approved = isApprovedForPublicationArchived(archived.reviews());

        return new ReviewCheckResponse(
                archived.orderDetailId(),
                permissions.canOpenManagerLinks() ? archived.orderId() : null,
                permissions.canOpenManagerLinks() ? archived.companyId() : null,
                archived.companyTitle(),
                archived.filialTitle(),
                archived.status(),
                permissions.canSeeInternalInfo() ? archived.workerFio() : "",
                permissions.canSeeInternalInfo() ? archived.orderComments() : "",
                canSeeCurrentCompanyComments ? archived.companyComments() : "",
                archived.comment(),
                archived.amount(),
                archived.counter(),
                archived.sum(),
                approved,
                archived.reviews().stream()
                        .map(review -> toArchivedReviewResponse(
                                review,
                                archived,
                                permissions,
                                canSeeCurrentCompanyComments
                        ))
                        .toList(),
                permissions
        );
    }

    private ReviewCheckReviewResponse buildReviewResponse(UUID orderDetailId, Long reviewId, Authentication authentication) {
        OrderDetails orderDetails = reviewCheckDetails(orderDetailId);
        Order order = requireOrder(orderDetails);
        Review review = requireReviewInDetails(orderDetails, reviewId);
        return toReviewResponse(review, orderDetails, order, livePermissions(order, authentication));
    }

    private boolean isApprovedForPublication(List<Review> reviews) {
        return reviews != null
                && !reviews.isEmpty()
                && reviews.stream().allMatch(review -> review != null
                && review.getPublishedDate() != null
                && !isBlankOrPlaceholder(review.getText()));
    }

    private boolean isApprovedForPublicationArchived(List<ArchivedReviewCheckReview> reviews) {
        return reviews != null
                && !reviews.isEmpty()
                && reviews.stream().allMatch(review -> review != null
                && review.publishedDate() != null
                && !isBlankOrPlaceholder(review.text()));
    }

    private OrderDetails reviewCheckDetails(UUID orderDetailId) {
        return findLiveReviewCheckDetails(orderDetailId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена"));
    }

    private OrderDetails reviewCheckDetailsForAction(
            UUID orderDetailId,
            String restoreTargetStatus,
            Authentication authentication
    ) {
        return findLiveReviewCheckDetails(orderDetailId)
                .orElseGet(() -> restoreArchivedReviewCheck(orderDetailId, restoreTargetStatus, authentication));
    }

    private Optional<OrderDetails> findLiveReviewCheckDetails(UUID orderDetailId) {
        try {
            return Optional.of(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId));
        } catch (UsernameNotFoundException exception) {
            return Optional.empty();
        }
    }

    private OrderDetails restoreArchivedReviewCheck(
            UUID orderDetailId,
            String restoreTargetStatus,
            Authentication authentication
    ) {
        try {
            reviewCheckArchiveService.restoreByOrderDetailId(orderDetailId, restoreTargetStatus, restoredBy(authentication));
        } catch (ArchiveRestoreConflictException exception) {
            return findLiveReviewCheckDetails(orderDetailId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Архивная проверка отзывов не найдена", exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, exception.getMessage(), exception);
        }

        return findLiveReviewCheckDetails(orderDetailId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Заказ восстановлен из архива, но проверка отзывов не найдена"
                ));
    }

    private ReviewCheckReviewResponse toReviewResponse(
            Review review,
            OrderDetails orderDetails,
            Order order,
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
                permissions.canSeeInternalInfo() ? safe(order.getZametka()) : "",
                permissions.canSeeInternalInfo()
                        && order.getCompany() != null
                        ? safe(order.getCompany().getCommentsCompany())
                        : "",
                product != null ? safe(product.getTitle()) : "",
                product != null && Boolean.TRUE.equals(product.getPhoto()),
                safe(review.getUrl()),
                dateValue(review.getPublishedDate()),
                review.isPublish()
        );
    }

    private ReviewCheckReviewResponse toArchivedReviewResponse(
            ArchivedReviewCheckReview review,
            ArchivedReviewCheck archived,
            ReviewCheckPermissions permissions,
            boolean canSeeCurrentCompanyComments
    ) {
        return new ReviewCheckReviewResponse(
                review.id(),
                safe(review.text()),
                safe(review.answer()),
                permissions.canSeeBot() ? safe(review.botName()) : "",
                permissions.canSeeInternalInfo() ? safe(archived.comment()) : "",
                permissions.canSeeInternalInfo() ? safe(archived.orderComments()) : "",
                canSeeCurrentCompanyComments ? safe(archived.companyComments()) : "",
                safe(review.productTitle()),
                review.productPhoto(),
                safe(review.url()),
                dateValue(review.publishedDate()),
                review.publish()
        );
    }

    private ReviewCheckPermissions permissionsForOrder(Order order, Authentication authentication) {
        boolean authenticated = isAuthenticated(authentication);
        boolean objectAccess = authenticated
                && order != null
                && order.getId() != null
                && managerAccessService.canAccessOrder(order.getId(), authentication);
        boolean canManage = objectAccess && hasAnyRole(authentication, "MANAGER", "ADMIN", "OWNER");
        boolean workerOnly = objectAccess && hasRole(authentication, "WORKER") && !canManage;

        return new ReviewCheckPermissions(
                authenticated,
                objectAccess,
                objectAccess,
                !workerOnly,
                true,
                !workerOnly,
                objectAccess && hasAnyRole(authentication, "WORKER", "ADMIN"),
                objectAccess && hasAnyRole(authentication, "MANAGER", "ADMIN", "OWNER"),
                canManage,
                canManage
        );
    }

    private ReviewCheckPermissions livePermissions(Order order, Authentication authentication) {
        ReviewCheckPermissions base = permissionsForOrder(order, authentication);
        if (base.canSeeInternalInfo() || publicMutationPolicy.clientMutationAllowed(order)) {
            return base;
        }

        return new ReviewCheckPermissions(
                base.authenticated(),
                base.canSeeInternalInfo(),
                base.canSeeBot(),
                false,
                false,
                false,
                base.canSendToCheck(),
                base.canMarkPaid(),
                base.canOpenManagerLinks(),
                base.canEditNotes()
        );
    }

    private ReviewCheckPermissions archivedPermissions(
            Authentication authentication,
            ArchivedReviewCheck archived
    ) {
        boolean authenticated = isAuthenticated(authentication);
        boolean objectAccess = authenticated
                && archived != null
                && managerAccessService.canAccessArchivedOrder(
                        archived.managerId(),
                        archived.workerId(),
                        authentication
                );
        boolean canManage = objectAccess && hasAnyRole(authentication, "MANAGER", "ADMIN", "OWNER");
        boolean workerOnly = objectAccess && hasRole(authentication, "WORKER") && !canManage;
        ReviewCheckPermissions base = new ReviewCheckPermissions(
                authenticated,
                objectAccess,
                objectAccess,
                !workerOnly,
                true,
                !workerOnly,
                false,
                false,
                false,
                false
        );
        boolean mutationAllowed = archived != null && !archived.terminalPaidOrder();
        return new ReviewCheckPermissions(
                base.authenticated(),
                base.canSeeInternalInfo(),
                base.canSeeBot(),
                mutationAllowed && base.canApprovePublication(),
                mutationAllowed && base.canSave(),
                mutationAllowed && base.canSendCorrection(),
                false,
                false,
                false,
                false
        );
    }

    private String restoredBy(Authentication authentication) {
        return isAuthenticated(authentication) ? authentication.getName() : "anonymous-review-check";
    }

    private String approvalAuditDetails(Authentication authentication, HttpServletRequest request) {
        StringBuilder details = new StringBuilder();
        appendDetail(details, "identity", isAuthenticated(authentication) ? "authenticated" : "anonymous_public_link");
        appendDetail(details, "actor", isAuthenticated(authentication) ? authentication.getName() : null);
        appendDetail(details, "ip", clientIp(request));
        appendDetail(details, "userAgent", header(request, "User-Agent"));
        appendDetail(details, "origin", header(request, "Origin"));
        return details.toString();
    }

    private void appendDetail(StringBuilder details, String key, String value) {
        String sanitized = sanitizeAuditDetailValue(value);
        if (sanitized.isBlank()) {
            return;
        }
        details.append(key).append('=').append(sanitized).append(';');
    }

    private String sanitizeAuditDetailValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), MAX_AUDIT_DETAIL_VALUE_LENGTH));
        int codePoints = 0;
        for (int offset = 0; offset < value.length() && codePoints < MAX_AUDIT_DETAIL_VALUE_LENGTH; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            codePoints++;

            if (codePoint == ';' || codePoint == '=') {
                sanitized.append(',');
                continue;
            }

            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                sanitized.append(' ');
                continue;
            }

            sanitized.appendCodePoint(codePoint);
        }
        return sanitized.toString().strip();
    }

    private String clientIp(HttpServletRequest request) {
        return request == null ? null : clientIpResolver.resolve(request);
    }

    private String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private void requireLiveClientMutationAllowed(OrderDetails orderDetails, Authentication authentication) {
        Order order = requireOrder(orderDetails);
        if (permissionsForOrder(order, authentication).canSeeInternalInfo()) {
            return;
        }
        publicMutationPolicy.requireClientMutationAllowed(order);
    }

    private void requireCanEditNotes(Order order, Authentication authentication) {
        if (!permissionsForOrder(order, authentication).canEditNotes()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для редактирования заметок");
        }
    }

    private Review requireReviewInDetails(OrderDetails orderDetails, Long reviewId) {
        return safeReviews(orderDetails).stream()
                .filter(review -> Objects.equals(review.getId(), reviewId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этой проверке"));
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
            LocalDate parsed = LocalDate.parse(value);
            LocalDate maxAllowed = maxAllowedDate();
            if (parsed.isAfter(maxAllowed)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Дата публикации слишком далеко: максимум " + maxAllowed
                                + " (" + MAX_FUTURE_DAYS + " дней вперед)"
                );
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная дата публикации");
        }
    }

    private String valueOrCurrent(String value, String current) {
        return value != null ? value : safe(current);
    }

    private void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.codePointCount(0, value.length()) > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private void validateChangedReviewUrl(String value) {
        requireMaxLength(value, MAX_PUBLIC_URL_LENGTH, "Ссылка на отзыв слишком длинная");
        if (value.isBlank()) {
            return;
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidReviewUrl();
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean webScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            boolean hasAuthority = uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank();
            if (!webScheme || !hasAuthority || uri.getRawUserInfo() != null) {
                throw invalidReviewUrl();
            }
        } catch (URISyntaxException exception) {
            throw invalidReviewUrl();
        }
    }

    private ResponseStatusException invalidReviewUrl() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Ссылка на отзыв должна использовать http или https без учетных данных"
        );
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

    public record ReviewCheckReviewTextUpdateRequest(String text) {
    }

    public record ReviewCheckReviewAnswerUpdateRequest(String answer) {
    }

    public record ReviewCheckOrderNoteUpdateRequest(String orderComments) {
    }

    public record ReviewCheckCompanyNoteUpdateRequest(String companyComments) {
    }

    public record ReviewCheckNotesResponse(
            String orderComments,
            String companyComments
    ) {
    }
}
