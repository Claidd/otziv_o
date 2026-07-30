package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.PreparedProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadEmergencyAssignmentTransactionService {

    private final WorkloadEmergencyAssignmentRepository repository;
    private final WorkloadTransferExecutionRepository executionRepository;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyResult apply(
            EmergencyCase candidateCase,
            Recipient recipient,
            BigDecimal minimumRating,
            long auditGroupChatId
    ) {
        var live = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(live)
                || !WorkloadLiveSettingsService.MODE_LIVE.equals(live.mode())
                || !live.emergencyFallbackEnabled()
                || !liveSettingsService.managerAllowed(
                        live,
                        candidateCase.sourceManagerId()
                )) {
            return ApplyResult.skipped("Аварийное назначение выключено");
        }
        LocalDateTime now = now();
        String key = UUID.randomUUID().toString();
        int inserted = repository.insertPrepared(
                key,
                candidateCase.shadowCaseId(),
                candidateCase.exhaustedWorkflowId(),
                candidateCase.reviewId(),
                recipient.workerId(),
                recipient.targetGroupChatId(),
                auditGroupChatId,
                minimumRating,
                live.mode(),
                candidateCase.exhaustedWorkflowId() == null
                        ? "У менеджера нет подходящих получателей"
                        : "Все последовательные предложения исчерпаны",
                now.toLocalDate(),
                now.plusMinutes(live.rollbackWindowMinutes()),
                now
        );
        if (inserted == 0) {
            return ApplyResult.skipped(
                    "Карточка, получатель или кадровый сигнал уже изменились"
            );
        }
        PreparedProjection prepared = repository.findPrepared(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Подготовленное аварийное назначение не найдено"
                ));
        long assignmentId = required(prepared.getAssignmentId(), "assignmentId");
        long sourceWorkerId = required(prepared.getSourceWorkerId(), "sourceWorkerId");
        long targetWorkerId = required(prepared.getTargetWorkerId(), "targetWorkerId");
        long companyId = required(prepared.getCompanyId(), "companyId");
        long reviewId = required(prepared.getReviewId(), "reviewId");

        int linkAdded = executionRepository.ensureTargetCompanyLink(
                companyId,
                targetWorkerId
        );
        if (linkAdded != 0 && linkAdded != 1) {
            throw new IllegalStateException(
                    "Некорректный результат добавления связи компании"
            );
        }
        executionRepository.clearCredentialPreparations(List.of(reviewId));
        exact(
                repository.transferReview(
                        reviewId,
                        sourceWorkerId,
                        targetWorkerId,
                        companyId
                ),
                1,
                "Карточка изменилась до аварийного назначения"
        );
        exact(
                repository.markApplied(assignmentId, linkAdded == 1, now),
                1,
                "Не удалось завершить журнал аварийного назначения"
        );
        if (prepared.getExhaustedWorkflowId() != null) {
            exact(
                    repository.markExhaustedWorkflowEmergencyApplied(
                            prepared.getExhaustedWorkflowId(),
                            now
                    ),
                    1,
                    "Исчерпанный workflow уже изменился"
            );
        }
        return new ApplyResult(
                assignmentId,
                "APPLIED",
                "Одиночная карточка назначена резервному специалисту"
        );
    }

    private LocalDateTime now() {
        var shadow = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(shadow));
    }

    private long required(Number value, String name) {
        if (value == null) {
            throw new IllegalStateException("Отсутствует " + name);
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

    public record EmergencyCase(
            long shadowCaseId,
            long sourceManagerId,
            long sourceWorkerId,
            long companyId,
            String companyTitle,
            long reviewId,
            Long exhaustedWorkflowId
    ) {
    }

    public record Recipient(
            long workerId,
            long managerId,
            long targetGroupChatId,
            String workerName
    ) {
    }

    public record ApplyResult(Long assignmentId, String status, String message) {
        static ApplyResult skipped(String message) {
            return new ApplyResult(null, "SKIPPED", message);
        }
    }
}
