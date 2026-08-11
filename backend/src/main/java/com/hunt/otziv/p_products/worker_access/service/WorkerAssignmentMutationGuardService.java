package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkerAssignmentMutationGuardService {

    private final WorkerAssignmentMutationGuardRepository repository;
    private final ManagerAccessService managerAccessService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    public void assertOrder(long orderId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        lockOrderWhenTransactional(orderId, authentication);
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            managerAccessService.requireOrderAccess(orderId, authentication);
            return;
        }
        assertOwned(repository.countOwnedOrder(orderId, authentication.getName()), authentication);
    }

    public void assertReview(long reviewId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Long orderId = resolveCanonicalOrder(
                reviewId,
                repository::findOrderIdByReviewId,
                authentication
        );
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            requireManagerialOrderAccess(orderId, authentication);
            return;
        }
        assertOwned(repository.countOwnedReview(reviewId, authentication.getName()), authentication);
    }

    public void assertBadTask(long taskId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Long orderId = resolveCanonicalOrder(
                taskId,
                repository::findOrderIdByBadTaskId,
                authentication
        );
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            requireManagerialOrderAccess(orderId, authentication);
            return;
        }
        assertOwned(repository.countOwnedBadTask(taskId, authentication.getName()), authentication);
    }

    public void assertRecoveryTask(long taskId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Optional<Long> liveOrderId = repository.findOrderIdByRecoveryTaskId(taskId);
        if (liveOrderId.isPresent()) {
            Long lockedOrderId = resolveCanonicalOrder(
                    taskId,
                    repository::findOrderIdByRecoveryTaskId,
                    authentication,
                    liveOrderId.get()
            );
            if (isAdministrator(authentication)) {
                return;
            }
            if (isManagerial(authentication)) {
                requireManagerialOrderAccess(lockedOrderId, authentication);
                return;
            }
            assertOwned(repository.countOwnedRecoveryTask(taskId, authentication.getName()), authentication);
            return;
        }
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            boolean managerAllowed = repository.findManagerIdByRecoveryTaskId(taskId)
                    .filter(managerId -> managerAccessService.canAccessManager(managerId, authentication))
                    .isPresent();
            if (!managerAllowed) {
                throw notFound();
            }
            return;
        }
        boolean owned = TransactionSynchronizationManager.isActualTransactionActive()
                ? repository.lockOwnedRecoveryTask(taskId, authentication.getName()).isPresent()
                : repository.countOwnedRecoveryTask(taskId, authentication.getName()) == 1L;
        assertOwned(owned ? 1L : 0L, authentication);
    }

    private void assertOwned(long ownedCount, Authentication authentication) {
        if (!isPlainWorker(authentication)) {
            return;
        }
        if (authentication.getName() != null
                && !authentication.getName().isBlank()
                && ownedCount == 1L) {
            return;
        }
        throw staleAssignment();
    }

    private Long resolveCanonicalOrder(
            long entityId,
            Function<Long, Optional<Long>> orderIdQuery,
            Authentication authentication
    ) {
        Long candidateOrderId = orderIdQuery.apply(entityId)
                .orElseThrow(() -> rejectedObject(authentication));
        return resolveCanonicalOrder(entityId, orderIdQuery, authentication, candidateOrderId);
    }

    private Long resolveCanonicalOrder(
            long entityId,
            Function<Long, Optional<Long>> orderIdQuery,
            Authentication authentication,
            Long candidateOrderId
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return candidateOrderId;
        }
        lockOrderWhenTransactional(candidateOrderId, authentication);
        Long currentOrderId = orderIdQuery.apply(entityId)
                .orElseThrow(() -> rejectedObject(authentication));
        if (!Objects.equals(candidateOrderId, currentOrderId)) {
            throw rejectedObject(authentication);
        }
        return currentOrderId;
    }

    private void requireManagerialOrderAccess(Long orderId, Authentication authentication) {
        if (!managerAccessService.canAccessOrder(orderId, authentication)) {
            throw notFound();
        }
    }

    private void lockOrderWhenTransactional(long orderId, Authentication authentication) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try {
            orderAggregateMutationLockService.lock(orderId);
        } catch (ResponseStatusException exception) {
            if (isPlainWorker(authentication) && exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw staleAssignment();
            }
            throw exception;
        }
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private boolean isAdministrator(Authentication authentication) {
        return hasRole(authentication, "ROLE_ADMIN");
    }

    private boolean isManagerial(Authentication authentication) {
        return hasRole(authentication, "ROLE_OWNER") || hasRole(authentication, "ROLE_MANAGER");
    }

    private boolean hasRole(Authentication authentication, String expectedRole) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> role != null)
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(expectedRole::equals);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Объект не найден");
    }

    private ResponseStatusException rejectedObject(Authentication authentication) {
        return isPlainWorker(authentication) ? staleAssignment() : notFound();
    }

    private ResponseStatusException staleAssignment() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Действие не выполнено: назначение или состояние объекта изменилось. "
                        + "Обновите страницу и повторите попытку."
        );
    }

    private boolean guardedStaff(Authentication authentication) {
        return isAdministrator(authentication)
                || isManagerial(authentication)
                || isPlainWorker(authentication);
    }

    private boolean isPlainWorker(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean worker = false;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority() == null
                    ? ""
                    : authority.getAuthority().toUpperCase(Locale.ROOT);
            if ("ROLE_ADMIN".equals(role)
                    || "ROLE_OWNER".equals(role)
                    || "ROLE_MANAGER".equals(role)) {
                return false;
            }
            if ("ROLE_WORKER".equals(role)) {
                worker = true;
            }
        }
        return worker;
    }
}
