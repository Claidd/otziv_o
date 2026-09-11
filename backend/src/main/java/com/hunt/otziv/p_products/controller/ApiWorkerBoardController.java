package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.p_products.application.WorkerStaffAccessPolicy;
import com.hunt.otziv.p_products.application.WorkerOrderCommands;
import com.hunt.otziv.p_products.application.WorkerTaskEditingCommands;
import com.hunt.otziv.p_products.application.WorkerTaskAssignmentCommands;
import com.hunt.otziv.p_products.application.WorkerTaskAccountCommands;
import com.hunt.otziv.p_products.application.WorkerTaskCompletionCommands;
import com.hunt.otziv.p_products.application.WorkerReviewContentCommands;
import com.hunt.otziv.p_products.application.WorkerReviewPublicationCommands;
import com.hunt.otziv.p_products.application.WorkerCredentialCommands;

import com.hunt.otziv.p_products.application.WorkerOrderActor;
import com.hunt.otziv.p_products.application.WorkerOrderCommandException;
import org.springframework.security.core.context.SecurityContextHolder;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.l_lead.service.PromoTextService;
import com.hunt.otziv.manager.dto.api.ManagerOverdueOrdersResponse;
import com.hunt.otziv.manager.dto.api.ManagerOverdueStatusResponse;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.board.service.OrderBoardQueryService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.board.service.WorkerBoardTaskQueries;
import com.hunt.otziv.p_products.board.service.WorkerOverdueOrdersQuery;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationSessionService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.security.credentials.CredentialRevealRequest;
import com.hunt.otziv.security.credentials.CredentialRevealResponse;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import com.hunt.otziv.worker_activity.service.WorkerRiskAccessPolicy;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import static com.hunt.otziv.config.metrics.PerformanceMetrics.segment;
import java.time.LocalDate;
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
    private static final Set<String> CLIENT_WAITING_ORDER_STATUSES = Set.of(ORDER_STATUS_NEW, ORDER_STATUS_CORRECT);
    private static final Set<String> REVIEW_CREDENTIAL_COPY_FIELDS = Set.of("login", "password");
    private static final int REVIEW_PUBLISH_CREDENTIAL_WAIT_SECONDS = 150;
    private static final int REVIEW_NAGUL_CREDENTIAL_WAIT_SECONDS = 180;
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
    private static final String OWNER_CONTROL_ALL_MANAGERS = "ALL_MANAGERS";
    private static final LocalDate DATABASE_MAX_DATE = LocalDate.of(9999, 12, 31);

    private final WorkerStaffAccessPolicy staffAccess;
    private final WorkerOrderCommands orderCommands;
    private final WorkerTaskEditingCommands workerTaskEditingCommands;
    private final WorkerTaskAssignmentCommands workerTaskAssignmentCommands;
    private final WorkerTaskAccountCommands workerTaskAccountCommands;
    private final WorkerTaskCompletionCommands workerTaskCompletionCommands;
    private final WorkerReviewContentCommands workerReviewContentCommands;
    private final WorkerReviewPublicationCommands workerReviewPublicationCommands;
    private final WorkerCredentialCommands workerCredentialCommands;

    private final com.hunt.otziv.p_products.application.WorkerReviewAccountCommands accountCommands;
    private final OrderService orderService;
    private final OrderBoardQueryService orderBoardQueryService;
    private final WorkerBoardTaskQueries boardTaskQueries;
    private final WorkerOverdueOrdersQuery overdueOrdersQuery;
    private final ReviewService reviewService;
    private final PromoTextService promoTextService;
    private final BotService botService;
    private final PerformanceMetrics performanceMetrics;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final UserMetricSnapshotService metricSnapshotService;
    private final AppSettingService appSettingService;
    private final WorkerPublicationGateService workerPublicationGateService;
    private final WorkerCredentialPreparationService credentialPreparationService;
    private final StaffDailyProgressService staffDailyProgressService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerRiskAccessPolicy workerRiskAccessPolicy;

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
            try (var identityReads = com.hunt.otziv.u_users.api.BoardIdentityReadScope.open()) {
                String normalizedSection = normalizeSection(section);
                String message = "";
                boolean warning = false;
                List<WorkerMetricResponse> metrics = null;
                WorkerSelection workerSelection = resolveWorkerSelection(principal, authentication, workerId);
                Worker selectedWorker = workerSelection.selectedWorker();
                WorkerRiskAccessPolicy.Status accessRestriction = workerRiskAccessPolicy.status(principal.getName());
                if (accessRestriction == null) {
                    accessRestriction = WorkerRiskAccessPolicy.Status.allowed();
                }

                if (accessRestriction.restricted()) {
                    int safePageNumber = Math.max(pageNumber, 0);
                    int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
                    return new WorkerBoardResponse(
                            normalizedSection,
                            title(normalizedSection),
                            toPageResponse(emptyPage(safePageNumber, safePageSize)),
                            emptyReviewResponsePage(safePageNumber, safePageSize),
                            List.of(),
                            List.of(),
                            List.of(),
                            buildPermissions(authentication),
                            workerSelection.options(),
                            workerId(selectedWorker),
                            workerSelection.available(),
                            accessRestriction.message(),
                            true,
                            null,
                            null,
                            null,
                            accessRestriction
                    );
                }

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

                String boardSection = normalizedSection;
                segment("worker.board", "cellular-access", () -> {
                    workerCellularAccessService.enforceSection(boardSection);
                    return null;
                });

                int safePageNumber = Math.max(pageNumber, 0);
                int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
                String normalizedSortDirection = normalizeSortDirection(sortDirection);
                String trimmedKeyword = keyword == null ? "" : keyword.trim();

                Page<OrderDTOList> orders = isOrderSection(boardSection)
                        ? segment("worker.board", "orders", () -> loadOrders(principal, authentication, selectedWorker, boardSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection))
                        : emptyPage(safePageNumber, safePageSize);
                if (hasOnlyWorkerRole(authentication)) {
                    orders.forEach(this::removeFinancialData);
                }

                PageResponse<WorkerReviewResponse> reviews = isReviewSection(boardSection)
                        ? segment("worker.board", "reviews", () -> loadReviewResponses(principal, authentication, selectedWorker, boardSection, trimmedKeyword, safePageNumber, safePageSize, normalizedSortDirection))
                        : emptyReviewResponsePage(safePageNumber, safePageSize);

                return new WorkerBoardResponse(
                        boardSection,
                        title(boardSection),
                        toPageResponse(orders),
                        reviews,
                        List.of(),
                        metrics != null ? metrics : segment("worker.board", "metrics", () -> buildMetrics(principal, authentication, selectedWorker)),
                        promoTextService.getAllPromoTexts(),
                        buildPermissions(authentication),
                        workerSelection.options(),
                        workerId(selectedWorker),
                        workerSelection.available(),
                        message,
                        warning,
                        activeCredentialPreparation(authentication, boardSection),
                        workerPublicationGateService.sessionState(principal, authentication),
                        segment("worker.board", "progress", () -> workerDailyProgress(principal, authentication, selectedWorker)),
                        accessRestriction
                );
            }
        });
    }

    @GetMapping("/overdue-orders")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ManagerOverdueOrdersResponse getOverdueOrders(
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("worker.overdue-orders", () -> {
            try {
                var overdue = overdueOrdersQuery.query(principal, authentication);
                return new ManagerOverdueOrdersResponse(overdue.thresholdDays(), overdue.total(),
                        overdue.statuses().stream().map(section -> new ManagerOverdueStatusResponse(
                                section.status(), section.count(), section.maxDays())).toList());
            } catch (WorkerOrderCommandException failure) {
                throw commandHttpFailure(failure);
            }
        });
    }

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'WORKER')")
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request,
            HttpServletRequest servletRequest,
            Principal principal,
            Authentication authentication
    ) throws Exception {
        String status = requireStatus(request);
        servletRequest.setAttribute("status", status);
        try {
            orderCommands.changeStatus(orderId, status, WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
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
        try {
            orderCommands.changeClientWaiting(orderId, request.waitingForClient(), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
        try {
            orderCommands.changeOrderNote(orderId, request.orderComments(), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
        try {
            orderCommands.changeCompanyNote(orderId, request.companyComments(), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/reviews/{reviewId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeReviewBot(
            @PathVariable Long reviewId,
            @RequestBody(required = false) WorkerActivitySourceRequest source,
            Principal principal,
            Authentication authentication
    ) {
        try {
            var result = accountCommands.change(reviewId, accountSource(source), WorkerOrderActor.from(authentication));
            return new BotChangeResponse(result.oldBotId(), result.newBotId());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/reviews/{reviewId}/bots/{botId}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotDeactivateResponse deactivateReviewBot(
            @PathVariable Long reviewId,
            @PathVariable Long botId,
            @RequestBody(required = false) WorkerActivitySourceRequest source,
            Principal principal,
            Authentication authentication
    ) {
        try {
            var result = accountCommands.deactivate(reviewId, botId, accountSource(source), WorkerOrderActor.from(authentication));
            return new BotDeactivateResponse(result.blockedBotId(), result.newBotId(), result.replacementFound());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    private com.hunt.otziv.p_products.application.WorkerReviewAccountCommands.Source accountSource(WorkerActivitySourceRequest source) {
        return new com.hunt.otziv.p_products.application.WorkerReviewAccountCommands.Source(
                source == null ? null : source.sourcePage(), source == null ? null : source.sourceEntry(),
                source == null ? null : source.sourceSection());
    }

    @PostMapping("/reviews/{reviewId}/copy-click")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public WorkerCredentialPreparationResponse logReviewCredentialCopyClick(
            @PathVariable Long reviewId,
            @RequestBody ReviewCopyClickRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            return workerCredentialCommands.logReviewCredentialCopyClick(reviewId, request == null ? null : new WorkerCredentialCommands.ReviewCopyClickRequest(request.field(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/reviews/{reviewId}/credential-reveal")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<CredentialRevealResponse> revealReviewCredential(
            @PathVariable Long reviewId,
            @RequestBody CredentialRevealRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            return noStore(workerCredentialCommands.revealReviewCredential(reviewId, request, WorkerOrderActor.from(authentication)));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/credential-reveal")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<CredentialRevealResponse> revealRecoveryTaskCredential(
            @PathVariable Long taskId,
            @RequestBody CredentialRevealRequest request,
            Authentication authentication
    ) {
        try {
            return noStore(workerCredentialCommands.revealRecoveryTaskCredential(taskId, request, WorkerOrderActor.from(authentication)));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/credential-reveal")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public ResponseEntity<CredentialRevealResponse> revealBadReviewTaskCredential(
            @PathVariable Long taskId,
            @RequestBody CredentialRevealRequest request,
            Authentication authentication
    ) {
        try {
            return noStore(workerCredentialCommands.revealBadReviewTaskCredential(taskId, request, WorkerOrderActor.from(authentication)));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/copy-click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void logRecoveryTaskCredentialCopyClick(
            @PathVariable Long taskId,
            @RequestBody ReviewCopyClickRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            workerCredentialCommands.logRecoveryTaskCredentialCopyClick(taskId, request == null ? null : new WorkerCredentialCommands.ReviewCopyClickRequest(request.field(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/copy-click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void logBadReviewTaskCredentialCopyClick(
            @PathVariable Long taskId,
            @RequestBody ReviewCopyClickRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            workerCredentialCommands.logBadReviewTaskCredentialCopyClick(taskId, request == null ? null : new WorkerCredentialCommands.ReviewCopyClickRequest(request.field(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/reviews/{reviewId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void publishReview(
            @PathVariable Long reviewId,
            Principal principal,
            Authentication authentication
    ) {
        try {
            workerReviewPublicationCommands.publishReview(reviewId, WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void completeBadReviewTask(
            @PathVariable Long taskId,
            Authentication authentication
    ) {
        try {
            workerTaskCompletionCommands.completeBadReviewTask(taskId, WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/bad-review-tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateBadReviewTask(
            @PathVariable Long taskId,
            @RequestBody BadTaskUpdateRequest request,
            Authentication authentication
    ) {
        try {
            workerTaskEditingCommands.updateBadReviewTask(taskId, request == null ? null : new WorkerTaskEditingCommands.BadTaskUpdateRequest(request.taskText(), request.scheduledDate()), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/bad-review-tasks/{taskId}/worker")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void reassignBadReviewTask(
            @PathVariable Long taskId,
            @RequestBody WorkerAssignmentRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            workerTaskAssignmentCommands.reassignBadReviewTask(taskId, request == null ? null : request.workerId(), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/recovery-tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateRecoveryTask(
            @PathVariable Long taskId,
            @RequestBody RecoveryTaskUpdateRequest request,
            Authentication authentication
    ) {
        try {
            workerTaskEditingCommands.updateRecoveryTask(taskId, request == null ? null : new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest(request.recoveryText(), request.recoveryAnswer(), request.scheduledDate()), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/recovery-tasks/{taskId}/worker")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void reassignRecoveryTask(
            @PathVariable Long taskId,
            @RequestBody WorkerAssignmentRequest request,
            Principal principal,
            Authentication authentication
    ) {
        try {
            workerTaskAssignmentCommands.reassignRecoveryTask(taskId, request == null ? null : request.workerId(), WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
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
            workerTaskCompletionCommands.completeRecoveryTask(taskId, WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/recovery-tasks/{taskId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeRecoveryTaskBot(@PathVariable Long taskId) {
        try {
            var result = workerTaskAccountCommands.changeRecoveryTaskBot(taskId, currentOrderActor());
            return new BotChangeResponse(result.oldBotId(), result.newBotId());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
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
            workerTaskAccountCommands.deactivateRecoveryTaskBot(taskId, botId, currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/bad-review-tasks/{taskId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public BotChangeResponse changeBadReviewTaskBot(@PathVariable Long taskId) {
        try {
            var result = workerTaskAccountCommands.changeBadReviewTaskBot(taskId, currentOrderActor());
            return new BotChangeResponse(result.oldBotId(), result.newBotId());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
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
            workerTaskAccountCommands.deactivateBadReviewTaskBot(taskId, botId, currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PostMapping("/reviews/{reviewId}/nagul")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public WorkerActionResponse nagulReview(
            @PathVariable Long reviewId,
            Principal principal,
            Authentication authentication
    ) {
        try {
            var result = workerReviewPublicationCommands.nagulReview(reviewId, WorkerOrderActor.from(authentication));
            return new WorkerActionResponse(result.success(), result.message());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/reviews/{reviewId}/text")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewText(
            @PathVariable Long reviewId,
            @RequestBody ReviewTextUpdateRequest request
    ) {
        try {
            workerReviewContentCommands.updateReviewText(reviewId, request == null ? null : new WorkerReviewContentCommands.ReviewTextUpdateRequest(request.orderId(), request.text(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/reviews/{reviewId}/bot-name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewBotName(
            @PathVariable Long reviewId,
            @RequestBody ReviewBotNameUpdateRequest request
    ) {
        try {
            accountCommands.rename(reviewId, request == null ? null : request.botName(), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/reviews/{reviewId}/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewAnswer(
            @PathVariable Long reviewId,
            @RequestBody ReviewAnswerUpdateRequest request
    ) {
        try {
            workerReviewContentCommands.updateReviewAnswer(reviewId, request == null ? null : new WorkerReviewContentCommands.ReviewAnswerUpdateRequest(request.orderId(), request.answer(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @PutMapping("/reviews/{reviewId}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateReviewNote(
            @PathVariable Long reviewId,
            @RequestBody ReviewNoteUpdateRequest request
    ) {
        try {
            workerReviewContentCommands.updateReviewNote(reviewId, request == null ? null : new WorkerReviewContentCommands.ReviewNoteUpdateRequest(request.orderId(), request.comment(), request.sourcePage(), request.sourceEntry(), request.sourceSection()), currentOrderActor());
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    @DeleteMapping("/bots/{botId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteBot(@PathVariable Long botId, Authentication authentication) {
        try {
            accountCommands.delete(botId, WorkerOrderActor.from(authentication));
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
                : orderBoardQueryService.getAllOrderDTOAndKeywordByWorker(resolveWorker(principal), keyword, status, pageNumber, pageSize, sortDirection);
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

        return toReviewPageResponse(
                loadReviewPage(principal, authentication, selectedWorker, section, pageNumber, pageSize, sortDirection, keyword)
        );
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
        return loadRecoveryTasks(
                principal,
                authentication,
                selectedWorker,
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                recoveryTaskBoardHorizon(authentication)
        );
    }

    private Page<ReviewRecoveryTask> loadRecoveryTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            LocalDate dueOnOrBefore
    ) {
        try {
            return boardTaskQueries.loadRecoveryTasks(principal, authentication, selectedWorker, keyword, pageNumber, pageSize, sortDirection, dueOnOrBefore);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
        return loadBadReviewTasks(
                principal,
                authentication,
                selectedWorker,
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                LocalDate.now()
        );
    }

    private Page<BadReviewTask> loadBadReviewTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            LocalDate dueOnOrBefore
    ) {
        try {
            return boardTaskQueries.loadBadReviewTasks(principal, authentication, selectedWorker, keyword, pageNumber, pageSize, sortDirection, dueOnOrBefore);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
        return loadReviewPage(
                principal,
                authentication,
                selectedWorker,
                section,
                pageNumber,
                pageSize,
                sortDirection,
                keyword,
                date
        );
    }

    private Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String section,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword,
            LocalDate dueOnOrBefore
    ) {
        try {
            return boardTaskQueries.loadReviewPage(principal, authentication, selectedWorker, section, pageNumber, pageSize, sortDirection, keyword, dueOnOrBefore);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    private List<BotResponse> loadBots(Principal principal, Authentication authentication) {
        List<BotDTO> bots;
        if (hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER")) {
            bots = botService.getAllBots(authentication);
        } else if (hasRole(authentication, "WORKER")) {
            bots = botService.getAllBotsByWorkerActiveIsTrue(authentication);
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
        // The counters describe what the user can see in every section. Orders that
        // wait for a client remain visible in "New"/"Correction", so they must be
        // included here as well. Actionable counts are still used separately for
        // the publication flow lock below.
        Map<String, Integer> orderCounts = countVisibleOrderMetrics(principal, authentication, selectedWorker);
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
        syncWorkerFlowLockFromMetrics(
                principal,
                authentication,
                countOrderMetrics(principal, authentication, selectedWorker)
        );

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
        int flowOrders = countStatus(orderCounts, ORDER_STATUS_NEW) + countStatus(orderCounts, ORDER_STATUS_CORRECT);
        workerPublicationGateService.syncFromMetrics(principal, authentication, flowOrders);
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
        LocalDate date = recoveryTaskBoardHorizon(authentication);
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

    private LocalDate recoveryTaskBoardHorizon(Authentication authentication) {
        if (hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER") || hasRole(authentication, "MANAGER")) {
            return DATABASE_MAX_DATE;
        }
        return LocalDate.now();
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

    private Map<String, Integer> countVisibleOrderMetrics(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker
    ) {
        if (selectedWorker != null) {
            return withPaidStatusCount(
                    orderService.countOrdersByStatusToWorker(selectedWorker),
                    orderService.countOrdersByWorkerAndStatus(selectedWorker, ORDER_STATUS_PAID)
            );
        }
        if (hasRole(authentication, "ADMIN")) {
            return orderService.countOrdersByStatus();
        }
        if (hasRole(authentication, "OWNER")) {
            return orderService.countOrdersByStatusToOwner(resolveOwnerManagers(principal));
        }
        if (hasRole(authentication, "MANAGER")) {
            Manager manager = resolveManager(principal);
            return withPaidStatusCount(
                    orderService.countOrdersByStatusToManager(manager),
                    orderService.getAllOrderDTOByStatusToManager(manager, ORDER_STATUS_PAID)
            );
        }
        Worker worker = resolveWorker(principal);
        return withPaidStatusCount(
                orderService.countOrdersByStatusToWorker(worker),
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
                admin || owner || manager,
                admin || owner,
                admin || owner || worker,
                admin || owner || manager,
                admin || owner || manager || worker,
                admin || owner || manager || worker
        );
    }

    private boolean hasOnlyWorkerRole(Authentication authentication) {
        return hasRole(authentication, "WORKER")
                && !hasRole(authentication, "ADMIN")
                && !hasRole(authentication, "OWNER")
                && !hasRole(authentication, "MANAGER");
    }

    private void removeFinancialData(OrderDTOList order) {
        if (order == null) {
            return;
        }
        order.setSum(null);
        order.setTotalSumWithBadReviews(null);
        order.setBadReviewTasksSum(null);
        order.setManagerPayText(null);
        order.setCommonInvoiceAmount(null);
        order.setCommonInvoicePaid(null);
        order.setCommonInvoiceRemaining(null);
        order.setCommonInvoicePublicUrl(null);
        order.setCommonInvoiceLastError(null);
    }

    private WorkerCredentialPreparationResponse activeCredentialPreparation(Authentication authentication, String section) {
        if (SECTION_PUBLISH.equals(section)) {
            return credentialPreparationService.active(authentication, WorkerCredentialPreparationScope.PUBLISH);
        }
        if (SECTION_NAGUL.equals(section)) {
            return credentialPreparationService.active(authentication, WorkerCredentialPreparationScope.NAGUL);
        }
        return null;
    }

    private BotResponse toBotResponse(BotDTO bot) {
        String workerFio = bot.getWorker() != null && bot.getWorker().getUser() != null
                ? safe(bot.getWorker().getUser().getFio())
                : "";
        String city = bot.getBotCity() != null ? safe(bot.getBotCity().getTitle()) : "";
        return new BotResponse(
                bot.getId(),
                !safe(bot.getLogin()).isBlank(),
                !safe(bot.getPassword()).isBlank(),
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
                !safe(review.getBotLogin()).isBlank(),
                !safe(review.getBotPassword()).isBlank(),
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
                "",
                null
        );
    }

    private WorkerReviewResponse toBadTaskReviewResponse(BadReviewTask task) {
        Review sourceReview = task.getSourceReview();
        ReviewDTOOne review = reviewService.toReviewDTOOne(sourceReview);
        Bot bot = task.getBot();
        Long botId = bot != null ? bot.getId() : review.getBotId();
        String botFio = firstNonBlank(task.getBotFioSnapshot(), bot != null ? bot.getFio() : null, review.getBotFio());
        boolean botLoginPresent = !firstNonBlank(
                task.getBotLoginSnapshot(),
                bot != null ? bot.getLogin() : null,
                review.getBotLogin()
        ).isBlank();
        boolean botPasswordPresent = !firstNonBlank(
                task.getBotPasswordSnapshot(),
                bot != null ? bot.getPassword() : null,
                review.getBotPassword()
        ).isBlank();
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
                botLoginPresent,
                botPasswordPresent,
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
                "",
                workerId(task.getWorker())
        );
    }

    private WorkerReviewResponse toRecoveryTaskReviewResponse(ReviewRecoveryTask task) {
        Review sourceReview = task.getSourceReview();
        ReviewDTOOne review = sourceReview == null
                ? archiveRecoveryReview(task)
                : reviewService.toReviewDTOOne(sourceReview);
        Bot bot = task.getBot();
        Long botId = bot != null ? bot.getId() : review.getBotId();
        String botFio = bot != null ? safe(bot.getFio()) : safe(task.getBotFioSnapshot());
        boolean botLoginPresent = !(bot != null
                ? safe(bot.getLogin())
                : safe(task.getBotLoginSnapshot())).isBlank();
        boolean botPasswordPresent = !(bot != null
                ? safe(bot.getPassword())
                : safe(task.getBotPasswordSnapshot())).isBlank();
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
                botLoginPresent,
                botPasswordPresent,
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
                dateValue(task.getCompletedDate()),
                workerId(task.getWorker())
        );
    }

    private ReviewDTOOne archiveRecoveryReview(ReviewRecoveryTask task) {
        if (task == null) {
            return new ReviewDTOOne();
        }
        Bot bot = task.getBot();
        return ReviewDTOOne.builder()
                .id(task.getArchiveReviewId())
                .companyId(task.getArchiveCompanyId())
                .orderDetailsId(task.getArchiveOrderDetailsId())
                .orderId(task.getArchiveOrderId())
                .orderStatus(firstNonBlank(task.getArchiveOrderStatus(), "Архив"))
                .text(task.getOriginalText())
                .answer(task.getOriginalAnswer())
                .category(task.getArchiveCategory())
                .subCategory(task.getArchiveSubCategory())
                .botId(bot != null ? bot.getId() : null)
                .botFio(bot != null ? bot.getFio() : task.getBotFioSnapshot())
                .botLogin(bot != null ? bot.getLogin() : task.getBotLoginSnapshot())
                .botPassword(bot != null ? bot.getPassword() : task.getBotPasswordSnapshot())
                .botCounter(bot != null ? bot.getCounter() : 0)
                .companyTitle(task.getArchiveCompanyTitle())
                .commentCompany(task.getArchiveCompanyNote())
                .orderComments(task.getArchiveOrderNote())
                .filialCity(task.getArchiveFilialCity())
                .filialTitle(task.getArchiveFilialTitle())
                .filialUrl(task.getArchiveFilialUrl())
                .productId(task.getArchiveProductId())
                .productTitle(task.getArchiveProductTitle())
                .productPhoto(false)
                .workerFio(task.getWorker() != null && task.getWorker().getUser() != null
                        ? task.getWorker().getUser().getFio()
                        : "")
                .created(task.getArchiveReviewCreated())
                .changed(task.getArchiveReviewChanged())
                .publishedDate(task.getArchiveReviewPublishedDate())
                .publish(task.isArchiveReviewPublish())
                .vigul(task.isArchiveReviewVigul())
                .comment("")
                .price(task.getArchiveReviewPrice())
                .url(task.getArchiveReviewUrl())
                .urlPhoto(task.getArchiveReviewUrl())
                .build();
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

    private DailyWorkProgressResponse workerDailyProgress(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker
    ) {
        if (!staffDailyProgressService.progressEnabled()) {
            return null;
        }

        LocalDate today = LocalDate.now();
        if (selectedWorker != null) {
            return staffDailyProgressService.workerProgressSnapshotByWorkers(List.of(selectedWorker), today)
                    .get(selectedWorker.getId());
        }

        if (hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER") || hasRole(authentication, "MANAGER")) {
            return staffDailyProgressService.aggregateWorkerProgressSnapshot(
                    workerFilterWorkers(principal, authentication),
                    today
            );
        }

        if (hasRole(authentication, "WORKER")) {
            Worker worker = resolveWorker(principal);
            return worker == null
                    ? null
                    : staffDailyProgressService.workerProgressSnapshotByWorkers(List.of(worker), today).get(worker.getId());
        }

        return null;
    }

    private List<Worker> workerFilterWorkers(Principal principal, Authentication authentication) {
        try {
            return staffAccess.workerFilterWorkers(principal, authentication);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
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
        try {
            return staffAccess.resolveManager(principal);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    private Worker resolveWorker(Principal principal) {
        try {
            return staffAccess.resolveWorker(principal);
        } catch (WorkerOrderCommandException failure) {
            throw commandHttpFailure(failure);
        }
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return staffAccess.resolveOwnerManagers(principal);
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

    private Long workerId(Worker worker) {
        return worker == null ? null : worker.getId();
    }

    private WorkerFlowRedirect workerFlowRedirect(Principal principal, Authentication authentication, String requestedSection) {
        return workerPublicationGateService.redirectFor(principal, authentication, requestedSection)
                .map(block -> new WorkerFlowRedirect(block.section(), block.message()))
                .orElse(null);
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

        return "asc".equals(sortDirection) ? comparator.reversed() : comparator;
    }

    private Sort reviewSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("publishedDate").descending().and(Sort.by("id").descending())
                : Sort.by("publishedDate").ascending().and(Sort.by("id").ascending());
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

    private WorkerOrderActor currentOrderActor() {
        return WorkerOrderActor.from(SecurityContextHolder.getContext().getAuthentication());
    }

    private ResponseStatusException commandHttpFailure(WorkerOrderCommandException failure) {
        // Preserve domain-specific exception handlers, headers and response payloads after the domain proxy has completed.
        if (failure.getCause() instanceof ResponseStatusException legacyFailure) return legacyFailure;
        return new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(failure.statusCode()), failure.getMessage(), failure);
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

    private ResponseEntity<CredentialRevealResponse> noStore(CredentialRevealResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
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

    private PageResponse<WorkerReviewResponse> toReviewPageResponse(
            Page<ReviewDTOOne> page
    ) {
        return new PageResponse<>(
                page.getContent().stream()
                        .map(this::toReviewResponse)
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PageResponse<WorkerReviewResponse> toBadTaskPageResponse(
            Page<BadReviewTask> page
    ) {
        return new PageResponse<>(
                page.getContent().stream()
                        .map(this::toBadTaskReviewResponse)
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private PageResponse<WorkerReviewResponse> toRecoveryTaskPageResponse(
            Page<ReviewRecoveryTask> page
    ) {
        return new PageResponse<>(
                page.getContent().stream()
                        .map(this::toRecoveryTaskReviewResponse)
                        .toList(),
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

    private Long orderId(Review review) {
        Order order = review != null && review.getOrderDetails() != null
                ? review.getOrderDetails().getOrder()
                : null;
        return order != null ? order.getId() : null;
    }

    private Long orderId(BadReviewTask task) {
        Order order = task != null ? task.getOrder() : null;
        return order != null ? order.getId() : null;
    }

    private Long orderId(ReviewRecoveryTask task) {
        Order order = task != null ? task.getOrder() : null;
        return order != null ? order.getId() : task != null ? task.getArchiveOrderId() : null;
    }

    private Long reviewId(BadReviewTask task) {
        Review review = task != null ? task.getSourceReview() : null;
        return review != null ? review.getId() : null;
    }

    private Long reviewId(ReviewRecoveryTask task) {
        Review review = task != null ? task.getSourceReview() : null;
        return review != null ? review.getId() : task != null ? task.getArchiveReviewId() : null;
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
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
            boolean warning,
            WorkerCredentialPreparationResponse credentialPreparation,
            WorkerPublicationSessionService.SessionState publicationSession,
            DailyWorkProgressResponse dailyProgress,
            WorkerRiskAccessPolicy.Status accessRestriction
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
            boolean loginPresent,
            boolean passwordPresent,
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
            boolean botLoginPresent,
            boolean botPasswordPresent,
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
            String recoveryTaskCompletedDate,
            Long taskWorkerId
    ) {
    }

    public record StatusChangeRequest(String status) {
    }

    public record ClientWaitingRequest(Boolean waitingForClient) {
    }

    public record WorkerAssignmentRequest(Long workerId) {
    }

    public record WorkerActivitySourceRequest(String sourcePage, String sourceEntry, String sourceSection) {
    }

    public record ReviewCopyClickRequest(String field, String sourcePage, String sourceEntry, String sourceSection) {
        public ReviewCopyClickRequest(String field) {
            this(field, null, null, null);
        }
    }

    public record OrderNoteUpdateRequest(String orderComments) {
    }

    public record CompanyNoteUpdateRequest(String companyComments) {
    }

    public record ReviewTextUpdateRequest(
            Long orderId,
            String text,
            String sourcePage,
            String sourceEntry,
            String sourceSection
    ) {
        public ReviewTextUpdateRequest(Long orderId, String text) {
            this(orderId, text, null, null, null);
        }
    }

    public record ReviewBotNameUpdateRequest(String botName) {
    }

    public record BadTaskUpdateRequest(String taskText, LocalDate scheduledDate) {
    }

    public record RecoveryTaskUpdateRequest(String recoveryText, String recoveryAnswer, LocalDate scheduledDate) {
    }

    public record ReviewAnswerUpdateRequest(
            Long orderId,
            String answer,
            String sourcePage,
            String sourceEntry,
            String sourceSection
    ) {
        public ReviewAnswerUpdateRequest(Long orderId, String answer) {
            this(orderId, answer, null, null, null);
        }
    }

    public record ReviewNoteUpdateRequest(
            Long orderId,
            String comment,
            String sourcePage,
            String sourceEntry,
            String sourceSection
    ) {
        public ReviewNoteUpdateRequest(Long orderId, String comment) {
            this(orderId, comment, null, null, null);
        }
    }

    public record WorkerActionResponse(boolean success, String message) {
    }

    public record BotChangeResponse(Long oldBotId, Long newBotId) {
    }

    public record BotDeactivateResponse(Long blockedBotId, Long newBotId, boolean replacementFound) {
    }

    private record WorkerFlowRedirect(String section, String message) {
    }
}
