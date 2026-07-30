package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.EmergencyCaseProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.EmergencyRecipientProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.WorkflowCandidatePairProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository.ManagerReservationProjection;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyAssignmentTransactionService.EmergencyCase;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyAssignmentTransactionService.Recipient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadEmergencyAssignmentService {

    private final WorkloadEmergencyAssignmentRepository repository;
    private final WorkloadTransferWorkflowRepository workflowRepository;
    private final WorkloadEmergencyAssignmentTransactionService transactionService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;

    public List<WorkloadEmergencyAssignmentTransactionService.ApplyResult>
            applyStaffingFallbacks() {
        var live = liveSettingsService.current();
        var shadow = shadowSettingsService.current();
        Long auditGroupChatId = shadow.notificationGroupChatId();
        if (!liveSettingsService.applicationAllowed(live)
                || !WorkloadLiveSettingsService.MODE_LIVE.equals(live.mode())
                || !live.emergencyFallbackEnabled()
                || !shadow.groupNotificationsEnabled()
                || auditGroupChatId == null
                || auditGroupChatId >= 0) {
            return List.of();
        }
        LocalDate today = now().toLocalDate();
        Map<Long, Long> managerUsage = new HashMap<>();
        long globalUsage = 0;
        for (ManagerReservationProjection row : workflowRepository.reservedByManagerSince(
                today.atStartOfDay()
        )) {
            if (row != null && row.getManagerId() != null) {
                long count = value(row.getReservedCount());
                managerUsage.put(row.getManagerId(), count);
                globalUsage += count;
            }
        }
        int globalRoom = remaining(live.maxTransfersGlobalDay(), globalUsage);
        if (globalRoom == 0) {
            return List.of();
        }

        BigDecimal minimumRating =
                BigDecimal.valueOf(shadow.recipientMinimumRating());
        List<EmergencyCaseProjection> readyCases = repository.findReadyCases();
        if (readyCases == null || readyCases.isEmpty()) {
            return List.of();
        }
        List<EmergencyRecipientProjection> recipientPool =
                repository.findEligibleRecipients(minimumRating, today);
        Map<Long, Set<Long>> priorCandidates = priorCandidates(readyCases);
        List<WorkloadEmergencyAssignmentTransactionService.ApplyResult> results =
                new ArrayList<>();
        for (EmergencyCaseProjection row : readyCases) {
            if (results.size() >= globalRoom || row == null) {
                break;
            }
            EmergencyCase candidateCase = emergencyCase(row);
            if (candidateCase == null
                    || !liveSettingsService.managerAllowed(
                            live,
                            candidateCase.sourceManagerId()
                    )) {
                continue;
            }
            long managerUsed = managerUsage.getOrDefault(
                    candidateCase.sourceManagerId(),
                    0L
            );
            if (managerUsed >= live.maxTransfersPerManagerDay()) {
                continue;
            }
            Recipient recipient = chooseRecipient(
                    recipientPool,
                    candidateCase,
                    priorCandidates.getOrDefault(
                            candidateCase.shadowCaseId(),
                            Set.of()
                    )
            );
            if (recipient == null) {
                continue;
            }
            try {
                var result = transactionService.apply(
                        candidateCase,
                        recipient,
                        minimumRating,
                        auditGroupChatId
                );
                if ("APPLIED".equals(result.status())) {
                    results.add(result);
                    managerUsage.put(
                            candidateCase.sourceManagerId(),
                            managerUsed + 1
                    );
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Emergency review assignment failed safely: caseId={}, reviewId={}",
                        candidateCase.shadowCaseId(),
                        candidateCase.reviewId(),
                        exception
                );
            }
        }
        return List.copyOf(results);
    }

    private Map<Long, Set<Long>> priorCandidates(
            List<EmergencyCaseProjection> readyCases
    ) {
        List<Long> caseIds = readyCases.stream()
                .filter(row -> row != null && row.getShadowCaseId() != null)
                .map(EmergencyCaseProjection::getShadowCaseId)
                .distinct()
                .toList();
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> result = new HashMap<>();
        for (WorkflowCandidatePairProjection row
                : repository.findWorkflowCandidatePairs(caseIds)) {
            if (row == null
                    || row.getShadowCaseId() == null
                    || row.getWorkerId() == null) {
                continue;
            }
            result.computeIfAbsent(
                    row.getShadowCaseId(),
                    ignored -> new HashSet<>()
            ).add(row.getWorkerId());
        }
        return result;
    }

    private Recipient chooseRecipient(
            List<EmergencyRecipientProjection> pool,
            EmergencyCase candidateCase,
            Set<Long> priorCandidates
    ) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        List<EmergencyRecipientProjection> rows = pool.stream()
                .filter(row -> row != null && row.getWorkerId() != null)
                .filter(row -> row.getWorkerId()
                        != candidateCase.sourceWorkerId())
                .filter(row -> !priorCandidates.contains(row.getWorkerId()))
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        long minimumAssigned = rows.stream()
                .mapToLong(row -> value(row.getEmergencyAssignmentsToday()))
                .min()
                .orElse(0);
        List<EmergencyRecipientProjection> leastUsed = rows.stream()
                .filter(row -> value(row.getEmergencyAssignmentsToday())
                        == minimumAssigned)
                .toList();
        EmergencyRecipientProjection chosen = leastUsed.get(
                ThreadLocalRandom.current().nextInt(leastUsed.size())
        );
        if (chosen.getWorkerId() == null
                || chosen.getManagerId() == null
                || chosen.getTargetGroupChatId() == null
                || chosen.getTargetGroupChatId() >= 0) {
            return null;
        }
        return new Recipient(
                chosen.getWorkerId(),
                chosen.getManagerId(),
                chosen.getTargetGroupChatId(),
                chosen.getWorkerName()
        );
    }

    private EmergencyCase emergencyCase(EmergencyCaseProjection row) {
        if (row.getShadowCaseId() == null
                || row.getSourceManagerId() == null
                || row.getSourceWorkerId() == null
                || row.getCompanyId() == null
                || row.getReviewId() == null) {
            return null;
        }
        return new EmergencyCase(
                row.getShadowCaseId(),
                row.getSourceManagerId(),
                row.getSourceWorkerId(),
                row.getCompanyId(),
                row.getCompanyTitle(),
                row.getReviewId(),
                row.getExhaustedWorkflowId()
        );
    }

    private LocalDateTime now() {
        var shadow = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(shadow));
    }

    private int remaining(int configured, long used) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(0L, (long) configured - Math.max(0L, used))
        );
    }

    private long value(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
