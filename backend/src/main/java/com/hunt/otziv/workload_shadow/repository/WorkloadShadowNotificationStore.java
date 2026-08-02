package com.hunt.otziv.workload_shadow.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.workload_shadow.health.dto.WorkloadShadowHealthData;
import com.hunt.otziv.workload_shadow.notification.dto.WorkloadShadowClaimedNotification;
import com.hunt.otziv.workload_shadow.notification.dto.WorkloadShadowDeliveryOutcome;
import com.hunt.otziv.workload_shadow.notification.dto.WorkloadShadowNotificationEvent;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowClaimedNotificationProjection;
import com.hunt.otziv.workload_shadow.repository.projection.WorkloadShadowHealthProjection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WorkloadShadowNotificationStore {

    private static final DateTimeFormatter DATABASE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final WorkloadShadowEventRepository eventRepository;
    private final WorkloadShadowRunRepository runRepository;
    private final WorkloadShadowWorkerDailyRepository workerDailyRepository;
    private final WorkloadShadowTransferRepository transferRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Long> findDueEventIds(LocalDateTime now, int limit) {
        return eventRepository.findDueEventIds(now, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claim(
            List<Long> eventIds,
            LocalDateTime now,
            LocalDateTime leaseUntil
    ) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return eventRepository.claimDueEvents(eventIds, now, leaseUntil);
    }

    @Transactional(readOnly = true)
    public List<WorkloadShadowClaimedNotification> findClaimed(
            List<Long> eventIds,
            LocalDateTime processingStartedAt,
            LocalDateTime leaseUntil
    ) {
        if (eventIds.isEmpty()) {
            return List.of();
        }
        return eventRepository.findClaimedEvents(
                        eventIds,
                        processingStartedAt,
                        leaseUntil
                ).stream()
                .map(this::toClaimedNotification)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int applyDeliveryOutcomes(
            List<WorkloadShadowDeliveryOutcome> outcomes,
            LocalDateTime processingStartedAt,
            LocalDateTime leaseUntil
    ) {
        if (outcomes.isEmpty()) {
            return 0;
        }
        return eventRepository.applyDeliveryOutcomes(
                outcomesJson(outcomes),
                processingStartedAt,
                leaseUntil
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelInactiveDeliveries(LocalDateTime now, int limit) {
        return eventRepository.cancelInactiveDeliveries(now, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failStaleRuns(
            LocalDateTime staleBefore,
            LocalDateTime now,
            int limit
    ) {
        return runRepository.failStaleRuns(staleBefore, now, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryStaleProcessingEvents(LocalDateTime now, int limit) {
        return eventRepository.retryStaleProcessingEvents(now, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteTerminalInactiveEvents(LocalDateTime cutoff, int limit) {
        return eventRepository.deleteTerminalInactiveEvents(cutoff, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteTerminalRuns(LocalDateTime cutoff, int limit) {
        return runRepository.deleteTerminalRuns(cutoff, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteFinalizedDaily(LocalDate cutoff, int limit) {
        return workerDailyRepository.deleteFinalizedDaily(cutoff, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteLateBatches(LocalDate cutoff, int limit) {
        return workerDailyRepository.deleteLateBatches(cutoff, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteInactiveResolvedTransferCases(LocalDateTime cutoff, int limit) {
        return transferRepository.deleteInactiveResolvedCases(cutoff, limit);
    }

    @Transactional(readOnly = true)
    public WorkloadShadowHealthData healthData(
            LocalDateTime now,
            LocalDateTime staleRunBefore
    ) {
        WorkloadShadowHealthProjection projection =
                eventRepository.healthData(now, staleRunBefore);
        return new WorkloadShadowHealthData(
                value(projection.getDueEvents()),
                value(projection.getProcessingEvents()),
                value(projection.getStaleProcessingEvents()),
                value(projection.getDeadEvents()),
                value(projection.getMissingGroupBindings()),
                value(projection.getRunningRuns()),
                value(projection.getStaleRunningRuns()),
                value(projection.getGraphWarningCases()),
                value(projection.getGraphErrorCases()),
                value(projection.getExpiredRecalculationLocks()),
                projection.getOldestDueEventAt(),
                projection.getLastSuccessfulRunAt(),
                projection.getLastSnapshotAt()
        );
    }

    private WorkloadShadowClaimedNotification toClaimedNotification(
            WorkloadShadowClaimedNotificationProjection projection
    ) {
        WorkloadShadowNotificationEvent event = new WorkloadShadowNotificationEvent(
                projection.getId(),
                projection.getSeverity(),
                projection.getEventType(),
                projection.getManagerId(),
                projection.getTitle(),
                projection.getMessage(),
                projection.getTargetGroupType(),
                projection.getTargetGroupChatId(),
                projection.getDeliveryAttempts() == null
                        ? 0
                        : projection.getDeliveryAttempts()
        );
        return new WorkloadShadowClaimedNotification(event);
    }

    private String outcomesJson(List<WorkloadShadowDeliveryOutcome> outcomes) {
        List<DeliveryOutcomePayload> payload = outcomes.stream()
                .map(outcome -> new DeliveryOutcomePayload(
                        outcome.eventId(),
                        outcome.deliveryStatus(),
                        outcome.deliveryAttempts(),
                        timestamp(outcome.deliveredAt()),
                        timestamp(outcome.nextAttemptAt()),
                        outcome.errorCode(),
                        bounded(outcome.error())
                ))
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось сериализовать результаты shadow-доставки",
                    exception
            );
        }
    }

    private String timestamp(LocalDateTime value) {
        return value == null ? null : value.format(DATABASE_TIMESTAMP);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private record DeliveryOutcomePayload(
            long eventId,
            String deliveryStatus,
            int deliveryAttempts,
            String deliveredAt,
            String nextAttemptAt,
            String errorCode,
            String error
    ) {
    }
}
