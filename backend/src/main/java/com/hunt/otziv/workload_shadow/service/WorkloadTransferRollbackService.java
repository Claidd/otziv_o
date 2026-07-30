package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadTransferActionResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.RollbackContextProjection;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadTransferRollbackService {

    public static final String CONFIRMATION = "ОТКАТИТЬ ПЕРЕДАЧУ";

    private final WorkloadTransferExecutionRepository repository;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional
    public WorkloadTransferActionResponse rollback(
            long executionId,
            String confirmation
    ) {
        if (!CONFIRMATION.equals(confirmation == null ? "" : confirmation.trim())) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "Для отката введите точную фразу: " + CONFIRMATION
            );
        }
        LocalDateTime now = now();
        if (repository.claimRollback(executionId, now) != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Откат недоступен: окно истекло, передача уже изменена или откатывается"
            );
        }
        RollbackContextProjection context = repository.findRollbackContext(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Захваченный журнал отката не найден"
                ));
        long sourceWorkerId = required(context.getSourceWorkerId(), "sourceWorkerId");
        long targetWorkerId = required(context.getTargetWorkerId(), "targetWorkerId");
        long companyId = required(context.getCompanyId(), "companyId");
        long unsafe = repository.countRollbackUnsafeEntities(
                executionId,
                targetWorkerId
        );
        if (unsafe > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Технический откат запрещён: после передачи уже начата работа. "
                            + "Нужна новая контролируемая передача."
            );
        }

        List<Long> orderIds = ids(executionId, "ORDER");
        List<Long> reviewIds = ids(executionId, "REVIEW");
        List<Long> badTaskIds = ids(executionId, "BAD_TASK");
        List<Long> recoveryTaskIds = ids(executionId, "RECOVERY_TASK");
        boolean targetLinkWasAdded =
                !ids(executionId, "COMPANY_LINK").isEmpty();

        repository.ensureSourceCompanyLink(companyId, sourceWorkerId);
        if (!reviewIds.isEmpty()) {
            repository.clearCredentialPreparations(reviewIds);
            exact(
                    repository.rollbackReviews(
                            executionId,
                            reviewIds,
                            sourceWorkerId,
                            targetWorkerId
                    ),
                    reviewIds.size(),
                    "Карточки изменились после проверки отката"
            );
        }
        if (!badTaskIds.isEmpty()) {
            exact(
                    repository.rollbackBadTasks(
                            executionId,
                            badTaskIds,
                            sourceWorkerId,
                            targetWorkerId
                    ),
                    badTaskIds.size(),
                    "Плохие отзывы изменились после проверки отката"
            );
        }
        if (!recoveryTaskIds.isEmpty()) {
            exact(
                    repository.rollbackRecoveryTasks(
                            executionId,
                            recoveryTaskIds,
                            sourceWorkerId,
                            targetWorkerId,
                            now
                    ),
                    recoveryTaskIds.size(),
                    "Восстановления изменились после проверки отката"
            );
        }
        if (!orderIds.isEmpty()) {
            exact(
                    repository.rollbackOrders(
                            executionId,
                            orderIds,
                            sourceWorkerId,
                            targetWorkerId,
                            companyId
                    ),
                    orderIds.size(),
                    "Заказы изменились после проверки отката"
            );
        }
        if (targetLinkWasAdded) {
            repository.removeTargetCompanyLinkIfUnused(companyId, targetWorkerId);
        }
        positive(
                repository.markRolledBack(executionId, now),
                "Не удалось завершить журнал отката"
        );
        return new WorkloadTransferActionResponse(
                executionId,
                "ROLLED_BACK",
                "Передача полностью отменена; все зафиксированные назначения восстановлены"
        );
    }

    private List<Long> ids(long executionId, String type) {
        return repository.findAuditEntityIds(executionId, type);
    }

    private LocalDateTime now() {
        var settings = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(settings));
    }

    private long required(Number value, String name) {
        if (value == null) {
            throw new IllegalStateException("В журнале отсутствует " + name);
        }
        return value.longValue();
    }

    private void exact(int actual, int expected, String message) {
        if (actual != expected) {
            throw new IllegalStateException(
                    message + ": ожидалось " + expected + ", изменено " + actual
            );
        }
    }

    private void positive(int actual, String message) {
        /*
         * markRolledBack updates execution and workflow in one guarded MySQL
         * multi-table statement, so one logical rollback reports two changed rows.
         */
        if (actual <= 0) {
            throw new IllegalStateException(message);
        }
    }
}
