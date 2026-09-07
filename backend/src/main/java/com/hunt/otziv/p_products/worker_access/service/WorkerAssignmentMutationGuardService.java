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
        assertOrder(orderId, authentication);
    }

    /** Explicit application boundary: absent/untrusted actor never inherits a thread-local exemption. */
    public void assertOrder(long orderId, Authentication authentication) {
        requireStaff(authentication);
        lockOrderWhenTransactional(orderId, authentication);
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) requireManagerialOrderAccess(orderId, authentication);
            else managerAccessService.requireOrderAccess(orderId, authentication);
            return;
        }
        // A normal COUNT can reuse an earlier InnoDB REPEATABLE READ snapshot.
        // Read current ownership only after the canonical Order lock has been acquired.
        boolean owned = TransactionSynchronizationManager.isActualTransactionActive()
                ? repository.lockOwnedOrder(orderId, authentication.getName()).isPresent()
                : repository.countOwnedOrder(orderId, authentication.getName()) == 1L;
        assertOwned(owned ? 1L : 0L, authentication);
    }

    /** Returns the current relationship only after the actor check and canonical parent/child locks. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public long requireReviewOrder(long reviewId,Authentication authentication) {
        assertReview(reviewId,authentication);
        return repository.findCurrentOrderIdByReviewId(reviewId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,"Отзыв не найден"));
    }

    public void assertReview(long reviewId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        assertReview(reviewId, authentication);
    }

    public void assertReview(long reviewId, Authentication authentication) {
        requireStaff(authentication);
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Long orderId = resolveCanonicalOrder(
                reviewId,
                repository::findOrderIdByReviewId,
                repository::findCurrentOrderIdByReviewId,
                authentication
        );
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            requireManagerialOrderAccess(orderId, authentication);
            return;
        }
        // A normal COUNT can reuse an earlier InnoDB REPEATABLE READ snapshot.
        // Read current ownership only after the canonical Order lock has been acquired.
        boolean owned = TransactionSynchronizationManager.isActualTransactionActive()
                ? repository.lockOwnedReview(reviewId, authentication.getName()).isPresent()
                : repository.countOwnedReview(reviewId, authentication.getName()) == 1L;
        assertOwned(owned ? 1L : 0L, authentication);
    }

    public void assertBadTask(long taskId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        assertBadTask(taskId, authentication);
    }

    public void assertBadTask(long taskId, Authentication authentication) {
        requireStaff(authentication);
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Long orderId = resolveCanonicalOrder(
                taskId,
                repository::findOrderIdByBadTaskId,
                repository::findCurrentOrderIdByBadTaskId,
                authentication
        );
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            requireManagerialOrderAccess(orderId, authentication);
            return;
        }
        // A normal COUNT can reuse an earlier InnoDB REPEATABLE READ snapshot.
        // Read current ownership only after the canonical Order lock has been acquired.
        boolean owned = TransactionSynchronizationManager.isActualTransactionActive()
                ? repository.lockOwnedBadTask(taskId, authentication.getName()).isPresent()
                : repository.countOwnedBadTask(taskId, authentication.getName()) == 1L;
        assertOwned(owned ? 1L : 0L, authentication);
    }

    public void assertRecoveryTask(long taskId) {
        Authentication authentication = currentAuthentication();
        if (!guardedStaff(authentication)) {
            return;
        }
        assertRecoveryTask(taskId, authentication);
    }

    public void assertRecoveryTask(long taskId, Authentication authentication) {
        requireStaff(authentication);
        if (isAdministrator(authentication)
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Optional<Long> liveOrderId = repository.findOrderIdByRecoveryTaskId(taskId);
        if (liveOrderId.isPresent()) {
            Long lockedOrderId = resolveCanonicalOrder(
                    taskId,
                    repository::findOrderIdByRecoveryTaskId,
                    repository::findCurrentOrderIdByRecoveryTaskId,
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
            // A normal COUNT can reuse an earlier InnoDB REPEATABLE READ snapshot.
            // Read current ownership only after the canonical Order lock has been acquired.
            boolean owned = TransactionSynchronizationManager.isActualTransactionActive()
                    ? repository.lockOwnedRecoveryTask(taskId, authentication.getName()).isPresent()
                    : repository.countOwnedRecoveryTask(taskId, authentication.getName()) == 1L;
            assertOwned(owned ? 1L : 0L, authentication);
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && repository.findCurrentOrderIdByRecoveryTaskId(taskId).isPresent()) {
            throw rejectedObject(authentication);
        }
        if (isAdministrator(authentication)) {
            return;
        }
        if (isManagerial(authentication)) {
            Optional<Long> currentManagerId = TransactionSynchronizationManager.isActualTransactionActive()
                    ? repository.findCurrentManagerIdByRecoveryTaskId(taskId)
                    : repository.findManagerIdByRecoveryTaskId(taskId);
            boolean managerAllowed = currentManagerId
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
            Function<Long, Optional<Long>> currentOrderIdQuery,
            Authentication authentication
    ) {
        Long candidateOrderId = orderIdQuery.apply(entityId)
                .orElseThrow(() -> rejectedObject(authentication));
        return resolveCanonicalOrder(entityId, orderIdQuery, currentOrderIdQuery, authentication, candidateOrderId);
    }

    private Long resolveCanonicalOrder(
            long entityId,
            Function<Long, Optional<Long>> orderIdQuery,
            Function<Long, Optional<Long>> currentOrderIdQuery,
            Authentication authentication,
            Long candidateOrderId
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return candidateOrderId;
        }
        lockOrderWhenTransactional(candidateOrderId, authentication);
        Long currentOrderId = currentOrderIdQuery.apply(entityId)
                .orElseThrow(() -> rejectedObject(authentication));
        if (!Objects.equals(candidateOrderId, currentOrderId)) {
            throw rejectedObject(authentication);
        }
        return currentOrderId;
    }

    private void requireManagerialOrderAccess(Long orderId, Authentication authentication) {
        boolean allowed = TransactionSynchronizationManager.isActualTransactionActive()
                ? managerAccessService.canAccessCurrentOrderManager(repository.findCurrentManagerIdByOrderId(orderId).orElse(null), authentication)
                : managerAccessService.canAccessOrder(orderId, authentication);
        if (!allowed) throw notFound();
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

    private void requireStaff(Authentication authentication) {
        if (!guardedStaff(authentication) || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Операция недоступна");
        }
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
