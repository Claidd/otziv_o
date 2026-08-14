package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.ExecutionContextProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.WorkerManagerAssignmentProjection;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferGraphDiagnostics;
import com.hunt.otziv.workload_shadow.transfer.service.WorkloadTransferGraphQueryService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferExecutionTransactionService {

    private final WorkloadTransferExecutionRepository repository;
    private final WorkloadTransferGraphQueryService graphQueryService;
    private final WorkloadTransferGraphSnapshotService graphSnapshotService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository liveControlRepository;
    private final com.hunt.otziv.workload_shadow.repository.WorkloadTransferApplyGuardRepository applyGuardRepository;

    @Transactional
    public ApplyResult apply(long workflowId, long expectedVersion) {
        WorkloadLiveSettingsResponse liveSettings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(liveSettings)) {
            return ApplyResult.skipped(workflowId, "Боевой контур выключен");
        }
        LocalDateTime now = now();
        var liveControl = liveControlRepository.lockState().orElse(null);
        if (liveControl == null
                || liveControl.getSettingsRevision() == null
                || liveControl.getSettingsRevision() != liveSettings.revision()
                || !"true".equals(liveControl.getApplyEnabled())
                || !liveSettings.mode().equals(liveControl.getMode())) {
            return ApplyResult.skipped(
                    workflowId,
                    "Боевой контур или его ревизия изменились"
            );
        }
        if (repository.claimWorkflow(workflowId, expectedVersion, now) != 1) {
            return ApplyResult.skipped(workflowId, "Workflow уже обработан другим запуском");
        }
        ExecutionContextProjection context = repository.findClaimedContext(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                        "Захваченный workflow не найден: " + workflowId
                ));
        var applyGuard = applyGuardRepository.lockGuard(workflowId).orElse(null);
        if (applyGuard == null
                || applyGuard.getLiveSettingsRevision() == null
                || applyGuard.getLiveSettingsRevision()
                        .longValue() != liveControl.getSettingsRevision().longValue()
                || applyGuard.getRecommendationCurrent() == null
                || applyGuard.getRecommendationCurrent().longValue() != 1L) {
            return blocked(
                    context,
                    "BLOCKED_RECOMMENDATION_STALE",
                    "Исходный специалист или рекомендация больше не проходят "
                            + "актуальные критерии"
            );
        }
        if (!liveSettingsService.managerAllowed(liveSettings, context.getManagerId())) {
            return blocked(
                    context,
                    "BLOCKED_MODE",
                    "Менеджер больше не входит в разрешённый режим"
            );
        }
        if (context.getTargetEligible() == null
                || context.getTargetEligible().longValue() != 1L) {
            return blocked(
                    context,
                    "BLOCKED_RECIPIENT",
                    "Получатель больше не проходит актуальные критерии"
            );
        }
        if (!now.toLocalDate().equals(context.getDecisionDate())) {
            return blocked(
                    context,
                    "BLOCKED_EXPIRED",
                    "Решение относится к другому рабочему дню"
            );
        }
        long sourceWorkerId = required(context.getSourceWorkerId(), "sourceWorkerId");
        long targetWorkerId = required(context.getTargetWorkerId(), "targetWorkerId");
        long managerId = required(context.getManagerId(), "managerId");
        List<WorkerManagerAssignmentProjection> managerAssignments =
                repository.lockWorkerManagerAssignments(
                        List.of(sourceWorkerId, targetWorkerId)
                );
        if (!sameExclusiveManager(
                managerAssignments,
                sourceWorkerId,
                targetWorkerId,
                managerId
        )) {
            return blocked(
                    context,
                    "BLOCKED_MANAGER_CHANGED",
                    "Связь исходного специалиста или получателя с менеджером изменилась"
            );
        }
        long companyId = required(context.getCompanyId(), "companyId");
        if (repository.lockCompanyForTransfer(companyId).isEmpty()) {
            return blocked(
                    context,
                    "BLOCKED_COMPANY_MISSING",
                    "Компания больше не существует"
            );
        }
        repository.lockActiveSourceOrderIds(sourceWorkerId, companyId);
        long unsafeOrders = repository.countFinanciallyUnsafeOrders(
                sourceWorkerId,
                companyId
        );
        if (unsafeOrders > 0) {
            return blocked(
                    context,
                    "BLOCKED_FINANCIAL",
                    "Заказ уже готов к финансовому закрытию или по нему создан финансовый документ"
            );
        }

        WorkloadTransferCompanyGraph graph = currentGraph(context);
        if (graph == null) {
            return blocked(
                    context,
                    "BLOCKED_GRAPH_MISSING",
                    "Активный граф компании больше не найден"
            );
        }
        WorkloadTransferGraphDiagnostics diagnostics =
                WorkloadTransferGraphDiagnostics.from(graph);
        if (diagnostics.errorCount() > 0) {
            return blocked(
                    context,
                    "BLOCKED_GRAPH",
                    "Повторная проверка графа обнаружила ошибок: "
                            + diagnostics.errorCount()
            );
        }
        WorkloadTransferGraphSnapshotService.Snapshot snapshot =
                graphSnapshotService.snapshot(graph);
        if (!snapshot.fingerprint().equals(context.getGraphFingerprint())) {
            return blocked(
                    context,
                    "BLOCKED_GRAPH_CHANGED",
                    "Состав заказа изменился после отправки предложения"
            );
        }

        WorkloadTransferPlan plan = WorkloadTransferPlan.from(graph);
        if (plan.empty()) {
            return blocked(
                    context,
                    "BLOCKED_EMPTY",
                    "В компании больше нет активной работы для передачи"
            );
        }

        String idempotencyKey = UUID.randomUUID().toString();
        LocalDateTime rollbackDeadline = now.plusMinutes(
                liveSettings.rollbackWindowMinutes()
        );
        String planJson = graphSnapshotService.json(plan);
        exact(
                repository.insertExecution(
                        workflowId,
                        idempotencyKey,
                        planJson,
                        now,
                        rollbackDeadline
                ),
                1,
                "Не удалось создать журнал применения"
        );
        long executionId = repository.findExecutionIdByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Созданный журнал применения не найден"
                ));

        audit(
                executionId,
                plan,
                sourceWorkerId,
                targetWorkerId,
                companyId,
                now
        );
        int linkAdded = repository.ensureTargetCompanyLink(companyId, targetWorkerId);
        if (linkAdded == 1) {
            exact(
                    repository.auditAddedCompanyLink(
                            executionId,
                            companyId,
                            targetWorkerId,
                            now
                    ),
                    1,
                    "Не удалось зафиксировать новую связь компании"
            );
        } else if (linkAdded != 0) {
            throw new IllegalStateException("Некорректный результат добавления связи");
        }

        if (!plan.reviewIds().isEmpty()) {
            repository.clearCredentialPreparations(plan.reviewIds());
            exact(
                    repository.transferReviews(
                            plan.reviewIds(),
                            sourceWorkerId,
                            targetWorkerId
                    ),
                    plan.reviewIds().size(),
                    "Изменился состав карточек отзывов"
            );
        }
        if (!plan.badTaskIds().isEmpty()) {
            exact(
                    repository.transferBadTasks(
                            plan.badTaskIds(),
                            sourceWorkerId,
                            targetWorkerId
                    ),
                    plan.badTaskIds().size(),
                    "Изменился состав плохих отзывов"
            );
        }
        if (!plan.recoveryTaskIds().isEmpty()) {
            exact(
                    repository.transferRecoveryTasks(
                            plan.recoveryTaskIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            now
                    ),
                    plan.recoveryTaskIds().size(),
                    "Изменился состав восстановлений"
            );
        }
        if (!plan.orderIds().isEmpty()) {
            exact(
                    repository.transferOrders(
                            plan.orderIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            companyId
                    ),
                    plan.orderIds().size(),
                    "Изменился состав заказов"
            );
        }
        repository.removeSourceCompanyLinkIfUnused(companyId, sourceWorkerId);

        String afterSnapshot = graphSnapshotService.json(new AppliedSnapshot(
                workflowId,
                executionId,
                sourceWorkerId,
                targetWorkerId,
                companyId,
                plan.orderIds().size(),
                plan.reviewIds().size(),
                plan.badTaskIds().size(),
                plan.recoveryTaskIds().size(),
                linkAdded == 1,
                now
        ));
        exact(
                repository.markExecutionApplied(
                        executionId,
                        plan.orderIds().size(),
                        plan.reviewIds().size(),
                        plan.badTaskIds().size(),
                        plan.recoveryTaskIds().size(),
                        afterSnapshot,
                        now
                ),
                1,
                "Не удалось завершить журнал применения"
        );
        exact(
                repository.markWorkflowApplied(workflowId, now),
                1,
                "Не удалось завершить workflow"
        );
        return new ApplyResult(
                workflowId,
                executionId,
                "APPLIED",
                "Пакет компании передан одной транзакцией"
        );
    }

    private void audit(
            long executionId,
            WorkloadTransferPlan plan,
            long sourceWorkerId,
            long targetWorkerId,
            long companyId,
            LocalDateTime now
    ) {
        if (!plan.orderIds().isEmpty()) {
            exact(
                    repository.auditOrders(
                            executionId,
                            plan.orderIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            companyId,
                            now
                    ),
                    plan.orderIds().size(),
                    "Не удалось зафиксировать все заказы"
            );
        }
        if (!plan.reviewIds().isEmpty()) {
            exact(
                    repository.auditReviews(
                            executionId,
                            plan.reviewIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            companyId,
                            now
                    ),
                    plan.reviewIds().size(),
                    "Не удалось зафиксировать все карточки"
            );
        }
        if (!plan.badTaskIds().isEmpty()) {
            exact(
                    repository.auditBadTasks(
                            executionId,
                            plan.badTaskIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            companyId,
                            now
                    ),
                    plan.badTaskIds().size(),
                    "Не удалось зафиксировать все плохие отзывы"
            );
        }
        if (!plan.recoveryTaskIds().isEmpty()) {
            exact(
                    repository.auditRecoveryTasks(
                            executionId,
                            plan.recoveryTaskIds(),
                            sourceWorkerId,
                            targetWorkerId,
                            companyId,
                            now
                    ),
                    plan.recoveryTaskIds().size(),
                    "Не удалось зафиксировать все восстановления"
            );
        }
    }

    private WorkloadTransferCompanyGraph currentGraph(ExecutionContextProjection context) {
        long sourceWorkerId = required(context.getSourceWorkerId(), "sourceWorkerId");
        Map<Long, List<WorkloadTransferCompanyGraph>> graphs =
                graphQueryService.findActiveGraphs(
                        List.of(sourceWorkerId),
                        context.getDecisionDate()
                );
        return graphs.getOrDefault(sourceWorkerId, List.of()).stream()
                .filter(value -> value.companyId()
                        == required(context.getCompanyId(), "companyId"))
                .findFirst()
                .orElse(null);
    }

    private boolean sameExclusiveManager(
            List<WorkerManagerAssignmentProjection> assignments,
            long sourceWorkerId,
            long targetWorkerId,
            long managerId
    ) {
        Map<Long, Set<Long>> managersByWorker = new HashMap<>();
        for (WorkerManagerAssignmentProjection assignment : assignments) {
            if (assignment == null
                    || assignment.getWorkerId() == null
                    || assignment.getManagerId() == null) {
                continue;
            }
            managersByWorker.computeIfAbsent(
                    assignment.getWorkerId(),
                    ignored -> new HashSet<>()
            ).add(assignment.getManagerId());
        }
        return Set.of(managerId).equals(managersByWorker.get(sourceWorkerId))
                && Set.of(managerId).equals(managersByWorker.get(targetWorkerId));
    }

    private ApplyResult blocked(
            ExecutionContextProjection context,
            String code,
            String message
    ) {
        long workflowId = required(context.getWorkflowId(), "workflowId");
        LocalDateTime blockedAt = now();
        exact(
                repository.closeAcceptedCandidateForBlockedWorkflow(
                        workflowId,
                        message,
                        blockedAt
                ),
                1,
                "Не удалось закрыть кандидата остановленного workflow"
        );
        exact(
                repository.closeAcceptedOfferForBlockedWorkflow(
                        workflowId,
                        code,
                        message,
                        blockedAt
                ),
                1,
                "Не удалось закрыть предложение остановленного workflow"
        );
        exact(
                repository.blockWorkflow(
                        workflowId,
                        code,
                        code,
                        message,
                        blockedAt
                ),
                1,
                "Не удалось безопасно остановить workflow"
        );
        return new ApplyResult(workflowId, null, code, message);
    }

    private LocalDateTime now() {
        var settings = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(settings));
    }

    private long required(Number value, String name) {
        if (value == null) {
            throw new IllegalStateException("В workflow отсутствует " + name);
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

    public record ApplyResult(
            long workflowId,
            Long executionId,
            String status,
            String message
    ) {
        static ApplyResult skipped(long workflowId, String message) {
            return new ApplyResult(workflowId, null, "SKIPPED", message);
        }
    }

    private record AppliedSnapshot(
            long workflowId,
            long executionId,
            long sourceWorkerId,
            long targetWorkerId,
            long companyId,
            int orderCount,
            int reviewCount,
            int badTaskCount,
            int recoveryTaskCount,
            boolean targetCompanyLinkAdded,
            LocalDateTime appliedAt
    ) {
    }
}
