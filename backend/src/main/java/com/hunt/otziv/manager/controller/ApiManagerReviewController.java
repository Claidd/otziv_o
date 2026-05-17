package com.hunt.otziv.manager.controller;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.manager.dto.api.BadReviewTaskUpdateRequest;
import com.hunt.otziv.manager.dto.api.CompanyNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.OrderDetailsResponse;
import com.hunt.otziv.manager.dto.api.OrderNotesResponse;
import com.hunt.otziv.manager.dto.api.OrderNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewAnswerUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewDetailsResponse;
import com.hunt.otziv.manager.dto.api.ReviewEditorUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewNoteUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewRecoveryTaskUpdateRequest;
import com.hunt.otziv.manager.dto.api.ReviewTextUpdateRequest;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftTarget;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.application.ReputationSingleReviewDraftService;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftItem;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReputationSingleReviewDraftService reputationSingleReviewDraftService;
    private final UserService userService;
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
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
    public ReviewDetailsResponse changeOrderReviewText(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        if (!autoTextService.changeReviewText(reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не изменен");
        }

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse updateOrderReview(
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

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/photo")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse uploadOrderReviewPhoto(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не выбран");
        }

        ReviewDTO current = requireReviewForOrder(orderId, reviewId);
        String newUrl = s3UploadService.uploadFile(file, "reviews", current.getUrl(), reviewId);
        reviewService.updateReviewPhoto(reviewId, newUrl);

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse updateOrderReviewText(
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

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/answer")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse updateOrderReviewAnswer(
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

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse updateOrderReviewNote(
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

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PutMapping("/orders/{orderId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderNotesResponse updateOrderNote(
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

        return new OrderNotesResponse(
                normalize(order.getZametka()),
                order.getCompany() != null ? normalize(order.getCompany().getCommentsCompany()) : ""
        );
    }

    @PutMapping("/orders/{orderId}/company-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderNotesResponse updateOrderCompanyNote(
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

        return new OrderNotesResponse(normalize(order.getZametka()), normalize(company.getCommentsCompany()));
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse changeOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        reviewService.changeBot(reviewId);
        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/new-account")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse assignOrderReviewNewAccount(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);

        try {
            reviewService.assignNewAccount(reviewId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Новый аккаунт не назначен: " + exception.getMessage(), exception);
        }

        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/bots/{botId}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReviewDetailsResponse deactivateOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @PathVariable Long botId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        reviewService.deActivateAndChangeBot(reviewId, botId);
        return managerBoardEditAssembler.buildReviewDetailsResponse(orderId, reviewId);
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

    @PutMapping("/orders/{orderId}/bad-review-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse updateBadReviewTask(
            @PathVariable Long orderId,
            @PathVariable Long taskId,
            @RequestBody BadReviewTaskUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные плохой задачи не переданы");
        }
        try {
            requireBadReviewTaskForOrder(orderId, taskId);
            badReviewTaskService.updateTask(taskId, request.taskText(), request.scheduledDate());
            return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/orders/{orderId}/bad-review-tasks/{taskId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse changeBadReviewTaskBot(
            @PathVariable Long orderId,
            @PathVariable Long taskId,
            Authentication authentication
    ) {
        try {
            requireBadReviewTaskForOrder(orderId, taskId);
            badReviewTaskService.changeTaskBot(taskId);
            return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/orders/{orderId}/bad-review-tasks/{taskId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse completeBadReviewTask(
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
            badReviewTaskService.completeTask(taskId);
            return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/recovery-tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse createReviewRecoveryTask(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        reviewRecoveryTaskService.createTask(reviewId, currentUser(authentication));
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/help-draft")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ReputationSingleReviewDraftResult createReviewHelpDraft(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody(required = false) ReputationSingleReviewDraftRequest request
    ) {
        ReviewDTO review = requireReviewForOrder(orderId, reviewId);
        Order order = orderService.getOrder(orderId);
        if (order == null || order.getCompany() == null || order.getCompany().getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }
        if (!isNewOrderStatus(order.getStatus() == null ? "" : order.getStatus().getTitle())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI-помощь доступна только для заказа в статусе Новый");
        }

        ReputationSingleReviewDraftRequest safeRequest = request == null
                ? new ReputationSingleReviewDraftRequest(null, null, null, null, null, null, null)
                : request;
        ReputationSingleReviewDraftRequest enrichedRequest = new ReputationSingleReviewDraftRequest(
                safeRequest.deepReportJobId(),
                safeRequest.contentPackJobId(),
                safeRequest.idea(),
                safeRequest.style(),
                safeRequest.authorType(),
                safeRequest.emojiMode(),
                safeRequest.manualNotes(),
                safeRequest.length(),
                safeRequest.contentPackProfile(),
                reviewId,
                safeRequest.previousDraft(),
                orderReviewHelpContext(order, review, safeRequest.orderContext())
        );

        try {
            return reputationSingleReviewDraftService.generate(order.getCompany().getId(), enrichedRequest);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/orders/{orderId}/reviews/help-drafts")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse createReviewHelpDrafts(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        OrderDetailsResponse details = managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
        if (!isNewOrderStatus(details.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI-помощь доступна только для заказа в статусе Новый");
        }
        if (details.companyId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }
        List<ReputationBatchReviewDraftTarget> targets = details.reviews().stream()
                .filter(review -> review.id() != null)
                .map(review -> new ReputationBatchReviewDraftTarget(
                        review.id(),
                        batchReviewHelpIdea(details, review),
                        review.text(),
                        orderReviewHelpContext(details, review)
                ))
                .toList();
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "В заказе нет отзывов для AI-помощи");
        }

        ReputationBatchReviewDraftRequest request = new ReputationBatchReviewDraftRequest(
                null,
                null,
                "живой, естественный, разные тона и структуры без одинаковых заходов",
                "разные обычные клиенты",
                "без смайлов",
                "",
                "mixed",
                "quality",
                targets
        );

        ReputationBatchReviewDraftResult result;
        try {
            result = reputationSingleReviewDraftService.generateBatch(details.companyId(), request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }

        int saved = 0;
        for (ReputationBatchReviewDraftItem draft : result.drafts()) {
            if (draft.reviewId() == null || isBlank(draft.draft())) {
                continue;
            }
            if (reviewService.updateReviewText(orderId, draft.reviewId(), draft.draft())) {
                saved++;
            }
        }
        if (saved == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI-помощь не вернула тексты для карточек заказа");
        }

        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/recovery-tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse updateReviewRecoveryTask(
            @PathVariable Long orderId,
            @PathVariable Long taskId,
            @RequestBody ReviewRecoveryTaskUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные восстановления не переданы");
        }
        requireRecoveryTaskForOrder(orderId, taskId);

        reviewRecoveryTaskService.updateTask(taskId, request.recoveryText(), request.scheduledDate());
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/recovery-tasks/{taskId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse completeReviewRecoveryTask(
            @PathVariable Long orderId,
            @PathVariable Long taskId,
            Authentication authentication
    ) {
        requireRecoveryTaskForOrder(orderId, taskId);
        reviewRecoveryTaskService.completeTask(taskId, currentUser(authentication));
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/recovery-batches/{batchId}/client-notified")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse markRecoveryClientNotified(
            @PathVariable Long orderId,
            @PathVariable Long batchId,
            Authentication authentication
    ) {
        requireRecoveryBatchForOrder(orderId, batchId);
        reviewRecoveryTaskService.markClientNotified(batchId, currentUser(authentication));
        return managerBoardEditAssembler.buildOrderDetailsResponse(orderId, authentication);
    }

    private <T> T firstValue(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private boolean isNewOrderStatus(String status) {
        return "новый".equals(normalize(status).toLowerCase(Locale.ROOT).replace('ё', 'е'));
    }

    private String batchReviewHelpIdea(OrderDetailsResponse details, ReviewDetailsResponse review) {
        String reviewNote = normalize(review.comment());
        if (!reviewNote.isBlank()) {
            return "Живой отзыв клиента по заметке карточки: " + limit(reviewNote, 220);
        }

        String service = firstCustomerServiceTitle(
                review.productTitle(),
                details.productTitle(),
                review.subCategory(),
                review.category()
        );
        if (!service.isBlank()) {
            return "Живой отзыв клиента о " + limit(service, 140) + ".";
        }

        String category = firstNonBlank(review.subCategory(), review.category());
        if (!category.isBlank()) {
            return "Живой отзыв клиента по направлению " + limit(category, 140) + ".";
        }

        return "Живой отзыв клиента по фактам заказа.";
    }

    private String firstCustomerServiceTitle(String... values) {
        for (String value : values) {
            String clean = normalize(value);
            if (!clean.isBlank() && !isInternalReviewProduct(clean)) {
                return clean;
            }
        }
        return "";
    }

    private boolean isInternalReviewProduct(String value) {
        String clean = normalize(value).toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.contains("отзыв")
                || clean.contains("2гис")
                || clean.contains("2gis")
                || clean.contains("яндекс")
                || clean.contains("google")
                || clean.contains("карты")
                || clean.contains("репутац");
    }

    private String orderReviewHelpContext(Order order, ReviewDTO review, String externalContext) {
        List<String> lines = new ArrayList<>();
        addContextLine(lines, "Заказ", order.getId() == null ? "" : "#" + order.getId());
        addContextLine(lines, "Статус заказа", order.getStatus() == null ? "" : order.getStatus().getTitle());
        addContextLine(lines, "Компания", order.getCompany() == null ? "" : order.getCompany().getTitle());
        addContextLine(lines, "Город компании", order.getCompany() == null ? "" : order.getCompany().getCity());
        addContextLine(lines, "Категория компании", order.getCompany() == null || order.getCompany().getCategoryCompany() == null
                ? "" : order.getCompany().getCategoryCompany().getCategoryTitle());
        addContextLine(lines, "Подкатегория компании", order.getCompany() == null || order.getCompany().getSubCategory() == null
                ? "" : order.getCompany().getSubCategory().getSubCategoryTitle());
        addContextLine(lines, "Филиал", order.getFilial() == null ? "" : order.getFilial().getTitle());
        addContextLine(lines, "Город филиала", order.getFilial() == null || order.getFilial().getCity() == null
                ? "" : order.getFilial().getCity().getTitle());
        addContextLine(lines, "Заметка заказа", order.getZametka());
        addContextLine(lines, "Заметка компании", order.getCompany() == null ? "" : order.getCompany().getCommentsCompany());

        addContextLine(lines, "Отзыв", review.getId() == null ? "" : "#" + review.getId());
        addContextLine(lines, "Категория отзыва", review.getCategory() == null ? "" : review.getCategory().getCategoryTitle());
        addContextLine(lines, "Подкатегория отзыва", review.getSubCategory() == null ? "" : review.getSubCategory().getSubCategoryTitle());
        addContextLine(lines, "Товар/услуга отзыва", reviewProductLine(review));
        addContextLine(lines, "Заметка отзыва", review.getComment());

        List<OrderDetails> details = order.getDetails();
        if (details != null && !details.isEmpty()) {
            int index = 1;
            for (OrderDetails detail : details) {
                if (index > 8) {
                    break;
                }
                addContextLine(lines, "Позиция заказа " + index, orderDetailLine(detail));
                index++;
            }
        }

        if (!isBlank(externalContext)) {
            addContextLine(lines, "Контекст из карточки", externalContext);
        }

        return String.join("\n", lines);
    }

    private String orderReviewHelpContext(OrderDetailsResponse details, ReviewDetailsResponse review) {
        List<String> lines = new ArrayList<>();
        addContextLine(lines, "Заказ", details.orderId() == null ? "" : "#" + details.orderId());
        addContextLine(lines, "Статус заказа", details.status());
        addContextLine(lines, "Компания", details.companyTitle());
        addContextLine(lines, "Общий товар/услуга заказа", details.productTitle());
        addContextLine(lines, "Количество в заказе", details.amount() == null ? "" : String.valueOf(details.amount()));
        addContextLine(lines, "Сумма заказа", money(details.sum()));
        addContextLine(lines, "Заметка заказа", details.orderComments());
        addContextLine(lines, "Заметка компании", details.companyComments());
        addContextLine(lines, "Отзыв", review.id() == null ? "" : "#" + review.id());
        addContextLine(lines, "Категория отзыва", review.category());
        addContextLine(lines, "Подкатегория отзыва", review.subCategory());
        addContextLine(lines, "Филиал", review.filialTitle());
        addContextLine(lines, "Город филиала", review.filialCity());
        addContextLine(lines, "Товар/услуга отзыва", review.productTitle());
        addContextLine(lines, "Цена отзыва", money(review.price()));
        addContextLine(lines, "Заметка отзыва", review.comment());
        return String.join("\n", lines);
    }

    private String reviewProductLine(ReviewDTO review) {
        Product product = review.getProduct();
        List<String> parts = new ArrayList<>();
        if (product != null) {
            addPart(parts, product.getTitle());
            addPart(parts, money(product.getPrice()));
        }
        addPart(parts, money(review.getPrice()));
        return String.join("; ", parts);
    }

    private String orderDetailLine(OrderDetails detail) {
        if (detail == null) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        Product product = detail.getProduct();
        if (product != null) {
            addPart(parts, product.getTitle());
            addPart(parts, money(product.getPrice()));
        }
        if (detail.getAmount() > 0) {
            addPart(parts, "количество: " + detail.getAmount());
        }
        addPart(parts, money(detail.getPrice()));
        addPart(parts, detail.getComment());
        return String.join("; ", parts);
    }

    private void addContextLine(List<String> lines, String label, String value) {
        if (isBlank(value)) {
            return;
        }

        lines.add(label + ": " + limit(value, 700));
    }

    private void addPart(List<String> parts, String value) {
        if (!isBlank(value)) {
            parts.add(limit(value, 180));
        }
    }

    private String money(BigDecimal value) {
        if (value == null || BigDecimal.ZERO.compareTo(value) == 0) {
            return "";
        }

        return value.stripTrailingZeros().toPlainString() + " руб.";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value).replaceAll("\\s+", " ");
        if (clean.length() <= maxLength) {
            return clean;
        }

        return clean.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
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

    private void requireRecoveryTaskForOrder(Long orderId, Long taskId) {
        if (!reviewRecoveryTaskService.taskBelongsToOrder(taskId, orderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача восстановления не найдена в этом заказе");
        }
    }

    private void requireBadReviewTaskForOrder(Long orderId, Long taskId) {
        boolean belongsToOrder = badReviewTaskService.getTasksByOrderId(orderId).stream()
                .anyMatch(task -> Objects.equals(task.getId(), taskId));
        if (!belongsToOrder) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Плохая задача не найдена в этом заказе");
        }
    }

    private void requireRecoveryBatchForOrder(Long orderId, Long batchId) {
        if (!reviewRecoveryTaskService.batchBelongsToOrder(batchId, orderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пачка восстановления не найдена в этом заказе");
        }
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || isBlank(authentication.getName())) {
            return null;
        }

        return userService.findByUserName(authentication.getName()).orElse(null);
    }
}
