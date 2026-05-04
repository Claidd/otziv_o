package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/worker")
public class ApiWorkerBoardController {

    private static final String SECTION_NEW = "new";
    private static final String SECTION_CORRECT = "correct";
    private static final String SECTION_NAGUL = "nagul";
    private static final String SECTION_PUBLISH = "publish";
    private static final String SECTION_BAD = "bad";
    private static final String SECTION_ALL = "all";
    private static final String ORDER_STATUS_UNPAID = "Не оплачено";
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderService orderService;
    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final PromoTextService promoTextService;
    private final BotService botService;
    private final CompanyService companyService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final PerformanceMetrics performanceMetrics;
    private final BadReviewTaskService badReviewTaskService;

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public WorkerBoardResponse getBoard(
            @RequestParam(defaultValue = SECTION_NEW) String section,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("worker.board", () -> {
            String normalizedSection = normalizeSection(section);
            String message = "";
            boolean warning = false;

            boolean workerMustFinishNagul = hasRole(authentication, "WORKER")
                    && (SECTION_PUBLISH.equals(normalizedSection) || SECTION_ALL.equals(normalizedSection))
                    && reviewService.hasActiveNagulReviews(principal);

            if (workerMustFinishNagul) {
                String requestedSection = normalizedSection;
                normalizedSection = SECTION_NAGUL;
                message = SECTION_ALL.equals(requestedSection)
                        ? "Есть не выгулянные аккаунты. Раздел \"Все\" доступен после выгула всех отзывов"
                        : "Есть не выгулянные аккаунты. Публикация запрещена";
                warning = true;
            }

            int safePageNumber = Math.max(pageNumber, 0);
            int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
            String normalizedSortDirection = normalizeSortDirection(sortDirection);
            String trimmedKeyword = keyword == null ? "" : keyword.trim();

            Page<OrderDTOList> orders = isOrderSection(normalizedSection)
                    ? loadOrders(principal, authentication, normalizedSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection)
                    : emptyPage(safePageNumber, safePageSize);

            PageResponse<WorkerReviewResponse> reviews = isReviewSection(normalizedSection)
                    ? loadReviewResponses(principal, authentication, normalizedSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection)
                    : emptyReviewResponsePage(safePageNumber, safePageSize);

            return new WorkerBoardResponse(
                    normalizedSection,
                    title(normalizedSection),
                    toPageResponse(orders),
                    reviews,
                    List.of(),
                    buildMetrics(principal, authentication),
                    promoTextService.getAllPromoTexts(),
                    buildPermissions(authentication),
                    message,
                    warning
            );
        });
    }

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request
    ) throws Exception {
        String status = requireStatus(request);
        Order order = orderService.getOrder(orderId);

        if ("Опубликовано".equals(status) || "Оплачено".equals(status)) {
            requireCompleteCounter(order, status);
        }

        if (!orderService.changeStatusForOrder(orderId, status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус заказа не изменен");
        }

        if ("Публикация".equals(status)) {
            updateReviewPublishDates(order);
        }
    }

    @PutMapping("/orders/{orderId}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateOrderNote(
            @PathVariable Long orderId,
            @RequestBody OrderNoteUpdateRequest request
    ) {
        if (request == null || request.orderComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка заказа не указана");
        }

        Order order = orderService.getOrder(orderId);
        order.setZametka(request.orderComments());
        orderService.save(order);
    }

    @PutMapping("/orders/{orderId}/company-note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateOrderCompanyNote(
            @PathVariable Long orderId,
            @RequestBody CompanyNoteUpdateRequest request
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
    }

    @PostMapping("/reviews/{reviewId}/change-bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void changeReviewBot(@PathVariable Long reviewId) {
        reviewService.changeBot(reviewId);
    }

    @PostMapping("/reviews/{reviewId}/bots/{botId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void deactivateReviewBot(
            @PathVariable Long reviewId,
            @PathVariable Long botId
    ) {
        reviewService.deActivateAndChangeBot(reviewId, botId);
    }

    @PostMapping("/reviews/{reviewId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void publishReview(@PathVariable Long reviewId) {
        try {
            if (!orderService.changeStatusAndOrderCounter(reviewId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не отмечен опубликованным");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не отмечен опубликованным", exception);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void completeBadReviewTask(@PathVariable Long taskId) {
        try {
            badReviewTaskService.completeTask(taskId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Плохая задача не выполнена", exception);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/change-bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void changeBadReviewTaskBot(@PathVariable Long taskId) {
        try {
            badReviewTaskService.changeTaskBot(taskId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Аккаунт плохой задачи не заменен", exception);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/bots/{botId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void deactivateBadReviewTaskBot(
            @PathVariable Long taskId,
            @PathVariable Long botId
    ) {
        try {
            badReviewTaskService.deactivateAndChangeTaskBot(taskId, botId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Аккаунт плохой задачи не заблокирован", exception);
        }
    }

    @PostMapping("/reviews/{reviewId}/nagul")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public WorkerActionResponse nagulReview(
            @PathVariable Long reviewId,
            Principal principal
    ) {
        try {
            reviewService.performNagulWithExceptions(reviewId, principal.getName());
            return new WorkerActionResponse(true, "Отзыв успешно выгулен");
        } catch (NagulTooFastException | BotTemplateNameException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Произошла ошибка при выполнении выгула", exception);
        }
    }

    @PutMapping("/reviews/{reviewId}/text")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewText(
            @PathVariable Long reviewId,
            @RequestBody ReviewTextUpdateRequest request
    ) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewText(orderId, reviewId, request.text())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
    }

    @PutMapping("/reviews/{reviewId}/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewAnswer(
            @PathVariable Long reviewId,
            @RequestBody ReviewAnswerUpdateRequest request
    ) {
        if (request == null || request.answer() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ на отзыв не указан");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewAnswer(orderId, reviewId, request.answer())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
    }

    @PutMapping("/reviews/{reviewId}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewNote(
            @PathVariable Long reviewId,
            @RequestBody ReviewNoteUpdateRequest request
    ) {
        if (request == null || request.comment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка отзыва не указана");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewNote(orderId, reviewId, request.comment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
    }

    @DeleteMapping("/bots/{botId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteBot(@PathVariable Long botId) {
        botService.deleteBot(botId);
    }

    private Page<OrderDTOList> loadOrders(
            Principal principal,
            Authentication authentication,
            String section,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        String status = SECTION_CORRECT.equals(section) ? "Коррекция" : SECTION_NEW.equals(section) ? "Новый" : "Все";

        if (hasRole(authentication, "ADMIN")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize, sortDirection);
        }

        if (hasRole(authentication, "OWNER")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        if (hasRole(authentication, "MANAGER")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? orderService.getAllOrderDTOAndKeywordByWorkerAll(principal, keyword, pageNumber, pageSize)
                : orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword, status, pageNumber, pageSize);
    }

    private PageResponse<WorkerReviewResponse> loadReviewResponses(
            Principal principal,
            Authentication authentication,
            String section,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        if (SECTION_BAD.equals(section)) {
            Page<BadReviewTask> tasks = loadBadReviewTasks(
                    principal,
                    authentication,
                    keyword,
                    pageNumber,
                    pageSize,
                    sortDirection
            );
            return toBadTaskPageResponse(tasks);
        }

        return toReviewPageResponse(loadReviewPage(principal, authentication, section, pageNumber, pageSize, sortDirection, keyword));
    }

    private Page<BadReviewTask> loadBadReviewTasks(
            Principal principal,
            Authentication authentication,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        PageRequest pageable = PageRequest.of(
                pageNumber,
                pageSize,
                "asc".equals(sortDirection)
                        ? Sort.by("scheduledDate").ascending()
                        : Sort.by("scheduledDate").descending()
        );
        LocalDate date = LocalDate.now();

        if (hasRole(authentication, "ADMIN")) {
            return badReviewTaskService.getDueTasksToAdmin(date, keyword, pageable);
        }
        if (hasRole(authentication, "OWNER")) {
            return badReviewTaskService.getDueTasksToOwner(resolveOwnerManagers(principal), date, keyword, pageable);
        }
        if (hasRole(authentication, "MANAGER")) {
            return badReviewTaskService.getDueTasksToManager(resolveManager(principal), date, keyword, pageable);
        }
        return badReviewTaskService.getDueTasksToWorker(resolveWorker(principal), date, keyword, pageable);
    }

    private Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            String section,
            int pageNumber,
            int pageSize
    ) {
        return loadReviewPage(principal, authentication, section, pageNumber, pageSize, "desc");
    }

    private Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            String section,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        return loadReviewPage(principal, authentication, section, pageNumber, pageSize, sortDirection, "");
    }

    private Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            String section,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword
    ) {
        LocalDate date = SECTION_NAGUL.equals(section) ? LocalDate.now().plusDays(60) : LocalDate.now();

        if (SECTION_BAD.equals(section)) {
            if (hasRole(authentication, "ADMIN")) {
                return reviewService.getAllReviewDTOByOrderStatusToAdmin(ORDER_STATUS_UNPAID, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "OWNER")) {
                return reviewService.getAllReviewDTOByOwnerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "MANAGER")) {
                return reviewService.getAllReviewDTOByManagerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
        }

        if (SECTION_NAGUL.equals(section)) {
            if (hasRole(authentication, "ADMIN")) {
                return reviewService.getAllReviewDTOAndDateToAdminToVigul(date, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "OWNER")) {
                return reviewService.getAllReviewDTOByOwnerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "MANAGER")) {
                return reviewService.getAllReviewDTOByManagerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }

        if (hasRole(authentication, "ADMIN")) {
            return reviewService.getAllReviewDTOAndDateToAdmin(date, pageNumber, pageSize, sortDirection, keyword);
        }
        if (hasRole(authentication, "OWNER")) {
            return reviewService.getAllReviewDTOByOwnerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }
        if (hasRole(authentication, "MANAGER")) {
            return reviewService.getAllReviewDTOByManagerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }
        return reviewService.getAllReviewDTOByWorkerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
    }

    private List<BotResponse> loadBots(Principal principal, Authentication authentication) {
        List<BotDTO> bots;
        if (hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER")) {
            bots = botService.getAllBots();
        } else if (hasRole(authentication, "WORKER")) {
            bots = botService.getAllBotsByWorkerActiveIsTrue(principal);
        } else {
            bots = List.of();
        }

        return bots.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(bot -> safe(bot.getFio()), String.CASE_INSENSITIVE_ORDER))
                .map(this::toBotResponse)
                .toList();
    }

    private List<WorkerMetricResponse> buildMetrics(Principal principal, Authentication authentication) {
        List<WorkerMetricResponse> metrics = new ArrayList<>();
        Map<String, Integer> orderCounts = countOrderMetrics(principal, authentication);
        Map<String, Integer> reviewCounts = reviewService.countBoardReviewMetrics(
                LocalDate.now(),
                LocalDate.now().plusDays(60),
                ORDER_STATUS_UNPAID,
                principal,
                primaryBoardRole(authentication)
        );
        int badTaskCount = countBadReviewTasks(principal, authentication);

        metrics.add(orderMetric(orderCounts, "Новые", SECTION_NEW, "fiber_new", "yellow"));
        metrics.add(orderMetric(orderCounts, "Коррекция", SECTION_CORRECT, "build_circle", "pink"));
        metrics.add(reviewMetric(reviewCounts, "Выгул", SECTION_NAGUL, "directions_walk", "teal"));
        metrics.add(reviewMetric(reviewCounts, "Публикация", SECTION_PUBLISH, "published_with_changes", "green"));
        metrics.add(new WorkerMetricResponse("Плохие", badTaskCount, "money_off", "gray", SECTION_BAD));
        metrics.add(orderMetric(orderCounts, "Все", SECTION_ALL, "dashboard", "blue"));
        return metrics;
    }

    private int countBadReviewTasks(Principal principal, Authentication authentication) {
        LocalDate date = LocalDate.now();
        if (hasRole(authentication, "ADMIN")) {
            return badReviewTaskService.countDueTasksToAdmin(date);
        }
        if (hasRole(authentication, "OWNER")) {
            return badReviewTaskService.countDueTasksToOwner(resolveOwnerManagers(principal), date);
        }
        if (hasRole(authentication, "MANAGER")) {
            return badReviewTaskService.countDueTasksToManager(resolveManager(principal), date);
        }
        return badReviewTaskService.countDueTasksToWorker(resolveWorker(principal), date);
    }

    private WorkerMetricResponse orderMetric(
            Map<String, Integer> counts,
            String label,
            String section,
            String icon,
            String tone
    ) {
        String status = SECTION_NEW.equals(section) ? "Новый" : SECTION_CORRECT.equals(section) ? "Коррекция" : "Все";
        return new WorkerMetricResponse(label, countStatus(counts, status), icon, tone, section);
    }

    private Map<String, Integer> countOrderMetrics(Principal principal, Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return orderService.countOrdersByStatus();
        }
        if (hasRole(authentication, "OWNER")) {
            return orderService.countOrdersByStatusToOwner(resolveOwnerManagers(principal));
        }
        if (hasRole(authentication, "MANAGER")) {
            return orderService.countOrdersByStatusToManager(resolveManager(principal));
        }
        return orderService.countOrdersByStatusToWorker(resolveWorker(principal));
    }

    private int countStatus(Map<String, Integer> counts, String status) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        if ("Все".equals(status)) {
            long total = counts.values().stream()
                    .mapToLong(Integer::longValue)
                    .sum();
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
        return counts.getOrDefault(status, 0);
    }

    private WorkerMetricResponse reviewMetric(
            Map<String, Integer> counts,
            String label,
            String section,
            String icon,
            String tone
    ) {
        return new WorkerMetricResponse(
                label,
                counts == null ? 0 : counts.getOrDefault(section, 0),
                icon,
                tone,
                section
        );
    }

    private WorkerPermissionsResponse buildPermissions(Authentication authentication) {
        boolean admin = hasRole(authentication, "ADMIN");
        boolean owner = hasRole(authentication, "OWNER");
        boolean worker = hasRole(authentication, "WORKER");
        return new WorkerPermissionsResponse(
                admin || owner,
                admin || owner,
                admin || owner,
                admin || owner || worker,
                admin || owner || hasRole(authentication, "MANAGER"),
                admin || owner || hasRole(authentication, "MANAGER") || worker,
                admin || owner || hasRole(authentication, "MANAGER")
        );
    }

    private BotResponse toBotResponse(BotDTO bot) {
        String workerFio = bot.getWorker() != null && bot.getWorker().getUser() != null
                ? safe(bot.getWorker().getUser().getFio())
                : "";
        String city = bot.getBotCity() != null ? safe(bot.getBotCity().getTitle()) : "";
        return new BotResponse(
                bot.getId(),
                safe(bot.getLogin()),
                safe(bot.getPassword()),
                safe(bot.getFio()),
                city,
                bot.getCounter(),
                workerFio,
                safe(bot.getStatus()),
                bot.isActive()
        );
    }

    private WorkerReviewResponse toReviewResponse(ReviewDTOOne review) {
        return new WorkerReviewResponse(
                review.getId(),
                review.getCompanyId(),
                review.getOrderDetailsId(),
                review.getOrderId(),
                safe(review.getOrderStatus()),
                safe(review.getText()),
                safe(review.getAnswer()),
                safe(review.getCategory()),
                safe(review.getSubCategory()),
                review.getBotId(),
                safe(review.getBotFio()),
                safe(review.getBotLogin()),
                safe(review.getBotPassword()),
                review.getBotCounter(),
                safe(review.getCompanyTitle()),
                safe(review.getCommentCompany()),
                safe(review.getOrderComments()),
                safe(review.getFilialCity()),
                safe(review.getFilialTitle()),
                safe(review.getFilialUrl()),
                review.getProductId(),
                safe(review.getProductTitle()),
                review.isProductPhoto(),
                safe(review.getWorkerFio()),
                dateValue(review.getCreated()),
                dateValue(review.getChanged()),
                dateValue(review.getPublishedDate()),
                review.isPublish(),
                review.isVigul(),
                safe(review.getComment()),
                review.getPrice(),
                safe(review.getUrl()),
                !safe(review.getUrlPhoto()).isBlank() ? safe(review.getUrlPhoto()) : safe(review.getUrl()),
                false,
                null,
                review.getId(),
                null,
                null,
                "",
                null,
                "",
                "",
                null
        );
    }

    private WorkerReviewResponse toBadTaskReviewResponse(BadReviewTask task) {
        Review sourceReview = task.getSourceReview();
        ReviewDTOOne review = reviewService.toReviewDTOOne(sourceReview);
        Bot bot = task.getBot();
        Long botId = bot != null ? bot.getId() : null;
        String botFio = bot != null ? safe(bot.getFio()) : safe(review.getBotFio());
        String botLogin = bot != null ? safe(bot.getLogin()) : safe(review.getBotLogin());
        String botPassword = bot != null ? safe(bot.getPassword()) : safe(review.getBotPassword());
        int botCounter = bot != null ? bot.getCounter() : review.getBotCounter();
        String workerFio = task.getWorker() != null && task.getWorker().getUser() != null
                ? safe(task.getWorker().getUser().getFio())
                : safe(review.getWorkerFio());

        return new WorkerReviewResponse(
                review.getId(),
                review.getCompanyId(),
                review.getOrderDetailsId(),
                review.getOrderId(),
                safe(review.getOrderStatus()),
                safe(review.getText()),
                safe(review.getAnswer()),
                safe(review.getCategory()),
                safe(review.getSubCategory()),
                botId,
                botFio,
                botLogin,
                botPassword,
                botCounter,
                safe(review.getCompanyTitle()),
                safe(review.getCommentCompany()),
                safe(review.getOrderComments()),
                safe(review.getFilialCity()),
                safe(review.getFilialTitle()),
                safe(review.getFilialUrl()),
                review.getProductId(),
                safe(review.getProductTitle()),
                review.isProductPhoto(),
                workerFio,
                dateValue(review.getCreated()),
                dateValue(review.getChanged()),
                dateValue(task.getScheduledDate()),
                review.isPublish(),
                review.isVigul(),
                safe(review.getComment()),
                task.getPrice(),
                safe(review.getUrl()),
                !safe(review.getUrlPhoto()).isBlank() ? safe(review.getUrlPhoto()) : safe(review.getUrl()),
                true,
                task.getId(),
                review.getId(),
                task.getOriginalRating(),
                task.getTargetRating(),
                task.getStatus() == null ? "" : task.getStatus().name(),
                task.getPrice(),
                dateValue(task.getScheduledDate()),
                dateValue(task.getCompletedDate()),
                safe(task.getComment())
        );
    }

    private void requireCompleteCounter(Order order, String status) {
        if (order.getAmount() > order.getCounter()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя перевести заказ в статус \"" + status + "\": опубликовано "
                            + order.getCounter() + " из " + order.getAmount() + " отзывов"
            );
        }
    }

    private void updateReviewPublishDates(Order order) {
        if (order.getDetails() == null || order.getDetails().isEmpty()) {
            return;
        }

        reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailsService.getOrderDetailDTOById(order.getDetails().getFirst().getId()));
    }

    private Manager resolveManager(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return managerService.getManagerByUserId(user.getId());
    }

    private Worker resolveWorker(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Специалист не найден");
        }
        return worker;
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"))
                .getManagers();
    }

    private boolean isOrderSection(String section) {
        return SECTION_NEW.equals(section) || SECTION_CORRECT.equals(section) || SECTION_ALL.equals(section);
    }

    private boolean isReviewSection(String section) {
        return SECTION_NAGUL.equals(section) || SECTION_PUBLISH.equals(section) || SECTION_BAD.equals(section);
    }

    private boolean matchesReviewKeyword(ReviewDTOOne review, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalizedKeyword.isBlank()) {
            return true;
        }

        return containsReviewKeyword(normalizedKeyword,
                String.valueOf(review.getId()),
                String.valueOf(review.getOrderId()),
                String.valueOf(review.getCompanyId()),
                review.getCompanyTitle(),
                review.getFilialCity(),
                review.getFilialTitle(),
                review.getText(),
                review.getAnswer(),
                review.getBotFio(),
                review.getWorkerFio(),
                review.getProductTitle(),
                review.getCategory(),
                review.getSubCategory(),
                review.getCommentCompany(),
                review.getOrderComments(),
                review.getOrderStatus()
        );
    }

    private boolean containsReviewKeyword(String keyword, String... values) {
        for (String value : values) {
            if (safe(value).toLowerCase(Locale.ROOT).contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private Comparator<ReviewDTOOne> reviewComparator(String sortDirection) {
        Comparator<ReviewDTOOne> comparator = Comparator
                .comparing(ReviewDTOOne::getPublishedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReviewDTOOne::getId, Comparator.nullsLast(Comparator.naturalOrder()));

        return "asc".equals(sortDirection) ? comparator : comparator.reversed();
    }

    private Sort reviewSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("publishedDate").ascending()
                : Sort.by("publishedDate").descending();
    }

    private String normalizeSection(String section) {
        String normalized = section == null ? SECTION_NEW : section.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case SECTION_CORRECT, SECTION_NAGUL, SECTION_PUBLISH, SECTION_BAD, SECTION_ALL -> normalized;
            default -> SECTION_NEW;
        };
    }

    private String normalizeSortDirection(String sortDirection) {
        return "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
    }

    private String title(String section) {
        return switch (section) {
            case SECTION_CORRECT -> "Коррекция";
            case SECTION_NAGUL -> "Выгул";
            case SECTION_PUBLISH -> "Публикация";
            case SECTION_BAD -> "Плохие";
            case SECTION_ALL -> "Все";
            default -> "Новые";
        };
    }

    private String requireStatus(StatusChangeRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус не указан");
        }
        return request.status().trim();
    }

    private Long requireReviewOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ отзыва не указан");
        }

        return orderId;
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private String primaryBoardRole(Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return "ADMIN";
        }
        if (hasRole(authentication, "OWNER")) {
            return "OWNER";
        }
        if (hasRole(authentication, "MANAGER")) {
            return "MANAGER";
        }
        return "WORKER";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String dateValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private <T> Page<T> emptyPage(int pageNumber, int pageSize) {
        return new PageImpl<>(List.of(), PageRequest.of(pageNumber, pageSize), 0);
    }

    private Page<ReviewDTOOne> pageReviews(
            List<ReviewDTOOne> reviews,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        int totalElements = reviews.size();
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        int correctedPageNumber = Math.max(0, pageNumber);

        if (totalPages > 0 && correctedPageNumber >= totalPages) {
            correctedPageNumber = totalPages - 1;
        }

        int start = correctedPageNumber * pageSize;
        int end = Math.min(start + pageSize, totalElements);
        List<ReviewDTOOne> content = start >= totalElements ? List.of() : reviews.subList(start, end);
        Sort sort = "asc".equals(sortDirection)
                ? Sort.by("publishedDate").ascending()
                : Sort.by("publishedDate").descending();

        return new PageImpl<>(content, PageRequest.of(correctedPageNumber, pageSize, sort), totalElements);
    }

    private <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PageResponse<WorkerReviewResponse> toReviewPageResponse(Page<ReviewDTOOne> page) {
        return new PageResponse<>(
                page.getContent().stream().map(this::toReviewResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PageResponse<WorkerReviewResponse> toBadTaskPageResponse(Page<BadReviewTask> page) {
        return new PageResponse<>(
                page.getContent().stream().map(this::toBadTaskReviewResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PageResponse<WorkerReviewResponse> emptyReviewResponsePage(int pageNumber, int pageSize) {
        return new PageResponse<>(List.of(), pageNumber, pageSize, 0, 0, true, true);
    }

    public record WorkerBoardResponse(
            String section,
            String title,
            PageResponse<OrderDTOList> orders,
            PageResponse<WorkerReviewResponse> reviews,
            List<BotResponse> bots,
            List<WorkerMetricResponse> metrics,
            List<String> promoTexts,
            WorkerPermissionsResponse permissions,
            String message,
            boolean warning
    ) {
    }

    public record PageResponse<T>(
            List<T> content,
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
    }

    public record WorkerMetricResponse(
            String label,
            int value,
            String icon,
            String tone,
            String section
    ) {
    }

    public record WorkerPermissionsResponse(
            boolean canManageOrderStatuses,
            boolean canSeePhoneAndPayment,
            boolean canManageBots,
            boolean canAddBot,
            boolean canSeeMoney,
            boolean canWorkReviews,
            boolean canEditNotes
    ) {
    }

    public record BotResponse(
            Long id,
            String login,
            String password,
            String fio,
            String city,
            int counter,
            String workerFio,
            String status,
            boolean active
    ) {
    }

    public record WorkerReviewResponse(
            Long id,
            Long companyId,
            UUID orderDetailsId,
            Long orderId,
            String orderStatus,
            String text,
            String answer,
            String category,
            String subCategory,
            Long botId,
            String botFio,
            String botLogin,
            String botPassword,
            int botCounter,
            String companyTitle,
            String commentCompany,
            String orderComments,
            String filialCity,
            String filialTitle,
            String filialUrl,
            Long productId,
            String productTitle,
            boolean productPhoto,
            String workerFio,
            String created,
            String changed,
            String publishedDate,
            boolean publish,
            boolean vigul,
            String comment,
            BigDecimal price,
            String url,
            String urlPhoto,
            boolean badTask,
            Long badTaskId,
            Long sourceReviewId,
            Integer originalRating,
            Integer targetRating,
            String badTaskStatus,
            BigDecimal badTaskPrice,
            String badTaskScheduledDate,
            String badTaskCompletedDate,
            String badTaskComment
    ) {
    }

    public record StatusChangeRequest(String status) {
    }

    public record OrderNoteUpdateRequest(String orderComments) {
    }

    public record CompanyNoteUpdateRequest(String companyComments) {
    }

    public record ReviewTextUpdateRequest(Long orderId, String text) {
    }

    public record ReviewAnswerUpdateRequest(Long orderId, String answer) {
    }

    public record ReviewNoteUpdateRequest(Long orderId, String comment) {
    }

    public record WorkerActionResponse(boolean success, String message) {
    }
}
