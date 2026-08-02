package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.config.legacy.LegacyMvc;

import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckMutationLockService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckPublicMutationPolicy;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Controller
@LegacyMvc
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/review")
public class ReviewController {

    private static final int MAX_PUBLIC_REVIEW_ITEMS = 1_000;
    private static final int MAX_PUBLIC_TEXT_LENGTH = 5_000;

    private final ReviewService reviewService;
    private final OrderDetailsService orderDetailsService;
    private final OrderService orderService;
    private final ProductService productService;
    private final ReviewCheckMutationLockService mutationLockService;
    private final OrderPublicationApprovalService publicationApprovalService;
    private final ManagerAccessService managerAccessService;
    private final ReviewCheckPublicMutationPolicy publicMutationPolicy;

    //    =========================================== REVIEW EDIT =======================================================
    @GetMapping("/editReview/{reviewId}")
    String ReviewEdit(@PathVariable Long reviewId, Model model){
        ReviewDTO reviewDTO = reviewService.getReviewDTOById(reviewId);
        model.addAttribute("reviewDTO", reviewDTO);
        model.addAttribute("products", productService.findAll());
        model.addAttribute("companyId", reviewDTO.getOrderDetails().getOrder().getCompany().getId());
        model.addAttribute("orderId", reviewDTO.getOrderDetails().getOrder().getId());
        return "products/review_edit";
    } // Страница редактирования Заказа - Get

    @PostMapping("/editReview/{reviewId}") // Страница редактирования Заказа - Post
    String ReviewEditPost(@ModelAttribute("reviewDTO") ReviewDTO reviewDTO, @PathVariable Long reviewId, RedirectAttributes rm, Model model, Principal principal){
        String userRole = getRole(principal);
        log.info("1. Начинаем обновлять данные отзыва. - {}", principal != null ? principal.getName() : "Гость");
        reviewService.updateReview(userRole, reviewDTO, reviewId);
        log.info("5. Обновление отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReview/{reviewId}";
    } // Страница редактирования Заказа - Post

    @PostMapping("/addReviews/{companyId}/{orderId}") // Добавить новый отзыв - Post
    String ReviewAdd(@PathVariable Long orderId, @PathVariable Long companyId, RedirectAttributes rm, Model model, Principal principal){
        log.info("1. Начинаем добавлять новый Отзыв. - {}", principal != null ? principal.getName() : "Гость");
        if (orderService.addNewReview(orderId)){
            log.info("5. Добавили новый отзыв");
            rm.addFlashAttribute("saveSuccess", "true");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        log.info("3. Неудачная попытка");
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    } // Добавить новый отзыв - Post


    @PostMapping("/deleteReviews/{companyId}/{orderId}/{reviewId}") // Удалить отзыв - Post
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER')")
    String ReviewDelete(@PathVariable Long orderId, @PathVariable Long companyId, @PathVariable Long reviewId, RedirectAttributes rm, Model model, Principal principal){
        log.info("1. Начинаем удалять новый Отзыв. - {}", principal != null ? principal.getName() : "Гость");
        if (orderService.deleteNewReview(orderId, reviewId)){
            log.info("2. Удалили отзыв");
            rm.addFlashAttribute("saveSuccess", "true");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        log.info("2. Неудачная попытка удаления");
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    } // Удалить отзыв - Post


//    ==========================================================================================================

    //    =========================================== REVIEW'S EDIT =======================================================
    @GetMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Get
    String ReviewsEdit(@PathVariable UUID orderDetailId, Model model, @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "Все") String status){
        long startTime = System.nanoTime();
        OrderDetailsDTO orderDetailsDTO = orderDetailsService.getOrderDetailDTOById(orderDetailId);
        if (orderDetailsDTO.getReviews().isEmpty()) {
            model.addAttribute("orderDetailDTO", orderDetailsDTO);
            model.addAttribute("orderDetailId", orderDetailId);
//            model.addAttribute("statusCheck", orderDetailsDTO.getReviews().get(0).getPublishedDate());
            model.addAttribute("errorMessage", "Список отзывов пуст. Сообщите менеджеру об этом");
            checkTimeMethod("Время выполнения страницы проверки отзыов для клиента /review/editReviews/{orderDetailId} для всех: ", startTime);
            return "products/reviews_edit";
        } else {
            model.addAttribute("orderDetailDTO", orderDetailsDTO);
            model.addAttribute("orderDetailId", orderDetailId);
            model.addAttribute("statusCheck", orderDetailsDTO.getReviews().getFirst().getPublishedDate());
            model.addAttribute("companyKeyword", orderDetailsDTO.getOrder().getCompany().getTitle());
//        model.addAttribute("address", orderDetailsDTO.getOrder().getFilial().getTitle());
            checkTimeMethod("Время выполнения страницы проверки отзыов для клиента /review/editReviews/{orderDetailId} для всех: ", startTime);
            return "products/reviews_edit";
        }

    } // Страница редактирования Заказа - Get

    @PostMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Post - СОХРАНИТЬ
    @Transactional(rollbackFor = Exception.class)
    String ReviewsEditPost(
            @PathVariable UUID orderDetailId,
            @ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailsDTO,
            RedirectAttributes rm,
            Model model,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        log.info("1. Начинаем обновлять данные Отзыва. - {}", authentication != null ? authentication.getName() : "Гость");
        OrderDetailsDTO boundDetails = bindSharedReviewForm(orderDetailId, orderDetailsDTO);
        OrderDetails liveDetails = liveSharedReview(orderDetailId);
        requirePublicMutationAllowed(liveDetails.getOrder(), authentication);
        reviewService.updateOrderDetailAndReviews(boundDetails);
        log.info("5. Обновление Отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    } // Страница редактирования Заказа - Post - СОХРАНИТЬ

    @PostMapping("/editReviews/{orderDetailId}/payOk") // Страница редактирования Заказа - Post - СОХРАНИТЬ
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @Transactional(rollbackFor = Exception.class)
    String OrderPayOkPost(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model, Authentication authentication, @PathVariable UUID orderDetailId) throws Exception {
        requirePayOkRole(authentication);
        mutationLockService.lock(orderDetailId);
        log.info("1. Начинаем менять статус заказа на Оплачено. - {}", authentication != null ? authentication.getName() : "Гость");
        OrderDetailsDTO boundDetails = bindSharedReviewAction(orderDetailId, orderDetailDTO);
        Order order = liveSharedReview(boundDetails.getId()).getOrder();
        managerAccessService.requireOrderAccess(order.getId(), authentication);
        if (order.getAmount() <= order.getCounter()){
            if (!orderService.changeStatusForOrder(order.getId(), "Оплачено")) {
                markCurrentTransactionRollbackOnly();
                rm.addFlashAttribute("errorMessage", "Не удалось отметить заказ оплаченным");
                return "redirect:/review/editReviews/{orderDetailId}";
            }
            log.info("статус заказа успешно изменен на Оплачено");
        }
        else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
        }
        log.info("5. Отметка об оплате заказа и смена статуса прошли успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    } // Страница редактирования Заказа - Post - СОХРАНИТЬ

    @PostMapping("/editReviews/{orderDetailId}/publish")
    @Transactional(rollbackFor = Exception.class)
    String ReviewsEditPostToPublish(
            @PathVariable UUID orderDetailId,
            @ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailsDTO,
            RedirectAttributes rm,
            Model model,
            Authentication authentication
    ) {
        mutationLockService.lock(orderDetailId);
        log.info("1. Начинаем обновлять данные Отзыва и публиковать - {}",
                authentication != null ? authentication.getName() : "Гость");

        OrderDetailsDTO boundDetails = bindSharedReviewForm(orderDetailId, orderDetailsDTO);
        try {
            OrderDetails liveDetails = liveSharedReview(orderDetailId);
            Order order = liveDetails.getOrder();
            requirePublicDecisionAllowed(order, authentication);
            publicMutationPolicy.requireCompleteReviewSet(liveDetails, boundDetails);

            if (hasInvalidReviewText(boundDetails)) {
                rm.addFlashAttribute("errorMessage", "Заполните текст всех отзывов перед публикацией");
                return "redirect:/review/editReviews/{orderDetailId}";
            }

            reviewService.updateOrderDetailAndReviews(boundDetails);
            publicationApprovalService.approvePreparedOrder(
                    order.getId(),
                    List.of(boundDetails),
                    legacyApprovalAuditDetails(authentication)
            );
            log.info("5. Обновление отзыва и дат публикации прошло успешно");
            rm.addFlashAttribute("saveSuccess", "true");

            return "redirect:/review/editReviews/{orderDetailId}";

        } catch (Exception e) {
            log.error("2. Произошла ошибка при публикации: ", e);
            markCurrentTransactionRollbackOnly();
            rm.addFlashAttribute("errorMessage", "Ошибка публикации: " + e.getMessage());
            return "redirect:/review/editReviews/{orderDetailId}";
        }
    }

//    @PostMapping("/editReviews/{orderDetailId}/publish") // Страница редактирования Заказа - Post - ОПУБЛИКОВАТЬ
//    String ReviewsEditPostToPublish(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model, Principal principal) throws Exception {
//        log.info("1. Начинаем обновлять данные Отзыва3. - {}", principal != null ? principal.getName() : "Гость");
//        if (reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailDTO)){
//            log.info("Начинаем обновлять статус заказа");
//            orderService.changeStatusForOrder(orderDetailDTO.getOrder().getId(), "Публикация");
//            log.info("Обновили статус заказа");
//            log.info("5. Обновление Отзыва прошло успешно3");
//            rm.addFlashAttribute("saveSuccess", "true");
//            Long companyId = orderDetailDTO.getOrder().getCompany().getId();
//            return "redirect:/review/editReviews/{orderDetailId}";
//        }
//        else {
//            log.info("2. Произошла какая-то ошибка");
//            return "redirect:/review/editReviews/{orderDetailId}";
//        }
//    } // Страница редактирования Заказа - Post - ОПУБЛИКОВАТЬ

    @PostMapping("/editReviewses/{orderDetailId}") // Страница редактирования Заказа - Post - КОРРЕКТИРОВАТЬ
    @Transactional(rollbackFor = Exception.class)
    String ReviewsEditPost2(
            @PathVariable UUID orderDetailId,
            @ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailsDTO,
            RedirectAttributes rm,
            Model model,
            Authentication authentication
    ) throws Exception {
        mutationLockService.lock(orderDetailId);
        log.info("1. Начинаем обновлять данные Отзыва2. - {}", authentication != null ? authentication.getName() : "Гость");
        OrderDetailsDTO boundDetails = bindSharedReviewForm(orderDetailId, orderDetailsDTO);
        OrderDetails liveDetails = liveSharedReview(orderDetailId);
        requirePublicDecisionAllowed(liveDetails.getOrder(), authentication);
        reviewService.updateOrderDetailAndReviews(boundDetails);
        log.info("Начинаем обновлять статус заказа");
        if (!orderService.changeStatusForOrder(boundDetails.getOrder().getId(), "Коррекция")) {
            markCurrentTransactionRollbackOnly();
            rm.addFlashAttribute("errorMessage", "Не удалось отправить заказ на коррекцию");
            return "redirect:/review/editReviews/{orderDetailId}";
        }
        log.info("Обновили статус заказа");
        log.info("5. Обновление Отзыва прошло успешно2");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    } // Страница редактирования Заказа - Post - КОРРЕКТИРОВАТЬ


//    ==========================================================================================================
private void checkTimeMethod(String text, long startTime){
    long endTime = System.nanoTime();
    double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
    log.info("{}: {} сек", text, String.format("%.4f", timeElapsed));
}

    private String getRole(Principal principal) {
        if (principal == null) return "anonymous";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    }// Берем роль пользователя

    private boolean hasInvalidReviewText(OrderDetailsDTO orderDetailsDTO) {
        if (orderDetailsDTO.getReviews() == null || orderDetailsDTO.getReviews().isEmpty()) {
            return true;
        }

        return orderDetailsDTO.getReviews().stream()
                .anyMatch(review -> review == null || isBlankOrPlaceholder(review.getText()));
    }

    /**
     * The UUID in the public URL is the legacy capability. Form identifiers are
     * untrusted and may only select reviews already belonging to that UUID.
     * Validate the complete form before the first write, then replace all
     * object identifiers with server-loaded canonical values.
     */
    private OrderDetailsDTO bindSharedReviewForm(UUID orderDetailId, OrderDetailsDTO submitted) {
        return bindSharedReviewForm(orderDetailId, submitted, true);
    }

    private OrderDetailsDTO bindSharedReviewAction(UUID orderDetailId, OrderDetailsDTO submitted) {
        return bindSharedReviewForm(orderDetailId, submitted, false);
    }

    private OrderDetailsDTO bindSharedReviewForm(
            UUID orderDetailId,
            OrderDetailsDTO submitted,
            boolean reviewsRequired
    ) {
        if (orderDetailId == null || submitted == null) {
            throw invalidSharedReviewForm();
        }

        OrderDetailsDTO canonical = orderDetailsService.getOrderDetailDTOById(orderDetailId);
        if (canonical == null || canonical.getOrder() == null || canonical.getOrder().getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
        }
        if (submitted.getId() != null && !Objects.equals(orderDetailId, submitted.getId())) {
            throw invalidSharedReviewForm();
        }
        if (submitted.getOrder() != null
                && submitted.getOrder().getId() != null
                && !Objects.equals(canonical.getOrder().getId(), submitted.getOrder().getId())) {
            throw invalidSharedReviewForm();
        }
        if (submitted.getOrder() != null
                && submitted.getOrder().getOrderDetailsId() != null
                && !Objects.equals(orderDetailId, submitted.getOrder().getOrderDetailsId())) {
            throw invalidSharedReviewForm();
        }
        if (reviewsRequired && submitted.getReviews() == null) {
            throw invalidSharedReviewForm();
        }

        Map<Long, ReviewDTO> canonicalReviewsById = new HashMap<>();
        if (canonical.getReviews() != null) {
            for (ReviewDTO canonicalReview : canonical.getReviews()) {
                if (canonicalReview != null && canonicalReview.getId() != null) {
                    canonicalReviewsById.put(canonicalReview.getId(), canonicalReview);
                }
            }
        }

        List<ReviewDTO> submittedReviews = submitted.getReviews() == null
                ? List.of()
                : submitted.getReviews();
        requirePublicFormMaxLength(
                submitted.getComment(),
                "Комментарий к проверке слишком длинный"
        );
        if (submittedReviews.size() > MAX_PUBLIC_REVIEW_ITEMS
                || submittedReviews.size() > canonicalReviewsById.size()) {
            throw invalidSharedReviewForm();
        }
        Set<Long> submittedReviewIds = new HashSet<>();
        List<ReviewDTO> boundReviews = new ArrayList<>(submittedReviews.size());
        for (ReviewDTO review : submittedReviews) {
            if (review == null
                    || review.getId() == null
                    || !canonicalReviewsById.containsKey(review.getId())
                    || !submittedReviewIds.add(review.getId())) {
                throw invalidSharedReviewForm();
            }
            if (review.getOrderDetailsId() != null
                    && !Objects.equals(orderDetailId, review.getOrderDetailsId())) {
                throw invalidSharedReviewForm();
            }
            if (review.getOrderDetails() != null
                    && review.getOrderDetails().getId() != null
                    && !Objects.equals(orderDetailId, review.getOrderDetails().getId())) {
                throw invalidSharedReviewForm();
            }
            if (review.getOrderDetails() != null
                    && review.getOrderDetails().getOrder() != null
                    && review.getOrderDetails().getOrder().getId() != null
                    && !Objects.equals(canonical.getOrder().getId(), review.getOrderDetails().getOrder().getId())) {
                throw invalidSharedReviewForm();
            }
            requirePublicFormMaxLength(review.getText(), "Текст отзыва слишком длинный");
            requirePublicFormMaxLength(review.getAnswer(), "Замечание к отзыву слишком длинное");

            ReviewDTO canonicalReview = canonicalReviewsById.get(review.getId());
            boundReviews.add(ReviewDTO.builder()
                    .id(review.getId())
                    .text(review.getText())
                    .answer(review.getAnswer())
                    .publishedDate(canonicalReview.getPublishedDate())
                    .publish(canonicalReview.isPublish())
                    .url(canonicalReview.getUrl())
                    .orderDetailsId(orderDetailId)
                    .build());
        }

        return OrderDetailsDTO.builder()
                .id(orderDetailId)
                .order(canonical.getOrder())
                .reviews(boundReviews)
                .comment(submitted.getComment())
                .build();
    }

    private ResponseStatusException invalidSharedReviewForm() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Данные формы не относятся к этой проверке отзывов"
        );
    }

    private void requirePublicFormMaxLength(String value, String message) {
        if (value != null
                && value.codePointCount(0, value.length()) > MAX_PUBLIC_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private OrderDetails liveSharedReview(UUID orderDetailId) {
        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailId);
        if (orderDetails == null || orderDetails.getOrder() == null || orderDetails.getOrder().getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
        }
        return orderDetails;
    }

    private void requirePublicMutationAllowed(Order order, Authentication authentication) {
        if (isAuthenticatedUser(authentication)
                && managerAccessService.canAccessOrder(order.getId(), authentication)) {
            return;
        }
        publicMutationPolicy.requireClientMutationAllowed(order);
    }

    private void requirePublicDecisionAllowed(Order order, Authentication authentication) {
        boolean objectAccess = isAuthenticatedUser(authentication)
                && managerAccessService.canAccessOrder(order.getId(), authentication);
        boolean canManage = objectAccess && authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(Set.of("ROLE_MANAGER", "ROLE_ADMIN", "ROLE_OWNER")::contains);
        boolean assignedWorkerOnly = objectAccess
                && !canManage
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_WORKER".equals(authority.getAuthority()));

        if (assignedWorkerOnly) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Недостаточно прав для разрешения публикации или отправки на коррекцию"
            );
        }
        if (canManage) {
            return;
        }
        publicMutationPolicy.requireClientMutationAllowed(order);
    }

    private String legacyApprovalAuditDetails(Authentication authentication) {
        String identity = isAuthenticatedUser(authentication)
                ? "authenticated"
                : "anonymous_public_link";
        return "identity=" + identity + ";channel=legacy_mvc;";
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_ANONYMOUS".equals(authority.getAuthority()));
    }

    private void requirePayOkRole(Authentication authentication) {
        boolean allowed = isAuthenticatedUser(authentication)
                && authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .anyMatch(Set.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER")::contains);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для отметки оплаты");
        }
    }

    private void markCurrentTransactionRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

}
