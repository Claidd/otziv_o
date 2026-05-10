package com.hunt.otziv.archive;

import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ManagerArchiveService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ManagerArchiveRepository repository;
    private final OrderArchiveRestoreService restoreService;
    private final ManagerPermissionService managerPermissionService;
    private final UserService userService;
    private final ManagerService managerService;

    @Transactional(readOnly = true)
    public PageResponse<ManagerArchiveOrderListItem> findOrders(
            String keyword,
            String mode,
            int pageNumber,
            int pageSize,
            Principal principal,
            Authentication authentication
    ) {
        ArchiveAccessScope scope = resolveScope(principal, authentication);
        int safePageNumber = Math.max(pageNumber, 0);
        int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        String safeKeyword = keyword == null ? "" : keyword.trim();
        long total = repository.countOrders(scope, mode, safeKeyword);
        List<ManagerArchiveOrderListItem> orders = repository.findOrders(scope, mode, safeKeyword, safePageNumber, safePageSize);

        return new PageResponse<>(
                orders,
                safePageNumber,
                safePageSize,
                total,
                totalPages(total, safePageSize),
                safePageNumber == 0,
                total == 0 || safePageNumber + 1 >= totalPages(total, safePageSize)
        );
    }

    @Transactional(readOnly = true)
    public ManagerArchiveOrderDetailsResponse getOrder(
            Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order id is required");
        }

        ArchiveAccessScope scope = resolveScope(principal, authentication);
        ManagerArchiveOrderListItem order = repository.findOrder(scope, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Архивный заказ не найден"));

        return new ManagerArchiveOrderDetailsResponse(
                order,
                repository.findOrderComments(orderId),
                repository.findOrderDetails(orderId),
                repository.findReviews(orderId),
                repository.findBadReviewTasks(orderId),
                repository.findNextOrderRequests(orderId),
                repository.findZp(orderId),
                repository.findPaymentChecks(orderId)
        );
    }

    @Transactional
    public ArchiveRestoreResult restoreOrder(
            Long orderId,
            String targetStatus,
            boolean confirm,
            Principal principal,
            Authentication authentication
    ) {
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order id is required");
        }

        ArchiveAccessScope scope = resolveScope(principal, authentication);
        repository.findOrder(scope, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Архивный заказ не найден"));

        String restoredBy = principal == null ? null : principal.getName();
        return restoreService.restoreOrder(orderId, targetStatus, restoredBy, confirm);
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
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
            Manager manager = managerService.getManagerByUserId(user.getId());
            if (manager == null || manager.getId() == null) {
                return ArchiveAccessScope.managers(Set.of());
            }
            return ArchiveAccessScope.managers(Set.of(manager.getId()));
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к архиву");
    }

    private int totalPages(long total, int pageSize) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }
}
