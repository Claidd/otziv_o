package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
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

    public void assertOrder(long orderId) {
        assertOwned(
                orderId,
                "заказ",
                repository::countOwnedOrder,
                repository::lockOwnedOrder
        );
    }

    public void assertReview(long reviewId) {
        assertOwned(
                reviewId,
                "карточка",
                repository::countOwnedReview,
                repository::lockOwnedReview
        );
    }

    public void assertBadTask(long taskId) {
        assertOwned(
                taskId,
                "задача плохого отзыва",
                repository::countOwnedBadTask,
                repository::lockOwnedBadTask
        );
    }

    public void assertRecoveryTask(long taskId) {
        assertOwned(
                taskId,
                "задача восстановления",
                repository::countOwnedRecoveryTask,
                repository::lockOwnedRecoveryTask
        );
    }

    private void assertOwned(
            long entityId,
            String label,
            BiFunction<Long, String, Long> countQuery,
            BiFunction<Long, String, Optional<Long>> lockQuery
    ) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (!isPlainWorker(authentication)) {
            return;
        }
        String username = authentication.getName();
        boolean owned = false;
        if (username != null && !username.isBlank()) {
            owned = TransactionSynchronizationManager
                    .isActualTransactionActive()
                    ? lockQuery.apply(entityId, username).isPresent()
                    : countQuery.apply(entityId, username) == 1L;
        }
        if (owned) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Назначение изменилось: " + label
                        + " уже передана другому специалисту. Обновите страницу."
        );
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
