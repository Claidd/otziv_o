package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadTransferActionResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadEmergencyRollbackService {

    public static final String CONFIRMATION =
            "ОТКАТИТЬ АВАРИЙНУЮ КАРТОЧКУ";

    private final WorkloadEmergencyAssignmentRepository repository;
    private final WorkloadTransferExecutionRepository executionRepository;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional
    public WorkloadTransferActionResponse rollback(
            long assignmentId,
            String confirmation
    ) {
        if (!CONFIRMATION.equals(
                confirmation == null ? "" : confirmation.trim()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "Для отката введите точную фразу: " + CONFIRMATION
            );
        }
        LocalDateTime now = now();
        if (repository.claimRollback(assignmentId, now) != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Откат недоступен: срок истёк или назначение уже изменено"
            );
        }
        var context = repository.findRollbackContext(assignmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Захваченное аварийное назначение не найдено"
                ));
        if (!Boolean.TRUE.equals(context.getRollbackSafe())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Откат остановлен: получатель уже изменил или выполнил карточку"
            );
        }
        long sourceWorkerId = required(context.getSourceWorkerId());
        long targetWorkerId = required(context.getTargetWorkerId());
        long companyId = required(context.getCompanyId());
        executionRepository.ensureSourceCompanyLink(
                companyId,
                sourceWorkerId
        );
        exact(
                repository.rollbackReview(assignmentId),
                "Карточка изменилась во время отката"
        );
        if (Boolean.TRUE.equals(context.getTargetCompanyLinkAdded())) {
            executionRepository.removeTargetCompanyLinkIfUnused(
                    companyId,
                    targetWorkerId
            );
        }
        exact(
                repository.markRolledBack(assignmentId, now),
                "Не удалось завершить журнал отката"
        );
        return new WorkloadTransferActionResponse(
                assignmentId,
                "ROLLED_BACK",
                "Аварийная карточка возвращена исходному специалисту"
        );
    }

    private LocalDateTime now() {
        var settings = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(settings));
    }

    private long required(Number value) {
        if (value == null) {
            throw new IllegalStateException(
                    "В аварийном назначении отсутствует обязательный ID"
            );
        }
        return value.longValue();
    }

    private void exact(int actual, String message) {
        if (actual != 1) {
            throw new IllegalStateException(message);
        }
    }
}
