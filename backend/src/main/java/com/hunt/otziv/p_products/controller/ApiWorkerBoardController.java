package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.dto.api.ManagerOverdueOrdersResponse;
import com.hunt.otziv.manager.dto.api.ManagerOverdueStatusResponse;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.worker_flow.WorkerFlowLockService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/worker")
public class ApiWorkerBoardController {

    private static final String SECTION_NEW = "new";
    private static final String SECTION_CORRECT = "correct";
    private static final String SECTION_NAGUL = "nagul";
    private static final String SECTION_RECOVERY = "recovery";
    private static final String SECTION_PUBLISH = "publish";
    private static final String SECTION_BAD = "bad";
    private static final String SECTION_ALL = "all";
    private static final String SECTION_CURRENT = "current";
    private static final String ORDER_STATUS_NEW = "Новый";
    private static final String ORDER_STATUS_CORRECT = "Коррекция";
    private static final String ORDER_STATUS_UNPAID = "Не оплачено";
    private static final String ORDER_STATUS_PAID = "Оплачено";
    private static final int WORKER_FLOW_BLOCKING_UNCHANGED_DAYS = 1;
    private static final Set<String> WORKER_FLOW_ORDER_STATUSES = Set.of(
            ORDER_STATUS_NEW,
            ORDER_STATUS_CORRECT
    );
    private static final String WORKER_FLOW_BLOCK_MESSAGE = "В разделах \"Новые\" или \"Коррекция\" есть заказы без изменений 1 день или больше. "
            + "Публикация и раздел \"Все\" откроются, когда в \"Новых\" и \"Коррекции\" не останется активных заказов. "
            + "Заказы, которые ждут клиента, переход не блокируют";
    private static final Set<String> CLIENT_WAITING_ORDER_STATUSES = Set.of(ORDER_STATUS_NEW, ORDER_STATUS_CORRECT);
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final Set<String> OVERDUE_IGNORED_STATUSES = Set.of(
            "Оплачено",
            "Архив",
            "Публикация"
    );
    private static final Set<String> REVIEW_CREDENTIAL_COPY_FIELDS = Set.of("login", "password");
    private static final String REVIEW_DTO_ERROR_COMPANY_TITLE = "ОШИБКА ПРИ ОБРАБОТКЕ";
    private static final String REVIEW_DTO_MISSING_ORDER_TITLE = "НЕТ ЗАКАЗА";
    private static final List<String> CURRENT_WORK_SECTIONS = List.of(
            SECTION_NEW,
            SECTION_CORRECT,
            SECTION_NAGUL,
            SECTION_RECOVERY,
            SECTION_PUBLISH,
            SECTION_BAD
    );
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderService orderService;
    private final OrderBoardQueryService orderBoardQueryService;
    private final OrderRepository orderRepository;
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
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final UserMetricSnapshotService metricSnapshotService;
    private final AppSettingService appSettingService;
    private final WorkerFlowLockService workerFlowLockService;

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public WorkerBoardResponse getBoard(
            @RequestParam(defaultValue = SECTION_NEW) String section,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) Long workerId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("worker.board", () -> {
            String normalizedSection = normalizeSection(section);
            String message = "";
            boolean warning = false;
            List<WorkerMetricResponse> metrics = null;
            WorkerSelection workerSelection = resolveWorkerSelection(principal, authentication, workerId);
            Worker selectedWorker = workerSelection.selectedWorker();

            if (isCurrentSectionRequest(section)) {
                metrics = buildMetrics(principal, authentication, selectedWorker);
                normalizedSection = currentWorkSection(metrics);
            } else {
                WorkerFlowRedirect redirect = workerFlowRedirect(principal, authentication, normalizedSection);
                if (redirect != null) {
                    normalizedSection = redirect.section();
                    message = redirect.message();
                    warning = true;
                }
            }

            int safePageNumber = Math.max(pageNumber, 0);
            int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
            String normalizedSortDirection = normalizeSortDirection(sortDirection);
            String trimmedKeyword = keyword == null ? "" : keyword.trim();

            Page<OrderDTOList> orders = isOrderSection(normalizedSection)
                    ? loadOrders(principal, authentication, selectedWorker, normalizedSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection)
                    : emptyPage(safePageNumber, safePageSize);

            PageResponse<WorkerReviewResponse> reviews = isReviewSection(normalizedSection)
                    ? loadReviewResponses(principal, authentication, selectedWorker, normalizedSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection)
                    : emptyReviewResponsePage(safePageNumber, safePageSize);

            return new WorkerBoardResponse(
                    normalizedSection,
                    title(normalizedSection),
                    toPageResponse(orders),
                    reviews,
                    List.of(),
                    metrics != null ? metrics : buildMetrics(principal, authentication, selectedWorker),
                    promoTextService.getAllPromoTexts(),
                    buildPermissions(authentication),
                    workerSelection.options(),
                    workerId(selectedWorker),
                    workerSelection.available(),
                    message,
                    warning
            );
        });
    }

    @GetMapping("/overdue-orders")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ManagerOverdueOrdersResponse getOverdueOrders(
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("worker.overdue-orders", () -> {
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
            List<Object[]> summaryRows;

            if (hasRole(authentication, "ADMIN")) {
                summaryRows = orderRepository.summarizeOverdueOrders(cutoff, OVERDUE_IGNORED_STATUSES);
            } else if (hasRole(authentication, "OWNER")) {
                Set<Manager> managers = resolveOwnerManagers(principal);
                summaryRows = managers.isEmpty()
                        ? List.of()
                        : orderRepository.summarizeOverdueOrdersByManagers(managers, cutoff, OVERDUE_IGNORED_STATUSES);
            } else if (hasRole(authentication, "MANAGER")) {
                summaryRows = orderRepository.summarizeOverdueOrdersByManager(
                        resolveManager(principal),
                        cutoff,
                        OVERDUE_IGNORED_STATUSES
                );
            } else {
                summaryRows = orderRepository.summarizeOverdueOrdersByWorker(
                        resolveWorker(principal),
                        cutoff,
                        OVERDUE_IGNORED_STATUSES
                );
            }

            List<ManagerOverdueStatusResponse> statuses = toOverdueStatuses(summaryRows, today);
            long total = statuses.stream()
                    .mapToLong(ManagerOverdueStatusResponse::count)
                    .sum();

            return new ManagerOverdueOrdersResponse(
                    OVERDUE_NOTIFICATION_DAYS,
                    total,
                    statuses
            );
        });
    }

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request,
            HttpServletRequest servletRequest
    ) throws Exception {
        String status = requireStatus(request);
        servletRequest.setAttribute("status", status);
        Order order = orderService.getOrder(orderId);

        if ("Опубликовано".equals(status) || "Оплачено".equals(status)) {
            requireCompleteCounter(order, status);
        }

        if (!orderService.changeStatusForOrder(orderId, status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус заказа не изменен");
        }

        clearClientWaitingIfNeeded(orderId, status);

        if ("Публикация".equals(status)) {
            updateReviewPublishDates(order);
        }
    }

    @PostMapping("/orders/{orderId}/client-waiting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateOrderClientWaiting(
            @PathVariable Long orderId,
            @RequestBody ClientWaitingRequest request
    ) {
        if (request == null || request.waitingForClient() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Состояние ожидания клиента не указано");
        }

        Order order = orderService.getOrder(orderId);
        boolean waitingForClient = request.waitingForClient();
        if (waitingForClient && !isClientWaitingStatus(orderStatusTitle(order))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ожидание клиента доступно только для статусов \"" + ORDER_STATUS_NEW + "\" и \"" + ORDER_STATUS_CORRECT + "\""
            );
        }

        if (order.isWaitingForClient() != waitingForClient) {
            order.setWaitingForClientChangedAt(waitingForClient ? LocalDateTime.now() : null);
        }
        order.setWaitingForClient(waitingForClient);
        if (waitingForClient) {
            order.setClientTextExpected(true);
        }
        orderService.save(order);
    }

    @PutMapping("/orders/{orderId}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeReviewBot(@PathVariable Long reviewId) {
        Long oldBotId = botId(reviewService.getReviewById(reviewId));
        reviewService.changeBot(reviewId);
        Long newBotId = botId(reviewService.getReviewById(reviewId));
        return new BotChangeResponse(oldBotId, newBotId);
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

    @PostMapping("/reviews/{reviewId}/copy-click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void logReviewCredentialCopyClick(
            @PathVariable Long reviewId,
            @RequestBody ReviewCopyClickRequest request,
            Principal principal
    ) {
        String field = normalizeReviewCopyField(request);
        Review review = reviewService.getReviewById(reviewId);
        if (review == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }

        Order order = review.getOrderDetails() != null ? review.getOrderDetails().getOrder() : null;
        Company company = order != null ? order.getCompany() : null;
        Bot bot = review.getBot();

        log.info(
                "Выгул: специалист {} нажал кнопку \"{}\" для отзыва ID {}, заказа ID {}, компании \"{}\", бота ID {}",
                principalName(principal),
                copyFieldLabel(field),
                review.getId(),
                order != null ? order.getId() : null,
                company != null ? safe(company.getTitle()) : "",
                bot != null ? bot.getId() : null
        );
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

    @PutMapping("/bad-review-tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateBadReviewTask(
            @PathVariable Long taskId,
            @RequestBody BadTaskUpdateRequest request
    ) {
        if (request == null || request.taskText() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст плохой задачи не указан");
        }

        try {
            badReviewTaskService.updateTask(taskId, request.taskText(), request.scheduledDate());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Плохая задача не сохранена", exception);
        }
    }

    @PutMapping("/recovery-tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateRecoveryTask(
            @PathVariable Long taskId,
            @RequestBody RecoveryTaskUpdateRequest request
    ) {
        if (request == null || request.recoveryText() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст восстановления не указан");
        }

        try {
            reviewRecoveryTaskService.updateTask(taskId, request.recoveryText(), request.recoveryAnswer(), request.scheduledDate());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Задача восстановления не сохранена", exception);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void completeRecoveryTask(
            @PathVariable Long taskId,
            Authentication authentication
    ) {
        try {
            reviewRecoveryTaskService.completeTask(taskId, currentUser(authentication));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Задача восстановления не выполнена", exception);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeRecoveryTaskBot(@PathVariable Long taskId) {
        try {
            ReviewRecoveryTask task = reviewRecoveryTaskService.changeTaskBot(taskId);
            return new BotChangeResponse(null, botId(task));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Аккаунт восстановления не заменен", exception);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/bots/{botId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void deactivateRecoveryTaskBot(
            @PathVariable Long taskId,
            @PathVariable Long botId
    ) {
        try {
            reviewRecoveryTaskService.deactivateAndChangeTaskBot(taskId, botId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Аккаунт восстановления не заблокирован", exception);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeBadReviewTaskBot(@PathVariable Long taskId) {
        try {
            BadReviewTask task = badReviewTaskService.changeTaskBot(taskId);
            return new BotChangeResponse(null, botId(task));
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
            Worker selectedWorker,
            String section,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        String status = SECTION_CORRECT.equals(section) ? "Коррекция" : SECTION_NEW.equals(section) ? "Новый" : "Все";

        if (selectedWorker != null) {
            return "Все".equals(status)
                    ? orderBoardQueryService.getWorkerBoardOrderDTOAndKeywordByWorkerAll(selectedWorker, keyword, pageNumber, pageSize, sortDirection)
                    : orderBoardQueryService.getAllOrderDTOAndKeywordByWorker(selectedWorker, keyword, status, pageNumber, pageSize, sortDirection);
        }

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
                    ? orderBoardQueryService.getWorkerBoardOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? orderBoardQueryService.getWorkerBoardOrderDTOAndKeywordByWorkerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                : orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword, status, pageNumber, pageSize);
    }

    private PageResponse<WorkerReviewResponse> loadReviewResponses(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
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
                    selectedWorker,
                    keyword,
                    pageNumber,
                    pageSize,
                    sortDirection
            );
            return toBadTaskPageResponse(tasks);
        }

        if (SECTION_RECOVERY.equals(section)) {
            Page<ReviewRecoveryTask> tasks = loadRecoveryTasks(
                    principal,
                    authentication,
                    selectedWorker,
                    keyword,
                    pageNumber,
                    pageSize,
                    sortDirection
            );
            return toRecoveryTaskPageResponse(tasks);
        }

        return toReviewPageResponse(loadReviewPage(principal, authentication, selectedWorker, section, pageNumber, pageSize, sortDirection, keyword));
    }

    private Page<ReviewRecoveryTask> loadRecoveryTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        PageRequest pageable = PageRequest.of(pageNumber, pageSize, recoveryTaskSort(sortDirection));
        LocalDate date = LocalDate.now();

        if (selectedWorker != null) {
            return reviewRecoveryTaskService.getDueTasksToWorker(selectedWorker, date, keyword, pageable);
        }

        if (hasRole(authentication, "ADMIN")) {
            return reviewRecoveryTaskService.getDueTasksToAdmin(date, keyword, pageable);
        }
        if (hasRole(authentication, "OWNER")) {
            return reviewRecoveryTaskService.getDueTasksToOwner(resolveOwnerManagers(principal), date, keyword, pageable);
        }
        if (hasRole(authentication, "MANAGER")) {
            return reviewRecoveryTaskService.getDueTasksToManager(resolveManager(principal), date, keyword, pageable);
        }
        return reviewRecoveryTaskService.getDueTasksToWorker(resolveWorker(principal), date, keyword, pageable);
    }

    private Page<BadReviewTask> loadBadReviewTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        PageRequest pageable = PageRequest.of(pageNumber, pageSize, badReviewTaskSort(sortDirection));
        LocalDate date = LocalDate.now();

        if (selectedWorker != null) {
            return badReviewTaskService.getDueTasksToWorker(selectedWorker, date, keyword, pageable);
        }

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
        return loadReviewPage(principal, authentication, null, section, pageNumber, pageSize, sortDirection, "");
    }

    private Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String section,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword
    ) {
        LocalDate date = SECTION_NAGUL.equals(section) ? nagulLookaheadDate() : LocalDate.now();

        if (selectedWorker != null) {
            if (SECTION_BAD.equals(section)) {
                return reviewService.getAllReviewDTOByWorkerByOrderStatus(selectedWorker, ORDER_STATUS_UNPAID, pageNumber, pageSize, sortDirection, keyword);
            }
            if (SECTION_NAGUL.equals(section)) {
                return reviewService.getAllReviewDTOByWorkerByPublishToVigul(selectedWorker, date, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByPublish(selectedWorker, date, pageNumber, pageSize, sortDirection, keyword);
        }

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
        return buildMetrics(principal, authentication, null);
    }

    private List<WorkerMetricResponse> buildMetrics(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker
    ) {
        List<WorkerMetricResponse> metrics = new ArrayList<>();
        Map<String, Integer> orderCounts = countOrderMetrics(principal, authentication, selectedWorker);
        Map<String, Integer> reviewCounts = selectedWorker != null
                ? Map.of(
                        SECTION_PUBLISH, reviewService.countOrdersByWorkerAndStatusPublish(selectedWorker, LocalDate.now()),
                        SECTION_NAGUL, reviewService.countOrdersByWorkerAndStatusVigul(selectedWorker, nagulLookaheadDate())
                )
                : reviewService.countBoardReviewMetrics(
                        LocalDate.now(),
                        nagulLookaheadDate(),
                        ORDER_STATUS_UNPAID,
                        principal,
                        primaryBoardRole(authentication)
                );
        int badTaskCount = countBadReviewTasks(principal, authentication, selectedWorker);
        int recoveryTaskCount = countRecoveryTasks(principal, authentication, selectedWorker);

        metrics.add(orderMetric(orderCounts, "Новые", SECTION_NEW, "fiber_new", "yellow"));
        metrics.add(orderMetric(orderCounts, "Коррекция", SECTION_CORRECT, "build_circle", "pink"));
        metrics.add(reviewMetric(reviewCounts, "Выгул", SECTION_NAGUL, "directions_walk", "teal"));
        metrics.add(new WorkerMetricResponse("Восстановление", recoveryTaskCount, "restore", "yellow", SECTION_RECOVERY));
        metrics.add(reviewMetric(reviewCounts, "Публикация", SECTION_PUBLISH, "published_with_changes", "green"));
        metrics.add(new WorkerMetricResponse("Плохие", badTaskCount, "money_off", "gray", SECTION_BAD));
        metrics.add(orderMetric(orderCounts, "Все", SECTION_ALL, "dashboard", "blue"));
        syncWorkerFlowLockFromMetrics(principal, authentication, orderCounts);

        if (selectedWorker != null) {
            return metrics;
        }

        Map<String, Integer> deltas = metricSnapshotService.deltas(
                principal,
                UserMetricSnapshotService.PAGE_WORKER,
                metrics.stream()
                        .map(metric -> new UserMetricSnapshotService.MetricValue(
                                metric.section(),
                                metric.section(),
                                metric.value()
                        ))
                        .toList()
        );

        return metrics.stream()
                .map(metric -> metric.withDelta(deltas.getOrDefault(
                        UserMetricSnapshotService.key(metric.section(), metric.section()),
                        0
                )))
                .toList();
    }

    private void syncWorkerFlowLockFromMetrics(
            Principal principal,
            Authentication authentication,
            Map<String, Integer> orderCounts
    ) {
        if (!isWorkerFlowRestricted(authentication)) {
            return;
        }

        Worker worker = resolveWorker(principal);
        int flowOrders = countStatus(orderCounts, ORDER_STATUS_NEW) + countStatus(orderCounts, ORDER_STATUS_CORRECT);
        boolean hasFlowOrders = flowOrders > 0;

        workerFlowLockService.syncPublicationLock(
                workerFlowLockKey(worker, principal),
                workerId(worker),
                hasFlowOrders,
                hasFlowOrders && hasStaleWorkerFlowOrders(worker)
        );
    }

    private int countBadReviewTasks(Principal principal, Authentication authentication) {
        return countBadReviewTasks(principal, authentication, null);
    }

    private int countBadReviewTasks(Principal principal, Authentication authentication, Worker selectedWorker) {
        LocalDate date = LocalDate.now();
        if (selectedWorker != null) {
            return badReviewTaskService.countDueTasksToWorker(selectedWorker, date);
        }
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

    private int countRecoveryTasks(Principal principal, Authentication authentication, Worker selectedWorker) {
        LocalDate date = LocalDate.now();
        if (selectedWorker != null) {
            return reviewRecoveryTaskService.countDueTasksToWorker(selectedWorker, date);
        }
        if (hasRole(authentication, "ADMIN")) {
            return reviewRecoveryTaskService.countDueTasksToAdmin(date);
        }
        if (hasRole(authentication, "OWNER")) {
            return reviewRecoveryTaskService.countDueTasksToOwner(resolveOwnerManagers(principal), date);
        }
        if (hasRole(authentication, "MANAGER")) {
            return reviewRecoveryTaskService.countDueTasksToManager(resolveManager(principal), date);
        }
        return reviewRecoveryTaskService.countDueTasksToWorker(resolveWorker(principal), date);
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
        return countOrderMetrics(principal, authentication, null);
    }

    private Map<String, Integer> countOrderMetrics(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker
    ) {
        if (selectedWorker != null) {
            return withPaidStatusCount(
                    orderService.countActionableOrdersByStatusToWorker(selectedWorker),
                    orderService.countOrdersByWorkerAndStatus(selectedWorker, ORDER_STATUS_PAID)
            );
        }
        if (hasRole(authentication, "ADMIN")) {
            return orderService.countActionableOrdersByStatus();
        }
        if (hasRole(authentication, "OWNER")) {
            return orderService.countActionableOrdersByStatusToOwner(resolveOwnerManagers(principal));
        }
        if (hasRole(authentication, "MANAGER")) {
            Manager manager = resolveManager(principal);
            return withPaidStatusCount(
                    orderService.countActionableOrdersByStatusToManager(manager),
                    orderService.getAllOrderDTOByStatusToManager(manager, ORDER_STATUS_PAID)
            );
        }
        Worker worker = resolveWorker(principal);
        return withPaidStatusCount(
                orderService.countActionableOrdersByStatusToWorker(worker),
                orderService.countOrdersByWorkerAndStatus(worker, ORDER_STATUS_PAID)
        );
    }

    private Map<String, Integer> withPaidStatusCount(Map<String, Integer> counts, int paidCount) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (counts != null) {
            result.putAll(counts);
        }
        result.put(ORDER_STATUS_PAID, Math.max(paidCount, 0));
        return result;
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
        boolean manager = hasRole(authentication, "MANAGER");
        boolean worker = hasRole(authentication, "WORKER");
        return new WorkerPermissionsResponse(
                admin || owner,
                admin || owner || manager,
                admin || owner,
                admin || owner,
                admin || owner || worker,
                admin || owner || manager,
                admin || owner || manager || worker,
                admin || owner || manager || worker
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
                null,
                false,
                null,
                "",
                "",
                ""
        );
    }

    private WorkerReviewResponse toBadTaskReviewResponse(BadReviewTask task) {
        Review sourceReview = task.getSourceReview();
        ReviewDTOOne review = reviewService.toReviewDTOOne(sourceReview);
        Bot bot = task.getBot();
        Long botId = bot != null ? bot.getId() : review.getBotId();
        String botFio = firstNonBlank(task.getBotFioSnapshot(), bot != null ? bot.getFio() : null, review.getBotFio());
        String botLogin = firstNonBlank(task.getBotLoginSnapshot(), bot != null ? bot.getLogin() : null, review.getBotLogin());
        String botPassword = firstNonBlank(task.getBotPasswordSnapshot(), bot != null ? bot.getPassword() : null, review.getBotPassword());
        int botCounter = bot != null ? bot.getCounter() : review.getBotCounter();
        String workerFio = task.getWorker() != null && task.getWorker().getUser() != null
                ? safe(task.getWorker().getUser().getFio())
                : safe(review.getWorkerFio());
        String taskText = firstNonBlank(task.getTaskText(), review.getText());

        return new WorkerReviewResponse(
                review.getId(),
                review.getCompanyId(),
                review.getOrderDetailsId(),
                review.getOrderId(),
                safe(review.getOrderStatus()),
                taskText,
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
                safe(task.getComment()),
                false,
                null,
                "",
                "",
                ""
        );
    }

    private WorkerReviewResponse toRecoveryTaskReviewResponse(ReviewRecoveryTask task) {
        Review sourceReview = task.getSourceReview();
        ReviewDTOOne review = reviewService.toReviewDTOOne(sourceReview);
        Bot bot = task.getBot();
        Long botId = bot != null ? bot.getId() : review.getBotId();
        String botFio = bot != null ? safe(bot.getFio()) : safe(task.getBotFioSnapshot());
        String botLogin = bot != null ? safe(bot.getLogin()) : safe(task.getBotLoginSnapshot());
        String botPassword = bot != null ? safe(bot.getPassword()) : safe(task.getBotPasswordSnapshot());
        int botCounter = bot != null ? bot.getCounter() : review.getBotCounter();
        String workerFio = task.getWorker() != null && task.getWorker().getUser() != null
                ? safe(task.getWorker().getUser().getFio())
                : safe(review.getWorkerFio());
        String urlPhoto = !safe(review.getUrlPhoto()).isBlank() ? safe(review.getUrlPhoto()) : safe(review.getUrl());

        return new WorkerReviewResponse(
                review.getId(),
                recoveryCompanyId(task, review),
                review.getOrderDetailsId(),
                recoveryOrderId(task, review),
                recoveryOrderStatus(task, review),
                safe(task.getRecoveryText()),
                safe(task.getRecoveryAnswer()),
                safe(review.getCategory()),
                safe(review.getSubCategory()),
                botId,
                botFio,
                botLogin,
                botPassword,
                botCounter,
                recoveryCompanyTitle(task, review),
                recoveryCompanyNote(task, review),
                recoveryOrderNote(task, review),
                recoveryFilialCity(task, review),
                recoveryFilialTitle(task, review),
                recoveryFilialUrl(task, review),
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
                review.getPrice(),
                safe(review.getUrl()),
                urlPhoto,
                false,
                null,
                review.getId(),
                null,
                null,
                "",
                null,
                "",
                "",
                null,
                true,
                task.getId(),
                task.getStatus() == null ? "" : task.getStatus().name(),
                dateValue(task.getScheduledDate()),
                dateValue(task.getCompletedDate())
        );
    }

    private Long recoveryCompanyId(ReviewRecoveryTask task, ReviewDTOOne review) {
        if (review.getCompanyId() != null) {
            return review.getCompanyId();
        }

        Order order = task != null ? task.getOrder() : null;
        Company company = order != null ? order.getCompany() : null;
        return company != null ? company.getId() : null;
    }

    private Long recoveryOrderId(ReviewRecoveryTask task, ReviewDTOOne review) {
        return review.getOrderId() != null
                ? review.getOrderId()
                : task != null && task.getOrder() != null ? task.getOrder().getId() : null;
    }

    private String recoveryOrderStatus(ReviewRecoveryTask task, ReviewDTOOne review) {
        return firstNonBlank(
                review.getOrderStatus(),
                task != null && task.getOrder() != null && task.getOrder().getStatus() != null
                        ? task.getOrder().getStatus().getTitle()
                        : ""
        );
    }

    private String recoveryCompanyTitle(ReviewRecoveryTask task, ReviewDTOOne review) {
        String reviewTitle = safe(review.getCompanyTitle()).trim();
        if (!reviewTitle.isBlank()
                && !REVIEW_DTO_ERROR_COMPANY_TITLE.equalsIgnoreCase(reviewTitle)
                && !REVIEW_DTO_MISSING_ORDER_TITLE.equalsIgnoreCase(reviewTitle)) {
            return reviewTitle;
        }

        Order order = task != null ? task.getOrder() : null;
        Company company = order != null ? order.getCompany() : null;
        return firstNonBlank(company != null ? company.getTitle() : "", reviewTitle);
    }

    private String recoveryCompanyNote(ReviewRecoveryTask task, ReviewDTOOne review) {
        Order order = task != null ? task.getOrder() : null;
        Company company = order != null ? order.getCompany() : null;
        return firstNonBlank(review.getCommentCompany(), company != null ? company.getCommentsCompany() : "");
    }

    private String recoveryOrderNote(ReviewRecoveryTask task, ReviewDTOOne review) {
        Order order = task != null ? task.getOrder() : null;
        return firstNonBlank(review.getOrderComments(), order != null ? order.getZametka() : "");
    }

    private String recoveryFilialCity(ReviewRecoveryTask task, ReviewDTOOne review) {
        Review sourceReview = task != null ? task.getSourceReview() : null;
        Filial filial = sourceReview != null ? sourceReview.getFilial() : null;
        Order order = task != null ? task.getOrder() : null;
        Company company = order != null ? order.getCompany() : null;

        return firstNonBlank(
                review.getFilialCity(),
                filial != null && filial.getCity() != null ? filial.getCity().getTitle() : "",
                company != null ? company.getCity() : ""
        );
    }

    private String recoveryFilialTitle(ReviewRecoveryTask task, ReviewDTOOne review) {
        Review sourceReview = task != null ? task.getSourceReview() : null;
        Filial filial = sourceReview != null ? sourceReview.getFilial() : null;
        return firstNonBlank(review.getFilialTitle(), filial != null ? filial.getTitle() : "");
    }

    private String recoveryFilialUrl(ReviewRecoveryTask task, ReviewDTOOne review) {
        Review sourceReview = task != null ? task.getSourceReview() : null;
        Filial filial = sourceReview != null ? sourceReview.getFilial() : null;
        return firstNonBlank(review.getFilialUrl(), filial != null ? filial.getUrl() : "");
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

    private void clearClientWaitingIfNeeded(Long orderId, String status) {
        if (isClientWaitingStatus(status)) {
            return;
        }

        Order order = orderService.getOrder(orderId);
        if (!order.isWaitingForClient()) {
            return;
        }

        order.setWaitingForClient(false);
        order.setWaitingForClientChangedAt(null);
        orderService.save(order);
    }

    private WorkerSelection resolveWorkerSelection(
            Principal principal,
            Authentication authentication,
            Long requestedWorkerId
    ) {
        boolean available = canSelectWorkerFilter(authentication);
        if (!available) {
            if (requestedWorkerId != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Выбор специалиста недоступен");
            }
            return new WorkerSelection(List.of(), null, false);
        }

        List<Worker> workers = workerFilterWorkers(principal, authentication);
        List<WorkerOptionResponse> options = workers.stream()
                .map(worker -> new WorkerOptionResponse(worker.getId(), workerOptionLabel(worker)))
                .toList();

        if (requestedWorkerId == null) {
            return new WorkerSelection(options, null, true);
        }

        Worker selectedWorker = workers.stream()
                .filter(worker -> Objects.equals(worker.getId(), requestedWorkerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Этот специалист недоступен"));

        return new WorkerSelection(options, selectedWorker, true);
    }

    private boolean canSelectWorkerFilter(Authentication authentication) {
        return hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER") || hasRole(authentication, "MANAGER");
    }

    private List<Worker> workerFilterWorkers(Principal principal, Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return sortWorkerOptions(workerService.getAllWorkers());
        }
        if (hasRole(authentication, "OWNER")) {
            List<Manager> managers = resolveOwnerManagers(principal).stream().toList();
            return managers.isEmpty()
                    ? List.of()
                    : sortWorkerOptions(workerService.getAllWorkersToManagerList(managers).stream().toList());
        }
        if (hasRole(authentication, "MANAGER")) {
            Manager manager = resolveManager(principal);
            return manager == null ? List.of() : sortWorkerOptions(workerService.getAllWorkersToManager(manager));
        }
        return List.of();
    }

    private List<Worker> sortWorkerOptions(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }

        return workers.stream()
                .filter(worker -> worker != null && worker.getId() != null)
                .distinct()
                .sorted(Comparator.comparing(this::workerOptionLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String workerOptionLabel(Worker worker) {
        User user = worker == null ? null : worker.getUser();
        if (user != null && !safe(user.getFio()).isBlank()) {
            return user.getFio().trim();
        }
        if (user != null && !safe(user.getUsername()).isBlank()) {
            return user.getUsername().trim();
        }
        return worker != null && worker.getId() != null ? "Специалист #" + worker.getId() : "Специалист";
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

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }

        return userService.findByUserName(authentication.getName()).orElse(null);
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return userService.findManagersByUserName(principal.getName());
    }

    private List<ManagerOverdueStatusResponse> toOverdueStatuses(List<Object[]> rows, LocalDate today) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        return rows.stream()
                .map(row -> new ManagerOverdueStatusResponse(
                        rowString(row, 0, "Без статуса"),
                        rowLong(row, 1),
                        daysSince(rowDate(row, 2), today)
                ))
                .filter(status -> status.count() > 0)
                .sorted(Comparator.comparingLong(ManagerOverdueStatusResponse::maxDays).reversed())
                .toList();
    }

    private long daysSince(LocalDate date, LocalDate today) {
        if (date == null) {
            return 0;
        }

        return ChronoUnit.DAYS.between(date, today);
    }

    private long rowLong(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private LocalDate rowDate(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof LocalDate localDate ? localDate : null;
    }

    private String rowString(Object[] row, int index, String fallback) {
        Object value = rowValue(row, index);
        if (value == null) {
            return fallback;
        }

        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private Object rowValue(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }

    private boolean isOrderSection(String section) {
        return SECTION_NEW.equals(section) || SECTION_CORRECT.equals(section) || SECTION_ALL.equals(section);
    }

    private boolean isReviewSection(String section) {
        return SECTION_NAGUL.equals(section)
                || SECTION_RECOVERY.equals(section)
                || SECTION_PUBLISH.equals(section)
                || SECTION_BAD.equals(section);
    }

    private boolean isCurrentSectionRequest(String section) {
        return section != null && SECTION_CURRENT.equals(section.toLowerCase(Locale.ROOT).trim());
    }

    private String currentWorkSection(List<WorkerMetricResponse> metrics) {
        return CURRENT_WORK_SECTIONS.stream()
                .filter(section -> metricValue(metrics, section) > 0)
                .findFirst()
                .orElse(SECTION_NEW);
    }

    private int metricValue(List<WorkerMetricResponse> metrics, String section) {
        if (metrics == null || metrics.isEmpty()) {
            return 0;
        }

        return metrics.stream()
                .filter(metric -> section.equals(metric.section()))
                .mapToInt(WorkerMetricResponse::value)
                .findFirst()
                .orElse(0);
    }

    private WorkerFlowRedirect workerFlowRedirect(Principal principal, Authentication authentication, String requestedSection) {
        if (!isWorkerFlowRestricted(authentication)) {
            return null;
        }

        if (!isPublishOrAll(requestedSection)) {
            return null;
        }

        Worker worker = resolveWorker(principal);
        Map<String, Integer> orderCounts = orderService.countActionableOrdersByStatusToWorker(worker);
        int newOrders = countStatus(orderCounts, ORDER_STATUS_NEW);
        int correctionOrders = countStatus(orderCounts, ORDER_STATUS_CORRECT);
        String lockKey = workerFlowLockKey(worker, principal);
        boolean hasFlowOrders = newOrders + correctionOrders > 0;

        if (!hasFlowOrders) {
            workerFlowLockService.syncPublicationLock(lockKey, workerId(worker), false, false);
            return null;
        }

        if (!workerFlowLockService.syncPublicationLock(
                lockKey,
                workerId(worker),
                true,
                hasStaleWorkerFlowOrders(worker)
        )) {
            return null;
        }

        if (newOrders > 0) {
            return new WorkerFlowRedirect(SECTION_NEW, WORKER_FLOW_BLOCK_MESSAGE);
        }

        return new WorkerFlowRedirect(SECTION_CORRECT, WORKER_FLOW_BLOCK_MESSAGE);
    }

    private boolean hasStaleWorkerFlowOrders(Worker worker) {
        Map<String, Integer> staleCounts = orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                worker,
                WORKER_FLOW_ORDER_STATUSES,
                LocalDate.now().minusDays(WORKER_FLOW_BLOCKING_UNCHANGED_DAYS)
        );

        return countStatus(staleCounts, ORDER_STATUS_NEW) + countStatus(staleCounts, ORDER_STATUS_CORRECT) > 0;
    }

    private Long workerId(Worker worker) {
        return worker == null ? null : worker.getId();
    }

    private String workerFlowLockKey(Worker worker, Principal principal) {
        if (worker != null && worker.getId() != null) {
            return "worker:" + worker.getId();
        }

        return "principal:" + (principal == null ? "" : principal.getName());
    }

    private boolean isWorkerFlowRestricted(Authentication authentication) {
        return hasRole(authentication, "WORKER")
                && !hasRole(authentication, "ADMIN")
                && !hasRole(authentication, "OWNER")
                && !hasRole(authentication, "MANAGER");
    }

    private boolean isPublishOrAll(String section) {
        return SECTION_PUBLISH.equals(section)
                || SECTION_ALL.equals(section);
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
                ? Sort.by("publishedDate").ascending().and(Sort.by("id").ascending())
                : Sort.by("publishedDate").descending().and(Sort.by("id").descending());
    }

    private Sort badReviewTaskSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("scheduledDate").ascending().and(Sort.by("id").ascending())
                : Sort.by("scheduledDate").descending().and(Sort.by("id").descending());
    }

    private Sort recoveryTaskSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("scheduledDate").ascending().and(Sort.by("id").ascending())
                : Sort.by("scheduledDate").descending().and(Sort.by("id").descending());
    }

    private String normalizeSection(String section) {
        String normalized = section == null ? SECTION_NEW : section.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case SECTION_CORRECT, SECTION_NAGUL, SECTION_RECOVERY, SECTION_PUBLISH, SECTION_BAD, SECTION_ALL -> normalized;
            default -> SECTION_NEW;
        };
    }

    private String normalizeSortDirection(String sortDirection) {
        return "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
    }

    private LocalDate nagulLookaheadDate() {
        return LocalDate.now().plusDays(nagulLookaheadDays());
    }

    private int nagulLookaheadDays() {
        return appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60);
    }

    private String title(String section) {
        return switch (section) {
            case SECTION_CORRECT -> "Коррекция";
            case SECTION_NAGUL -> "Выгул";
            case SECTION_RECOVERY -> "Восстановление";
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

    private boolean isClientWaitingStatus(String status) {
        return CLIENT_WAITING_ORDER_STATUSES.contains(status);
    }

    private String orderStatusTitle(Order order) {
        return order != null && order.getStatus() != null ? safe(order.getStatus().getTitle()) : "";
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeReviewCopyField(ReviewCopyClickRequest request) {
        String field = request == null ? "" : safe(request.field()).trim().toLowerCase(Locale.ROOT);
        if (!REVIEW_CREDENTIAL_COPY_FIELDS.contains(field)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Кнопка для логирования не поддерживается");
        }
        return field;
    }

    private String copyFieldLabel(String field) {
        return "password".equals(field) ? "пароль" : "логин";
    }

    private String principalName(Principal principal) {
        return principal == null ? "unknown" : safe(principal.getName());
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
        Sort sort = reviewSort(sortDirection);

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

    private PageResponse<WorkerReviewResponse> toRecoveryTaskPageResponse(Page<ReviewRecoveryTask> page) {
        return new PageResponse<>(
                page.getContent().stream().map(this::toRecoveryTaskReviewResponse).toList(),
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

    private Long botId(Review review) {
        Bot bot = review != null ? review.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    private Long botId(BadReviewTask task) {
        Bot bot = task != null ? task.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    private Long botId(ReviewRecoveryTask task) {
        Bot bot = task != null ? task.getBot() : null;
        return bot != null ? bot.getId() : null;
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
            List<WorkerOptionResponse> workerOptions,
            Long selectedWorkerId,
            boolean workerFilterAvailable,
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
            String section,
            int delta
    ) {
        public WorkerMetricResponse(
                String label,
                int value,
                String icon,
                String tone,
                String section
        ) {
            this(label, value, icon, tone, section, 0);
        }

        public WorkerMetricResponse withDelta(int delta) {
            return new WorkerMetricResponse(
                    label,
                    value,
                    icon,
                    tone,
                    section,
                    Math.max(0, delta)
            );
        }
    }

    public record WorkerPermissionsResponse(
            boolean canManageOrderStatuses,
            boolean canManageClientWaiting,
            boolean canSeePhoneAndPayment,
            boolean canManageBots,
            boolean canAddBot,
            boolean canSeeMoney,
            boolean canWorkReviews,
            boolean canEditNotes
    ) {
    }

    private record WorkerSelection(
            List<WorkerOptionResponse> options,
            Worker selectedWorker,
            boolean available
    ) {
    }

    public record WorkerOptionResponse(
            Long id,
            String label
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
            String badTaskComment,
            boolean recoveryTask,
            Long recoveryTaskId,
            String recoveryTaskStatus,
            String recoveryTaskScheduledDate,
            String recoveryTaskCompletedDate
    ) {
    }

    public record StatusChangeRequest(String status) {
    }

    public record ClientWaitingRequest(Boolean waitingForClient) {
    }

    public record ReviewCopyClickRequest(String field) {
    }

    public record OrderNoteUpdateRequest(String orderComments) {
    }

    public record CompanyNoteUpdateRequest(String companyComments) {
    }

    public record ReviewTextUpdateRequest(Long orderId, String text) {
    }

    public record BadTaskUpdateRequest(String taskText, LocalDate scheduledDate) {
    }

    public record RecoveryTaskUpdateRequest(String recoveryText, String recoveryAnswer, LocalDate scheduledDate) {
    }

    public record ReviewAnswerUpdateRequest(Long orderId, String answer) {
    }

    public record ReviewNoteUpdateRequest(Long orderId, String comment) {
    }

    public record WorkerActionResponse(boolean success, String message) {
    }

    public record BotChangeResponse(Long oldBotId, Long newBotId) {
    }

    private record WorkerFlowRedirect(String section, String message) {
    }
}
