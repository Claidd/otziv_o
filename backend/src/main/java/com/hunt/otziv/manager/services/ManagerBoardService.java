package com.hunt.otziv.manager.services;

import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.l_lead.promo.PromoButtonCatalog;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.dto.api.ManagerBoardResponse;
import com.hunt.otziv.manager.dto.api.ManagerMetricResponse;
import com.hunt.otziv.manager.dto.api.ManagerOverdueOrdersResponse;
import com.hunt.otziv.manager.dto.api.ManagerOverdueStatusResponse;
import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ManagerBoardService {

    private static final String SECTION_COMPANIES = "companies";
    private static final String SECTION_ORDERS = "orders";
    private static final int MAX_PAGE_SIZE = 50;
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final String CONTROL_MANAGER_OVERDUE = "manager-overdue";
    private static final Set<String> OVERDUE_IGNORED_STATUSES = Set.of(
            "Оплачено",
            "Архив",
            "Публикация",
            "Не оплачено",
            "Бан"
    );
    private static final Set<String> PAYMENT_AUTOMATION_STATUSES = Set.of(
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );
    private static final Set<ClientMessageScenario> PAYMENT_AUTOMATION_SCENARIOS = Set.of(
            ClientMessageScenario.PAYMENT_INVOICE_RETRY,
            ClientMessageScenario.PAYMENT_REMINDER,
            ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION
    );
    private static final Set<String> REVIEW_CHECK_AUTOMATION_STATUSES = Set.of("На проверке");
    private static final Set<ClientMessageScenario> REVIEW_CHECK_SCENARIOS = Set.of(
            ClientMessageScenario.REVIEW_CHECK_REMINDER
    );
    private static final Set<String> DELIVERY_RETRY_AUTOMATION_STATUSES = Set.of("В проверку");
    private static final Set<ClientMessageScenario> DELIVERY_RETRY_SCENARIOS = Set.of(
            ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY
    );
    private static final Set<String> CLIENT_TEXT_AUTOMATION_STATUSES = Set.of("Новый");
    private static final Set<ClientMessageScenario> CLIENT_TEXT_SCENARIOS = Set.of(
            ClientMessageScenario.CLIENT_TEXT_REMINDER
    );
    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_CONTROL_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );

    private final CompanyService companyService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PromoTextService promoTextService;
    private final UserService userService;
    private final ManagerService managerService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ManagerPermissionService managerPermissionService;
    private final ManagerAccessService managerAccessService;
    private final UserMetricSnapshotService metricSnapshotService;
    private final CommonBillingService commonBillingService;
    private final ClientMessageOrderStatusService clientMessageOrderStatusService;

    public ManagerBoardResponse getBoard(
            String section,
            String status,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            Long companyId,
            Principal principal,
            Authentication authentication
    ) {
        return getBoard(
                section,
                status,
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                companyId,
                null,
                "",
                principal,
                authentication
        );
    }

    public ManagerBoardResponse getBoard(
            String section,
            String status,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            Long companyId,
            Long managerId,
            String control,
            Principal principal,
            Authentication authentication
    ) {
        String normalizedSection = normalizeSection(section);
        String normalizedStatus = normalizeStatus(status);
        String normalizedSortDirection = normalizeSortDirection(sortDirection);
        int safePageNumber = Math.max(pageNumber, 0);
        int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        Manager managerFilter = resolveManagerFilter(managerId, principal, authentication);
        boolean managerControlOverdue = CONTROL_MANAGER_OVERDUE.equalsIgnoreCase(control == null ? "" : control.trim());

        Page<CompanyListDTO> companies = SECTION_COMPANIES.equals(normalizedSection)
                ? loadCompanies(principal, authentication, trimmedKeyword, normalizedStatus, safePageNumber, safePageSize, normalizedSortDirection)
                : emptyCompanyPage(safePageNumber, safePageSize);

        Page<OrderDTOList> orders = SECTION_ORDERS.equals(normalizedSection)
                ? loadOrders(principal, authentication, trimmedKeyword, normalizedStatus, safePageNumber, safePageSize, companyId, managerFilter, managerControlOverdue, normalizedSortDirection)
                : emptyOrderPage(safePageNumber, safePageSize);
        badReviewTaskService.enrichOrderList(orders.getContent());
        clientMessageOrderStatusService.enrichOrderList(orders.getContent());

        return new ManagerBoardResponse(
                normalizedSection,
                normalizedStatus,
                toPageResponse(companies),
                toPageResponse(orders),
                ManagerBoardStatusCatalog.companyStatuses(),
                ManagerBoardStatusCatalog.orderStatuses(),
                buildMetrics(principal, authentication, managerFilter, managerControlOverdue),
                promoTextService.getPromoTextsForManager(
                        resolvePromoManagerId(principal, authentication),
                        promoSectionCode(normalizedSection)
                )
        );
    }

    public ManagerOverdueOrdersResponse getOverdueOrders(
            Principal principal,
            Authentication authentication
    ) {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);

        List<Object[]> summaryRows;

        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            summaryRows = orderRepository.summarizeOverdueOrders(cutoff, OVERDUE_IGNORED_STATUSES);
        } else if (managerPermissionService.hasRole(authentication, "OWNER")) {
            Set<Manager> managers = resolveOwnerManagers(principal);
            if (managers.isEmpty()) {
                summaryRows = Collections.emptyList();
            } else {
                summaryRows = orderRepository.summarizeOverdueOrdersByManagers(managers, cutoff, OVERDUE_IGNORED_STATUSES);
            }
        } else {
            Manager manager = resolveManager(principal);
            summaryRows = orderRepository.summarizeOverdueOrdersByManager(manager, cutoff, OVERDUE_IGNORED_STATUSES);
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
    }

    private Page<CompanyListDTO> loadCompanies(
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return "Все".equals(status)
                    ? companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize, sortDirection)
                    : companyService.getAllCompaniesDTOListToList(keyword, status, pageNumber, pageSize, sortDirection);
        }

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return "Все".equals(status)
                    ? companyService.getAllCompaniesDTOListOwner(principal, keyword, pageNumber, pageSize, sortDirection)
                    : companyService.getAllCompaniesDtoToOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? companyService.getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize, sortDirection)
                : companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    private Page<OrderDTOList> loadOrders(
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            Long companyId,
            Manager managerFilter,
            boolean managerControlOverdue,
            String sortDirection
    ) {
        if (companyId != null) {
            managerAccessService.requireCompanyAccess(companyId, authentication);
        }
        if (managerControlOverdue && managerFilter != null) {
            return loadRawOrders(
                    principal,
                    authentication,
                    keyword,
                    status,
                    pageNumber,
                    pageSize,
                    companyId,
                    managerFilter,
                    true,
                    sortDirection
            );
        }
        Set<Long> visibleManagerIds = filteredVisibleManagerIds(principal, authentication, managerFilter);
        List<OrderDTOList> commonCards = commonBillingService.managerBoardCards(
                status,
                keyword,
                companyId,
                visibleManagerIds,
                sortDirection
        );

        int pageStart = pageNumber * pageSize;
        List<OrderDTOList> visibleCommonCards = commonCards.stream()
                .skip(pageStart)
                .limit(pageSize)
                .toList();
        int ordinaryLimit = Math.max(0, pageSize - visibleCommonCards.size());
        long ordinaryOffset = Math.max(0L, (long) pageStart - commonCards.size());
        int ordinaryPageNumber = (int) (ordinaryOffset / pageSize);
        int ordinarySkip = (int) (ordinaryOffset % pageSize);

        Page<OrderDTOList> firstOrdinaryPage = loadRawOrders(
                principal,
                authentication,
                keyword,
                status,
                ordinaryPageNumber,
                pageSize,
                companyId,
                managerFilter,
                false,
                sortDirection
        );

        List<OrderDTOList> content = new ArrayList<>(visibleCommonCards);
        content.addAll(collectOrdinaryWindow(
                firstOrdinaryPage,
                ordinarySkip,
                ordinaryLimit,
                principal,
                authentication,
                keyword,
                status,
                companyId,
                managerFilter,
                false,
                sortDirection
        ));

        int linkedTotal = commonBillingService.countLinkedBoardOrdersMatching(status, keyword, companyId, visibleManagerIds);
        long total = Math.max(0, firstOrdinaryPage.getTotalElements() - linkedTotal) + commonCards.size();
        return new PageImpl<>(content, PageRequest.of(pageNumber, pageSize), total);
    }

    private Page<OrderDTOList> loadRawOrders(
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            Long companyId,
            Manager managerFilter,
            boolean managerControlOverdue,
            String sortDirection
    ) {
        if (companyId != null) {
            managerAccessService.requireCompanyAccess(companyId, authentication);
            return orderService.getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize, sortDirection);
        } else if (managerControlOverdue && managerFilter != null) {
            return orderService.getManagerControlOverdueOrdersByManager(
                    managerFilter,
                    keyword,
                    status,
                    LocalDate.now().minusDays(OVERDUE_NOTIFICATION_DAYS + 1L),
                    OVERDUE_IGNORED_STATUSES,
                    COMMON_INVOICE_CONTROL_STATUSES,
                    PAYMENT_AUTOMATION_STATUSES,
                    PAYMENT_AUTOMATION_SCENARIOS,
                    REVIEW_CHECK_AUTOMATION_STATUSES,
                    REVIEW_CHECK_SCENARIOS,
                    DELIVERY_RETRY_AUTOMATION_STATUSES,
                    DELIVERY_RETRY_SCENARIOS,
                    CLIENT_TEXT_AUTOMATION_STATUSES,
                    CLIENT_TEXT_SCENARIOS,
                    ScheduledMessageStateStatus.ACTIVE,
                    ScheduledMessageStateStatus.DONE,
                    pageNumber,
                    pageSize,
                    sortDirection
            );
        } else if (managerFilter != null) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeywordByManagerAll(managerFilter, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByManager(managerFilter, keyword, status, pageNumber, pageSize, sortDirection);
        } else if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize, sortDirection);
        } else if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? orderService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                : orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    private List<ManagerMetricResponse> buildMetrics(
            Principal principal,
            Authentication authentication,
            Manager managerFilter,
            boolean managerControlOverdue
    ) {
        List<ManagerMetricResponse> metrics = new ArrayList<>();
        Map<String, Integer> companyCounts = countCompanyMetrics(principal, authentication);
        Map<String, Integer> orderCounts = managerControlOverdue && managerFilter != null
                ? countManagerControlOverdueMetrics(managerFilter)
                : countOrderMetrics(principal, authentication, managerFilter);

        metrics.add(companyMetric(companyCounts, "Новые", "Новая", "fiber_new", "yellow"));
        metrics.add(companyMetric(companyCounts, "В работе", "В работе", "badge", "green"));
        metrics.add(companyMetric(companyCounts, "Новый заказ", "Новый заказ", "business_center", "teal"));
        metrics.add(companyMetric(companyCounts, "К рассылке", "К рассылке", "campaign", "blue"));
        metrics.add(companyMetric(companyCounts, "Ожидание", "Ожидание", "hourglass_top", "pink"));
        metrics.add(companyMetric(companyCounts, "На стопе", "На стопе", "pause_circle", "gray"));
        metrics.add(companyMetric(companyCounts, "Бан", "Бан", "block", "gray"));
        metrics.add(companyMetric(companyCounts, "Все", "Все", "dashboard", "blue"));

        metrics.add(orderMetric(orderCounts, "Новые", "Новый", "fiber_new", "yellow"));
        metrics.add(orderMetric(orderCounts, "В проверку", "В проверку", "fact_check", "blue"));
        metrics.add(orderMetric(orderCounts, "На проверке", "На проверке", "manage_search", "teal"));
        metrics.add(orderMetric(orderCounts, "Коррекция", "Коррекция", "build_circle", "pink"));
        metrics.add(orderMetric(orderCounts, "Публикация", "Публикация", "published_with_changes", "green"));
        metrics.add(orderMetric(orderCounts, "Опубликовано", "Опубликовано", "task_alt", "green"));
        metrics.add(orderMetric(orderCounts, "Выставлен счет", "Выставлен счет", "receipt_long", "blue"));
        metrics.add(orderMetric(orderCounts, "Напоминание", "Напоминание", "notifications_active", "pink"));
        metrics.add(orderMetric(orderCounts, "Требует внимания", "Требует внимания", "error", "yellow"));
        metrics.add(orderMetric(orderCounts, "Не оплачено", "Не оплачено", "money_off", "gray"));
        metrics.add(orderMetric(orderCounts, "Бан", "Бан", "block", "gray"));
        metrics.add(recoveryReadyMetric(principal, authentication, managerFilter));
        metrics.add(orderMetric(orderCounts, "Все", "Все", "dashboard", "blue"));

        Map<String, Integer> deltas = metricSnapshotService.deltas(
                principal,
                UserMetricSnapshotService.PAGE_MANAGER,
                metrics.stream()
                        .map(metric -> new UserMetricSnapshotService.MetricValue(
                                metric.section(),
                                metric.status(),
                                metric.value()
                        ))
                        .toList()
        );

        return metrics.stream()
                .map(metric -> metric.withDelta(deltas.getOrDefault(
                        UserMetricSnapshotService.key(metric.section(), metric.status()),
                        0
                )))
                .toList();
    }

    private ManagerMetricResponse companyMetric(
            Map<String, Integer> counts,
            String label,
            String status,
            String icon,
            String tone
    ) {
        return new ManagerMetricResponse(
                label,
                countStatus(counts, status),
                icon,
                tone,
                SECTION_COMPANIES,
                status
        );
    }

    private ManagerMetricResponse orderMetric(
            Map<String, Integer> counts,
            String label,
            String status,
            String icon,
            String tone
    ) {
        return new ManagerMetricResponse(
                label,
                countStatus(counts, status),
                icon,
                tone,
                SECTION_ORDERS,
                status
        );
    }

    private ManagerMetricResponse recoveryReadyMetric(Principal principal, Authentication authentication, Manager managerFilter) {
        int count;
        if (managerFilter != null) {
            count = reviewRecoveryTaskService.countCompletedBatchesToManager(managerFilter);
        } else if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            count = reviewRecoveryTaskService.countCompletedBatchesToAdmin();
        } else if (managerPermissionService.hasRole(authentication, "OWNER")) {
            count = reviewRecoveryTaskService.countCompletedBatchesToOwner(resolveOwnerManagers(principal));
        } else {
            count = reviewRecoveryTaskService.countCompletedBatchesToManager(resolveManager(principal));
        }

        return new ManagerMetricResponse(
                "Восст. готовы",
                count,
                "restore",
                "yellow",
                SECTION_ORDERS,
                "Восстановления готовы"
        );
    }

    private Map<String, Integer> countCompanyMetrics(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return companyService.countCompaniesByStatus();
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return companyService.countCompaniesByStatusToOwner(resolveOwnerManagers(principal));
        }
        return companyService.countCompaniesByStatusToManager(resolveManager(principal));
    }

    private Map<String, Integer> countOrderMetrics(Principal principal, Authentication authentication, Manager managerFilter) {
        Map<String, Integer> counts;
        if (managerFilter != null) {
            counts = orderService.countOrdersByStatusToManager(managerFilter);
        } else if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            counts = orderService.countOrdersByStatus();
        } else if (managerPermissionService.hasRole(authentication, "OWNER")) {
            counts = orderService.countOrdersByStatusToOwner(resolveOwnerManagers(principal));
        } else {
            counts = orderService.countOrdersByStatusToManager(resolveManager(principal));
        }

        Set<Long> visibleManagerIds = filteredVisibleManagerIds(principal, authentication, managerFilter);
        Map<String, Integer> merged = new java.util.HashMap<>(counts == null ? Map.of() : counts);
        commonBillingService.countLinkedManagerBoardOrders(visibleManagerIds)
                .forEach((status, count) -> merged.computeIfPresent(status, (_status, value) -> Math.max(0, value - count)));
        commonBillingService.countManagerBoardCards(visibleManagerIds)
                .forEach((status, count) -> merged.merge(status, count, Integer::sum));
        return merged;
    }

    private Map<String, Integer> countManagerControlOverdueMetrics(Manager manager) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        LocalDate cutoff = LocalDate.now().minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
        orderRepository.summarizeManagerControlOverdueOrdersByManager(
                        manager,
                        cutoff,
                        OVERDUE_IGNORED_STATUSES,
                        COMMON_INVOICE_CONTROL_STATUSES,
                        PAYMENT_AUTOMATION_STATUSES,
                        PAYMENT_AUTOMATION_SCENARIOS,
                        REVIEW_CHECK_AUTOMATION_STATUSES,
                        REVIEW_CHECK_SCENARIOS,
                        DELIVERY_RETRY_AUTOMATION_STATUSES,
                        DELIVERY_RETRY_SCENARIOS,
                        CLIENT_TEXT_AUTOMATION_STATUSES,
                        CLIENT_TEXT_SCENARIOS,
                        ScheduledMessageStateStatus.ACTIVE,
                        ScheduledMessageStateStatus.DONE
                )
                .forEach(row -> counts.put(rowString(row, 0, "Без статуса"), safeInt(rowLong(row, 1))));
        return counts;
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

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private Manager resolveManager(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return managerService.getManagerByUserId(user.getId());
    }

    private Long resolvePromoManagerId(Principal principal, Authentication authentication) {
        if (principal == null || !managerPermissionService.hasRole(authentication, "MANAGER")) {
            return null;
        }

        return userService.findByUserName(principal.getName())
                .map(User::getId)
                .map(managerService::getManagerByUserId)
                .map(Manager::getId)
                .orElse(null);
    }

    private String promoSectionCode(String section) {
        return SECTION_ORDERS.equals(section)
                ? PromoButtonCatalog.SECTION_MANAGER_ORDERS
                : PromoButtonCatalog.SECTION_MANAGER_COMPANIES;
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return userService.findManagersByUserName(principal.getName());
    }

    private List<OrderDTOList> collectOrdinaryWindow(
            Page<OrderDTOList> firstPage,
            int skipOnFirstPage,
            int limit,
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            Long companyId,
            Manager managerFilter,
            boolean managerControlOverdue,
            String sortDirection
    ) {
        if (limit <= 0) {
            return List.of();
        }
        List<OrderDTOList> content = new ArrayList<>(limit);
        Page<OrderDTOList> page = firstPage;
        int skip = Math.max(0, skipOnFirstPage);
        while (page != null && content.size() < limit) {
            Set<Long> linkedOrderIds = commonBillingService.linkedBoardOrderIds(
                    page.getContent().stream()
                            .map(OrderDTOList::getId)
                            .filter(id -> id != null && id > 0)
                            .toList()
            );
            page.getContent().stream()
                    .skip(skip)
                    .filter(order -> order.getId() == null || !linkedOrderIds.contains(order.getId()))
                    .limit(limit - content.size())
                    .forEach(content::add);
            if (content.size() >= limit || !page.hasNext()) {
                break;
            }
            page = loadRawOrders(
                    principal,
                    authentication,
                    keyword,
                    status,
                    page.getNumber() + 1,
                    page.getSize(),
                    companyId,
                    managerFilter,
                    managerControlOverdue,
                    sortDirection
            );
            skip = 0;
        }
        return content;
    }

    private Set<Long> visibleManagerIds(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return null;
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return resolveOwnerManagers(principal).stream()
                    .map(Manager::getId)
                    .collect(java.util.stream.Collectors.toSet());
        }
        Manager manager = resolveManager(principal);
        return manager == null || manager.getId() == null ? Set.of() : Set.of(manager.getId());
    }

    private Set<Long> filteredVisibleManagerIds(Principal principal, Authentication authentication, Manager managerFilter) {
        if (managerFilter == null) {
            return visibleManagerIds(principal, authentication);
        }
        return Set.of(managerFilter.getId());
    }

    private Manager resolveManagerFilter(Long managerId, Principal principal, Authentication authentication) {
        if (managerId == null) {
            return null;
        }
        if (managerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
        }

        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            Manager manager = managerService.getManagerById(managerId);
            if (manager == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
            }
            return manager;
        }

        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return resolveOwnerManagers(principal).stream()
                    .filter(manager -> managerId.equals(manager.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен"));
        }

        Manager ownManager = resolveManager(principal);
        if (ownManager == null || !managerId.equals(ownManager.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер недоступен");
        }
        return ownManager;
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "Все" : status.trim();
    }

    private String normalizeSection(String section) {
        String normalized = section == null ? SECTION_COMPANIES : section.toLowerCase(Locale.ROOT).trim();
        return SECTION_ORDERS.equals(normalized) ? SECTION_ORDERS : SECTION_COMPANIES;
    }

    private String normalizeSortDirection(String sortDirection) {
        return "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
    }

    private Page<CompanyListDTO> emptyCompanyPage(int pageNumber, int pageSize) {
        return new PageImpl<>(List.of(), PageRequest.of(pageNumber, pageSize), 0);
    }

    private Page<OrderDTOList> emptyOrderPage(int pageNumber, int pageSize) {
        return new PageImpl<>(List.of(), PageRequest.of(pageNumber, pageSize), 0);
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
}
