package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import com.hunt.otziv.archive.dto.ArchiveRestoreResult;
import com.hunt.otziv.archive.service.OrderArchiveRestoreService;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveListItem;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveOrderItem;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveRestoreResult;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.repository.CommonInvoiceArchiveRepository;
import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.status.policy.OrderManualArchivePolicy;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CommonInvoiceArchiveService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommonInvoiceArchiveRepository repository;
    private final CommonBillingService commonBillingService;
    private final OrderArchiveRestoreService orderArchiveRestoreService;
    private final ManagerPermissionService managerPermissionService;
    private final UserService userService;
    private final ManagerService managerService;

    @Transactional(readOnly = true)
    public PageResponse<CommonInvoiceArchiveListItem> find(
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            Principal principal,
            Authentication authentication
    ) {
        ArchiveAccessScope scope = resolveScope(principal, authentication);
        int safePage = Math.max(0, pageNumber);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        long total = repository.count(scope, keyword);
        List<CommonInvoiceArchiveListItem> content =
                repository.find(scope, keyword, safePage, safeSize, sortDirection);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        return new PageResponse<>(
                content,
                safePage,
                safeSize,
                total,
                pages,
                safePage == 0,
                total == 0 || safePage + 1 >= pages
        );
    }

    @Transactional(readOnly = true)
    public CommonInvoiceArchiveDetailsResponse details(
            Long invoiceId,
            Principal principal,
            Authentication authentication
    ) {
        CommonInvoiceArchiveListItem invoice = visibleInvoice(invoiceId, principal, authentication);
        return new CommonInvoiceArchiveDetailsResponse(
                invoice,
                repository.findOrders(invoiceId, invoice.source())
        );
    }

    @Transactional
    public CommonInvoiceArchiveRestoreResult restore(
            Long invoiceId,
            boolean confirm,
            Principal principal,
            Authentication authentication
    ) {
        if (!confirm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Восстановление требует confirm=true");
        }
        CommonInvoiceArchiveListItem invoice = visibleInvoice(invoiceId, principal, authentication);
        if (!invoice.restorable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот общий счет нельзя восстановить");
        }
        String actor = principal == null || principal.getName() == null
                ? "unknown"
                : principal.getName().trim();

        if ("live".equals(invoice.source())) {
            CommonInvoiceDetailsResponse restored =
                    commonBillingService.restoreLiveArchivedInvoice(invoiceId, principal);
            List<Long> orderIds = restored.orders().stream().map(order -> order.orderId()).toList();
            return new CommonInvoiceArchiveRestoreResult(
                    invoiceId,
                    restored.summary().status(),
                    "live",
                    LocalDateTime.now(),
                    actor,
                    orderIds,
                    "Общий счет и все его заказы восстановлены в live"
            );
        }

        List<Long> chainInvoiceIds = repository.unrestoredSupersessionChain(invoiceId);
        if (chainInvoiceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Цикл общего счета уже восстановлен");
        }
        Map<Long, String> archivedStatuses = new LinkedHashMap<>();
        Map<Long, CommonInvoiceArchiveOrderItem> ordersById = new LinkedHashMap<>();
        for (Long chainInvoiceId : chainInvoiceIds) {
            if (!repository.lockAndCheckPaymentRefsRestorable(chainInvoiceId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Цикл общего счета содержит незавершенную платежную операцию"
                );
            }
            archivedStatuses.put(chainInvoiceId, repository.archivedStatus(chainInvoiceId));
            repository.findOrders(chainInvoiceId, "archive")
                    .forEach(order -> ordersById.putIfAbsent(order.orderId(), order));
        }
        List<CommonInvoiceArchiveOrderItem> orders = new ArrayList<>(ordersById.values());
        if (orders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В архивном общем счете нет заказов");
        }
        List<ArchiveRestoreResult> restoredOrders = new ArrayList<>();
        for (CommonInvoiceArchiveOrderItem order : orders) {
            if (repository.isOrderAlreadyRestored(order.orderId())) {
                continue;
            }
            restoredOrders.add(orderArchiveRestoreService.restoreOrder(
                    order.orderId(),
                    restoreArchivedOrderTarget(order),
                    actor,
                    true
            ));
        }

        Long restoreBatchId = restoredOrders.stream()
                .map(ArchiveRestoreResult::batchId)
                .findFirst()
                .orElse(null);
        for (Long chainInvoiceId : chainInvoiceIds) {
            repository.restoreInvoice(chainInvoiceId, randomToken(), actor, restoreBatchId);
            if ("ARCHIVED".equals(archivedStatuses.get(chainInvoiceId))) {
                repository.reopenRestoredManualInvoice(chainInvoiceId);
            } else {
                repository.refreshRestoredClosedRetention(chainInvoiceId, actor);
            }
        }
        String archivedStatus = archivedStatuses.get(invoiceId);
        return new CommonInvoiceArchiveRestoreResult(
                invoiceId,
                "ARCHIVED".equals(archivedStatus) ? "COLLECTING" : archivedStatus,
                "archive",
                LocalDateTime.now(),
                actor,
                orders.stream().map(CommonInvoiceArchiveOrderItem::orderId).toList(),
                "Цепочка общего счета и ее заказы восстановлены из архива"
        );
    }

    private String restoreArchivedOrderTarget(CommonInvoiceArchiveOrderItem order) {
        String archivedOrderStatus = order.status() == null ? "" : order.status().trim();
        if ("Оплачено".equals(archivedOrderStatus) || "Бан".equals(archivedOrderStatus)) {
            return archivedOrderStatus;
        }
        return restoreTarget(order.archiveSourceStatus());
    }

    private CommonInvoiceArchiveListItem visibleInvoice(
            Long invoiceId,
            Principal principal,
            Authentication authentication
    ) {
        if (invoiceId == null || invoiceId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Номер общего счета обязателен");
        }
        return repository.findOne(resolveScope(principal, authentication), invoiceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Архивный общий счет не найден"
                ));
    }

    private ArchiveAccessScope resolveScope(Principal principal, Authentication authentication) {
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return ArchiveAccessScope.all();
        }
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Пользователь не определен");
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            Set<Long> managerIds = userService.findManagersByUserName(principal.getName()).stream()
                    .map(Manager::getId)
                    .collect(Collectors.toSet());
            return ArchiveAccessScope.managers(managerIds);
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            User user = userService.findByUserName(principal.getName())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Пользователь не найден"
                    ));
            Manager manager = managerService.getManagerByUserId(user.getId());
            return ArchiveAccessScope.managers(
                    manager == null || manager.getId() == null ? Set.of() : Set.of(manager.getId())
            );
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к архиву");
    }

    private String restoreTarget(String sourceStatus) {
        String normalized = sourceStatus == null ? "" : sourceStatus.trim();
        return OrderManualArchivePolicy.ALLOWED_SOURCE_STATUSES.contains(normalized)
                ? normalized
                : "В проверку";
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
