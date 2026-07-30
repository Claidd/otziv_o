package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository.ManagerReservationProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository.RecommendationCandidateProjection;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphDiagnostics;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphQueryService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferWorkflowService {

    private final WorkloadTransferWorkflowRepository repository;
    private final WorkloadTransferGraphQueryService graphQueryService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final WorkloadTransferGraphSnapshotService graphSnapshotService;

    @Transactional
    public StageResult stageEligibleRecommendations() {
        WorkloadLiveSettingsResponse settings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(settings)) {
            return StageResult.disabled();
        }
        LocalDateTime now = now();
        if (!insideOfferWindow(settings, now.toLocalTime())) {
            return new StageResult(true, 0, 0, 0, "Вне окна отправки предложений");
        }

        List<RecommendationCandidateProjection> rows =
                repository.findRecommendationCandidates();
        if (rows.isEmpty()) {
            return new StageResult(true, 0, 0, 0, "Новых рекомендаций нет");
        }

        Map<Long, RecommendationGroup> groups = group(rows);
        Map<Long, Long> reservedByManager = reservations(now.toLocalDate().atStartOfDay());
        long globalReserved = reservedByManager.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        int globalRoom = remaining(
                settings.maxTransfersGlobalDay(),
                globalReserved
        );
        if (globalRoom == 0) {
            return new StageResult(true, 0, groups.size(), 0, "Достигнут общий дневной лимит");
        }

        List<RecommendationGroup> selected = new ArrayList<>();
        int skippedByPolicy = 0;
        for (RecommendationGroup group : groups.values()) {
            if (selected.size() >= globalRoom) {
                skippedByPolicy++;
                continue;
            }
            if (!liveSettingsService.managerAllowed(settings, group.managerId())) {
                skippedByPolicy++;
                continue;
            }
            if (group.candidates().size() < settings.minCandidatesPerManager()) {
                skippedByPolicy++;
                continue;
            }
            if (group.financiallyUnsafeOrderCount() > 0) {
                skippedByPolicy++;
                continue;
            }
            long managerReserved = reservedByManager.getOrDefault(group.managerId(), 0L);
            if (managerReserved >= settings.maxTransfersPerManagerDay()) {
                skippedByPolicy++;
                continue;
            }
            selected.add(group);
            reservedByManager.put(group.managerId(), managerReserved + 1);
        }
        if (selected.isEmpty()) {
            return new StageResult(
                    true,
                    0,
                    skippedByPolicy,
                    0,
                    "Рекомендации остановлены лимитами или предохранителями"
            );
        }

        Set<Long> sourceWorkerIds = new LinkedHashSet<>();
        selected.forEach(value -> sourceWorkerIds.add(value.sourceWorkerId()));
        Map<Long, List<WorkloadTransferCompanyGraph>> graphs =
                graphQueryService.findActiveGraphs(sourceWorkerIds, now.toLocalDate());
        long appliedExecutions = repository.countAppliedExecutions();
        boolean ownerConfirmationRequired =
                appliedExecutions < settings.firstLiveOwnerConfirmations();
        List<WorkflowInsert> workflowInserts = new ArrayList<>();
        List<CandidateInsert> candidateInserts = new ArrayList<>();
        int graphRejected = 0;
        for (RecommendationGroup group : selected) {
            WorkloadTransferCompanyGraph graph = graph(
                    graphs.get(group.sourceWorkerId()),
                    group.companyId()
            );
            if (graph == null
                    || WorkloadTransferGraphDiagnostics.from(graph).errorCount() > 0) {
                graphRejected++;
                continue;
            }
            WorkloadTransferGraphSnapshotService.Snapshot snapshot =
                    graphSnapshotService.snapshot(graph);
            String workflowKey = UUID.randomUUID().toString();
            workflowInserts.add(new WorkflowInsert(
                    workflowKey,
                    group.shadowCaseId(),
                    group.managerId(),
                    group.sourceWorkerId(),
                    group.companyId(),
                    snapshot.fingerprint(),
                    snapshot.json(),
                    group.candidates().size()
            ));
            for (CandidateSnapshot candidate : group.candidates()) {
                candidateInserts.add(new CandidateInsert(
                        workflowKey,
                        group.managerId(),
                        group.sourceWorkerId(),
                        group.companyId(),
                        candidate.workerId(),
                        candidate.sequenceNumber(),
                        candidate.rating(),
                        candidate.hundredPercentDays(),
                        candidate.failureDays(),
                        candidate.currentEstimatedMinutes(),
                        candidate.targetGroupChatId(),
                        candidate.candidateTelegramId()
                ));
            }
        }
        if (workflowInserts.isEmpty()) {
            return new StageResult(
                    true,
                    0,
                    skippedByPolicy,
                    graphRejected,
                    "Повторная проверка графов не оставила безопасных рекомендаций"
            );
        }

        String workflowsJson = graphSnapshotService.json(workflowInserts);
        int staged = repository.insertWorkflowsBulk(
                workflowsJson,
                settings.mode(),
                ownerConfirmationRequired,
                now.toLocalDate(),
                now
        );
        if (staged > 0) {
            repository.insertWorkflowCandidatesBulk(
                    graphSnapshotService.json(candidateInserts),
                    now
            );
            long incompleteQueues =
                    repository.countIncompleteWorkflowQueues(workflowsJson);
            if (incompleteQueues > 0) {
                throw new IllegalStateException(
                        "Не удалось зафиксировать полные очереди кандидатов для "
                                + incompleteQueues
                                + " workflow"
                );
            }
        }
        return new StageResult(
                true,
                staged,
                skippedByPolicy,
                graphRejected,
                "Боевые workflow созданы только из повторно проверенных графов"
        );
    }

    private Map<Long, RecommendationGroup> group(
            List<RecommendationCandidateProjection> rows
    ) {
        Map<Long, MutableGroup> grouped = new LinkedHashMap<>();
        for (RecommendationCandidateProjection row : rows) {
            if (row == null
                    || row.getShadowCaseId() == null
                    || row.getManagerId() == null
                    || row.getSourceWorkerId() == null
                    || row.getCompanyId() == null
                    || row.getCandidateWorkerId() == null
                    || row.getSequenceNumber() == null
                    || row.getTargetGroupChatId() == null
                    || row.getCandidateTelegramId() == null) {
                continue;
            }
            MutableGroup group = grouped.computeIfAbsent(
                    row.getShadowCaseId(),
                    ignored -> new MutableGroup(row)
            );
            group.candidates.add(new CandidateSnapshot(
                    row.getCandidateWorkerId(),
                    row.getSequenceNumber(),
                    row.getRating(),
                    value(row.getHundredPercentDays()),
                    value(row.getFailureDays()),
                    value(row.getCurrentEstimatedMinutes()),
                    row.getTargetGroupChatId(),
                    row.getCandidateTelegramId()
            ));
        }
        Map<Long, RecommendationGroup> result = new LinkedHashMap<>();
        for (Map.Entry<Long, MutableGroup> entry : grouped.entrySet()) {
            result.put(entry.getKey(), entry.getValue().freeze());
        }
        return result;
    }

    private Map<Long, Long> reservations(LocalDateTime dayStart) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (ManagerReservationProjection row : repository.reservedByManagerSince(dayStart)) {
            if (row != null && row.getManagerId() != null) {
                result.put(row.getManagerId(), value(row.getReservedCount()));
            }
        }
        return result;
    }

    private WorkloadTransferCompanyGraph graph(
            List<WorkloadTransferCompanyGraph> values,
            long companyId
    ) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(value -> value.companyId() == companyId)
                .findFirst()
                .orElse(null);
    }

    private boolean insideOfferWindow(
            WorkloadLiveSettingsResponse settings,
            LocalTime now
    ) {
        LocalTime start = LocalTime.parse(settings.offerStartTime());
        LocalTime end = LocalTime.parse(settings.offerEndTime());
        return !now.isBefore(start) && now.isBefore(end);
    }

    private int remaining(int configured, long used) {
        long value = Math.max(0, (long) configured - Math.max(0, used));
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private LocalDateTime now() {
        var shadow = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(shadow));
    }

    private long value(Number value) {
        return value == null ? 0 : value.longValue();
    }

    public record StageResult(
            boolean enabled,
            int staged,
            int skippedByPolicy,
            int graphRejected,
            String message
    ) {
        static StageResult disabled() {
            return new StageResult(false, 0, 0, 0, "Боевой контур выключен");
        }
    }

    private record CandidateSnapshot(
            long workerId,
            int sequenceNumber,
            java.math.BigDecimal rating,
            long hundredPercentDays,
            long failureDays,
            long currentEstimatedMinutes,
            long targetGroupChatId,
            long candidateTelegramId
    ) {
    }

    private record WorkflowInsert(
            String workflowKey,
            long shadowCaseId,
            long managerId,
            long sourceWorkerId,
            long companyId,
            String graphFingerprint,
            String graphJson,
            int candidateCount
    ) {
    }

    private record CandidateInsert(
            String workflowKey,
            long managerId,
            long sourceWorkerId,
            long companyId,
            long workerId,
            int sequenceNumber,
            java.math.BigDecimal rating,
            long hundredPercentDays,
            long failureDays,
            long currentEstimatedMinutes,
            long targetGroupChatId,
            long candidateTelegramId
    ) {
    }

    private record RecommendationGroup(
            long shadowCaseId,
            long managerId,
            long sourceWorkerId,
            long companyId,
            long financiallyUnsafeOrderCount,
            List<CandidateSnapshot> candidates
    ) {
    }

    private static final class MutableGroup {
        private final RecommendationCandidateProjection first;
        private final List<CandidateSnapshot> candidates = new ArrayList<>();

        private MutableGroup(RecommendationCandidateProjection first) {
            this.first = first;
        }

        private RecommendationGroup freeze() {
            return new RecommendationGroup(
                    first.getShadowCaseId(),
                    first.getManagerId(),
                    first.getSourceWorkerId(),
                    first.getCompanyId(),
                    first.getFinanciallyUnsafeOrderCount() == null
                            ? 0
                            : first.getFinanciallyUnsafeOrderCount(),
                    List.copyOf(candidates)
            );
        }
    }
}
