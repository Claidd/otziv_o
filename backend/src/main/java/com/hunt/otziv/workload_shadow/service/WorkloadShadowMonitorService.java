package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowEventResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSummaryResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowTransferCaseResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowWorkerResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowMonitorRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadShadowMonitorService {

    private final WorkloadShadowMonitorRepository monitorRepository;
    private final WorkloadShadowRunRepository runRepository;
    private final WorkloadShadowSettingsService settingsService;

    @Transactional(readOnly = true)
    public WorkloadShadowSummaryResponse summary() {
        var settings = settingsService.current();
        var totals = monitorRepository.summaryTotals();
        List<WorkloadShadowSummaryResponse.ManagerSummary> managers =
                monitorRepository.managerSummaries().stream()
                        .map(this::toManagerSummary)
                        .toList();

        return new WorkloadShadowSummaryResponse(
                totals.getUpdatedAt(),
                totals.getProgressDate(),
                WorkloadShadowSettingsService.MODE_SHADOW,
                false,
                settings.observationEnabled(),
                intValue(totals.getManagerCount()),
                intValue(totals.getWorkerCount()),
                intValue(totals.getWorkersAt100()),
                intValue(totals.getAtRiskWorkers()),
                intValue(totals.getTransferCases()),
                intValue(totals.getStaffingSignals()),
                longValue(totals.getLateExcludedUnits()),
                intValue(totals.getMissingManagerGroups()),
                intValue(totals.getMissingWorkerGroups()),
                walkEstimate(
                        settings.walkMinutesPerCard(),
                        settings.walkMinimumMinutesPerCard()
                ),
                lastRun(),
                managers
        );
    }

    @Transactional(readOnly = true)
    public List<WorkloadShadowWorkerResponse> workers(Long managerId) {
        return monitorRepository.workers(managerId).stream()
                .map(this::toWorker)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkloadShadowTransferCaseResponse> transferCases(Long managerId) {
        List<WorkloadShadowMonitorRepository.TransferCaseProjection> cases =
                monitorRepository.transferCases(managerId);
        if (cases.isEmpty()) {
            return List.of();
        }

        List<Long> caseIds = cases.stream()
                .map(WorkloadShadowMonitorRepository.TransferCaseProjection::getId)
                .toList();
        Map<Long, List<WorkloadShadowTransferCaseResponse.Candidate>> candidatesByCase =
                new LinkedHashMap<>();
        for (var candidate : monitorRepository.transferCandidates(caseIds)) {
            candidatesByCase.computeIfAbsent(
                    candidate.getTransferCaseId(),
                    ignored -> new ArrayList<>()
            ).add(toCandidate(candidate));
        }

        return cases.stream()
                .map(row -> toTransferCase(
                        row,
                        candidatesByCase.getOrDefault(row.getId(), List.of())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkloadShadowEventResponse> events(int requestedLimit) {
        int limit = Math.max(1, Math.min(200, requestedLimit));
        return monitorRepository.events(limit).stream()
                .map(this::toEvent)
                .toList();
    }

    private WorkloadShadowSummaryResponse.ManagerSummary toManagerSummary(
            WorkloadShadowMonitorRepository.ManagerSummaryProjection row
    ) {
        return new WorkloadShadowSummaryResponse.ManagerSummary(
                row.getManagerId(),
                row.getManagerName(),
                intValue(row.getWorkerCount()),
                intValue(row.getWorkersAt100()),
                decimal(row.getProgressPercent()),
                intValue(row.getTransferCaseCount()),
                booleanValue(row.getStaffingRequired()),
                booleanValue(row.getGroupConnected())
        );
    }

    private WorkloadShadowWorkerResponse toWorker(
            WorkloadShadowMonitorRepository.WorkerProjection row
    ) {
        return new WorkloadShadowWorkerResponse(
                row.getWorkerId(),
                row.getWorkerUserId(),
                row.getManagerId(),
                row.getManagerName(),
                row.getWorkerName(),
                row.getProgressDate(),
                row.getSnapshotAt(),
                decimal(row.getProgressPercent()),
                longValue(row.getCompletedUnits()),
                longValue(row.getActiveUnits()),
                longValue(row.getEligibleUnits()),
                longValue(row.getLateExcludedUnits()),
                longValue(row.getFeasibleUnits()),
                longValue(row.getEstimatedRemainingMinutes()),
                longValue(row.getPlannedUnits()),
                longValue(row.getIncomingUnits()),
                longValue(row.getUrgentUnits()),
                longValue(row.getExternalBlockedUnits()),
                longValue(row.getClientDeferredUnits()),
                longValue(row.getManagerDeferredUnits()),
                longValue(row.getBlockedUnits()),
                longValue(row.getNewUnits()),
                longValue(row.getCorrectionUnits()),
                longValue(row.getNagulUnits()),
                longValue(row.getPublishUnits()),
                longValue(row.getRecoveryUnits()),
                longValue(row.getBadUnits()),
                decimal(row.getRating()),
                intValue(row.getHundredPercentDays()),
                intValue(row.getFailureDays()),
                intValue(row.getEvaluatedDays()),
                intValue(row.getFreezeCredits()),
                intValue(row.getTransferStage()),
                booleanValue(row.getLastDayReached100()),
                booleanValue(row.getAcceptsCompanyTransfers()),
                booleanValue(row.getRecipientEligible()),
                booleanValue(row.getWorkerGroupConnected()),
                row.getDiagnosticStatus(),
                row.getLastAvailableAt()
        );
    }

    private WorkloadShadowTransferCaseResponse toTransferCase(
            WorkloadShadowMonitorRepository.TransferCaseProjection row,
            List<WorkloadShadowTransferCaseResponse.Candidate> candidates
    ) {
        return new WorkloadShadowTransferCaseResponse(
                row.getId(),
                row.getManagerId(),
                row.getManagerName(),
                row.getSourceWorkerId(),
                row.getSourceWorkerName(),
                row.getCompanyId(),
                row.getCompanyTitle(),
                intValue(row.getFailureNumber()),
                intValue(row.getTransferPercent()),
                intValue(row.getSelectionRank()),
                longValue(row.getProblemUnits()),
                longValue(row.getEstimatedMinutes()),
                new WorkloadShadowTransferCaseResponse.Graph(
                        longValue(row.getActiveOrderCount()),
                        longValue(row.getNewUnitCount()),
                        longValue(row.getCorrectionCount()),
                        longValue(row.getNagulCount()),
                        longValue(row.getPublishCount()),
                        longValue(row.getRecoveryCount()),
                        longValue(row.getBadCount())
                ),
                intValue(row.getGraphWarningCount()),
                intValue(row.getGraphErrorCount()),
                row.getGraphWarningCodes(),
                row.getGraphErrorCodes(),
                booleanValue(row.getStaffingRequired()),
                row.getFallbackWorkerId(),
                row.getFallbackWorkerName(),
                row.getFallbackReviewId(),
                row.getStatus(),
                row.getFirstDetectedAt(),
                row.getLastSeenAt(),
                candidates
        );
    }

    private WorkloadShadowTransferCaseResponse.Candidate toCandidate(
            WorkloadShadowMonitorRepository.TransferCandidateProjection row
    ) {
        return new WorkloadShadowTransferCaseResponse.Candidate(
                row.getWorkerId(),
                row.getWorkerName(),
                intValue(row.getSequenceNumber()),
                decimal(row.getRating()),
                intValue(row.getHundredPercentDays()),
                intValue(row.getFailureDays()),
                longValue(row.getCurrentEstimatedMinutes()),
                booleanValue(row.getWorkerGroupConnected()),
                row.getSimulatedOfferStatus()
        );
    }

    private WorkloadShadowEventResponse toEvent(
            WorkloadShadowMonitorRepository.EventProjection row
    ) {
        return new WorkloadShadowEventResponse(
                row.getId(),
                row.getSeverity(),
                row.getEventType(),
                row.getManagerId(),
                row.getManagerName(),
                row.getWorkerId(),
                row.getWorkerName(),
                row.getCompanyId(),
                row.getCompanyTitle(),
                row.getTitle(),
                row.getMessage(),
                row.getTargetGroupType(),
                booleanValue(row.getTargetGroupConnected()),
                row.getDeliveryStatus(),
                intValue(row.getDeliveryAttempts()),
                longValue(row.getOccurrenceCount()),
                row.getFirstSeenAt(),
                row.getLastSeenAt(),
                row.getDeliveredAt(),
                row.getLastErrorCode(),
                row.getLastError(),
                booleanValue(row.getActive())
        );
    }

    private WorkloadShadowSummaryResponse.LastRun lastRun() {
        return runRepository.latestRun()
                .map(row -> new WorkloadShadowSummaryResponse.LastRun(
                        row.getId(),
                        row.getStatus(),
                        row.getTriggerType(),
                        row.getStartedAt(),
                        row.getFinishedAt(),
                        row.getDurationMs(),
                        row.getErrorMessage()
                ))
                .orElse(null);
    }

    private WorkloadShadowSummaryResponse.WalkEstimateSummary walkEstimate(
            int defaultMinutes,
            int minimumMinutes
    ) {
        return monitorRepository.nagulEstimate()
                .map(row -> new WorkloadShadowSummaryResponse.WalkEstimateSummary(
                        defaultMinutes,
                        intValue(row.getMinimumMinutes()),
                        intValue(row.getEffectiveMinutes()),
                        longValue(row.getSampleCount()),
                        longValue(row.getAverageSeconds()),
                        row.getEstimateSource(),
                        row.getCalculatedAt()
                ))
                .orElse(new WorkloadShadowSummaryResponse.WalkEstimateSummary(
                        defaultMinutes,
                        minimumMinutes,
                        Math.max(defaultMinutes, minimumMinutes),
                        0,
                        0,
                        "DEFAULT",
                        null
                ));
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int intValue(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private long longValue(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value instanceof Number number && number.intValue() != 0;
    }
}
