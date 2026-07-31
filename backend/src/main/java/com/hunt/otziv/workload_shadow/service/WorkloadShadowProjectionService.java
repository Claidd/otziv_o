package com.hunt.otziv.workload_shadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowProjectionService {

    private static final String SECTION_NEW = "NEW";
    private static final String SECTION_CORRECTION = "CORRECTION";
    private static final String SECTION_NAGUL = "NAGUL";
    private static final String SECTION_PUBLISH = "PUBLISH";
    private static final String SECTION_RECOVERY = "RECOVERY";
    private static final String SECTION_BAD = "BAD";
    private static final DateTimeFormatter SQL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Set<String> MANAGED_EVENT_TYPES = Set.of(
            "LATE_INCOMING_LOAD",
            // Legacy type is retained only so the next run resolves old active rows.
            // Per-manager audit groups are no longer a workload prerequisite.
            "MISSING_MANAGER_GROUP",
            "MISSING_WORKER_GROUP",
            "AMBIGUOUS_MANAGER_LINK"
    );

    private final WorkloadShadowProjectionRepository repository;
    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final WorkloadShadowSettingsService settingsService;

    @Transactional
    public WorkloadShadowRunService.RunResult recalculate(long runId, LocalDateTime requestedAt) {
        WorkloadShadowSettingsResponse settings = settingsService.current();
        LocalDateTime observedAt = requestedAt == null
                ? LocalDateTime.now(settingsService.zone(settings))
                : requestedAt;
        LocalDate progressDate = observedAt.toLocalDate();
        List<WorkerSubject> workers = loadWorkers();
        repository.closePreviousDayDecisions(progressDate);
        if (workers.isEmpty()) {
            repository.deactivateDailyBatchDecisions(progressDate);
            repository.deleteAllCurrent();
            resolveMissingEvents(observedAt);
            return new WorkloadShadowRunService.RunResult(0, 0, 0, 0, 0);
        }

        List<Long> workerIds = workers.stream().map(WorkerSubject::workerId).distinct().toList();
        int removedFutureDecisions = repository.deleteFutureDailyBatchDecisions(
                progressDate,
                observedAt
        );
        if (removedFutureDecisions > 0) {
            log.info(
                    "Removed {} workload shadow decisions with future source availability; "
                            + "they will be rebuilt from current source data",
                    removedFutureDecisions
            );
        }
        WalkEstimate walkEstimate = calculateWalkEstimate(progressDate, settings);
        persistWalkEstimate(walkEstimate, observedAt);
        List<WorkBatch> batches = activeBatches(
                workerIds,
                progressDate,
                observedAt,
                settings,
                walkEstimate.effectiveMinutes()
        );
        Map<Long, List<WorkBatch>> batchesByWorker = groupBatches(batches);
        Map<Long, WorkloadClassification> deferredAndBlocked = deferredAndBlockedUnits(
                workerIds,
                progressDate
        );
        Map<Long, Map<String, BatchDecision>> dailyBatchDecisions =
                dailyBatchDecisions(
                        workerIds,
                        progressDate,
                        observedAt,
                        settingsService.zone(settings)
                );
        Map<Long, LocalDateTime> observationWatermarks =
                dailyObservationWatermarks(workerIds, progressDate, settingsService.zone(settings));
        repository.deactivateDailyBatchDecisions(progressDate);
        LocalDateTime shiftStart = progressDate.atTime(settingsService.shiftStart(settings));
        LocalDateTime shiftEnd = progressDate.atTime(settingsService.shiftEnd(settings));
        Map<Long, CompletionStats> completions = completedUnits(workerIds, progressDate);
        Map<Long, HistoryStats> history = history(
                workerIds,
                progressDate,
                settings.lookbackDays(),
                !observedAt.isBefore(shiftEnd)
        );
        Map<Long, BigDecimal> historicalRatings = ratings(workerIds, progressDate, settings.lookbackDays());
        Map<Long, Integer> freezeCredits = freezeCredits(workerIds);
        Map<Long, WorkerSnapshot> snapshots = new LinkedHashMap<>();
        List<PendingEvent> pendingEvents = new ArrayList<>();
        int producedEvents = 0;

        for (WorkerSubject worker : workers) {
            List<WorkBatch> workerBatches = batchesByWorker.getOrDefault(worker.workerId(), List.of());
            CompletionStats completion = completions.getOrDefault(worker.workerId(), CompletionStats.empty());
            HistoryStats workerHistory = history.getOrDefault(worker.workerId(), HistoryStats.empty());
            WorkerSnapshot snapshot = snapshot(
                    worker,
                    workerBatches,
                    completion,
                    workerHistory,
                    historicalRatings.get(worker.workerId()),
                    freezeCredits.getOrDefault(worker.workerId(), 0),
                    settings,
                    progressDate,
                    observedAt,
                    shiftStart,
                    shiftEnd,
                    deferredAndBlocked.getOrDefault(
                            worker.workerId(),
                            WorkloadClassification.empty()
                    ),
                    dailyBatchDecisions.getOrDefault(worker.workerId(), Map.of()),
                    observationWatermarks.get(worker.workerId())
            );
            snapshots.put(worker.workerId(), snapshot);

            if (snapshot.lateExcludedUnits() > 0) {
                upsertEvent(
                        pendingEvents,
                        settings,
                        "LATE:" + progressDate + ":" + worker.workerId(),
                        "INFO",
                        "LATE_INCOMING_LOAD",
                        worker,
                        null,
                        null,
                        "Поздняя входящая нагрузка исключена из процента",
                        "НАБЛЮДЕНИЕ. У " + worker.workerName() + " исключено "
                                + snapshot.lateExcludedUnits() + " ед. поздней нагрузки ("
                                + snapshot.lateExcludedMinutes() + " мин.). Задачи остаются у специалиста "
                                + "и попадут в обязательную нагрузку следующего дня.",
                        observedAt
                );
                producedEvents++;
            }
            if (!worker.workerGroupConnected()) {
                upsertEvent(
                        pendingEvents,
                        settings,
                        "MISSING_WORKER_GROUP:" + worker.workerId(),
                        "WARNING",
                        "MISSING_WORKER_GROUP",
                        worker,
                        null,
                        null,
                        "Не подключена рабочая Telegram-группа специалиста",
                        "Для " + worker.workerName()
                                + " не привязана рабочая Telegram-группа. Личные сообщения не используются.",
                        observedAt
                );
                producedEvents++;
            }
            if (worker.managerLinkCount() != 1) {
                upsertEvent(
                        pendingEvents,
                        settings,
                        "AMBIGUOUS_MANAGER_LINK:" + worker.workerId(),
                        "CRITICAL",
                        "AMBIGUOUS_MANAGER_LINK",
                        worker,
                        null,
                        null,
                        "Неоднозначная связь специалиста с менеджерами",
                        "НАБЛЮДЕНИЕ. У " + worker.workerName() + " найдено "
                                + worker.managerLinkCount() + " связей с менеджерами. "
                                + "До исправления связи специалист исключён из рекомендаций "
                                + "на получение и передачу компаний.",
                        observedAt
                );
                producedEvents++;
            }
        }
        persistSnapshots(snapshots.values(), runId, observedAt, shiftEnd);
        persistDailyBatchDecisions(snapshots.values(), batchesByWorker);

        persistEvents(pendingEvents, observedAt, settings.alertCooldownMinutes());

        repository.deleteCurrentExceptRun(runId);
        resolveMissingEvents(observedAt);

        finalizePreviousSnapshots(progressDate, observedAt, settings);
        boolean finalizing = !observedAt.toLocalTime().isBefore(settingsService.shiftEnd(settings));
        LocalDate freezeThroughDate = finalizing ? progressDate : progressDate.minusDays(1);
        applyPendingFreezeSimulations(freezeThroughDate, settings);
        if (finalizing) {
            refreshCurrentFreezeCredits(progressDate);
        }

        int managerCount = (int) workers.stream().map(WorkerSubject::managerId).distinct().count();
        return new WorkloadShadowRunService.RunResult(
                managerCount,
                snapshots.size(),
                0,
                producedEvents,
                0
        );
    }

    public String instanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "unknown-instance";
        }
    }

    private List<WorkerSubject> loadWorkers() {
        return repository.findWorkers().stream().map(row -> new WorkerSubject(
                longValue(row.get("worker_id")),
                nullableLong(row.get("worker_user_id")),
                longValue(row.get("manager_id")),
                Math.max(0, intValue(row.get("manager_link_count"))),
                string(row.get("worker_name")),
                string(row.get("manager_name")),
                booleanValue(row.get("accepts_company_transfers")),
                nullableLong(row.get("worker_telegram_group_chat_id"))
        )).toList();
    }

    private List<WorkBatch> activeBatches(
            List<Long> workerIds,
            LocalDate date,
            LocalDateTime observedAt,
            WorkloadShadowSettingsResponse settings,
            int effectiveWalkMinutes
    ) {
        LocalDate nagulDate = date.plusDays(appSettingService.getInt(
                AppSettingService.NAGUL_LOOKAHEAD_DAYS,
                14
        ));
        ZoneId businessZone = settingsService.zone(settings);
        List<WorkBatch> result = new ArrayList<>();
        loadOrderBatches(workerIds, observedAt, settings, businessZone, result);
        loadNagulBatches(
                workerIds,
                nagulDate,
                observedAt,
                effectiveWalkMinutes,
                settings,
                businessZone,
                result
        );
        loadPublishBatches(workerIds, date, observedAt, settings, businessZone, result);
        loadBadBatches(workerIds, date, settings, businessZone, result);
        loadRecoveryBatches(workerIds, date, settings, businessZone, result);
        return result;
    }

    private Map<Long, WorkloadClassification> deferredAndBlockedUnits(
            List<Long> workerIds,
            LocalDate date
    ) {
        Map<Long, WorkloadClassification.Mutable> mutable = new HashMap<>();
        LocalDate nagulDate = date.plusDays(appSettingService.getInt(
                AppSettingService.NAGUL_LOOKAHEAD_DAYS,
                14
        ));
        repository.findDeferredAndBlockedUnits(workerIds, date, nagulDate).forEach(row -> {
            long workerId = longValue(row.get("worker_id"));
            WorkloadClassification.Mutable classification =
                    mutable.computeIfAbsent(workerId, ignored -> new WorkloadClassification.Mutable());
            classification.externalBlockedUnits += longValue(row.get("external_blocked_units"));
            classification.clientDeferredUnits += longValue(row.get("client_deferred_units"));
            classification.managerDeferredUnits += longValue(row.get("manager_deferred_units"));
        });

        Map<Long, WorkloadClassification> result = new HashMap<>();
        mutable.forEach((workerId, classification) ->
                result.put(workerId, classification.toClassification()));
        return result;
    }

    private Map<Long, Map<String, BatchDecision>> dailyBatchDecisions(
            List<Long> workerIds,
            LocalDate progressDate,
            LocalDateTime observedAt,
            ZoneId businessZone
    ) {
        Map<Long, Map<String, BatchDecision>> result = new HashMap<>();
        int ignoredFutureDecisions = 0;
        for (Map<String, Object> row : repository.findDailyBatchDecisions(
                workerIds,
                progressDate
        )) {
            long workerId = longValue(row.get("worker_id"));
            String batchKey = string(row.get("batch_key"));
            if (workerId <= 0 || batchKey.isBlank()) {
                continue;
            }
            DecisionCode decisionCode = DecisionCode.fromDatabase(row.get("decision_code"));
            LocalDateTime sourceAvailableAt =
                    toLocalDateTime(row.get("source_available_at"), businessZone);
            if (!isPersistedDecisionUsable(sourceAvailableAt, observedAt)) {
                ignoredFutureDecisions++;
                continue;
            }
            BatchDecision decision = new BatchDecision(
                    batchKey,
                    decisionCode,
                    DecisionOrigin.fromDatabase(row.get("decision_origin"), decisionCode),
                    string(row.get("cohort_key")),
                    Math.max(0, longValue(row.get("initial_units"))),
                    Math.max(0, longValue(row.get("initial_estimated_minutes"))),
                    toLocalDateTime(row.get("first_detected_at"), businessZone),
                    sourceAvailableAt,
                    Math.max(0, longValue(row.get("available_minutes_at_decision"))),
                    Math.max(0, longValue(row.get("cohort_estimated_minutes_at_decision")))
            );
            result.computeIfAbsent(workerId, ignored -> new LinkedHashMap<>())
                    .put(batchKey, decision);
        }
        if (ignoredFutureDecisions > 0) {
            log.info(
                    "Ignoring {} workload shadow decisions with future source availability; "
                            + "they will be recalculated from current source data",
                    ignoredFutureDecisions
            );
        }
        return result;
    }

    static boolean isPersistedDecisionUsable(
            LocalDateTime sourceAvailableAt,
            LocalDateTime observedAt
    ) {
        return sourceAvailableAt == null
                || observedAt == null
                || !sourceAvailableAt.isAfter(observedAt);
    }

    private Map<Long, LocalDateTime> dailyObservationWatermarks(
            List<Long> workerIds,
            LocalDate progressDate,
            ZoneId businessZone
    ) {
        Map<Long, LocalDateTime> result = new HashMap<>();
        repository.findDailyObservationWatermarks(workerIds, progressDate).forEach(row -> {
            long workerId = longValue(row.get("worker_id"));
            LocalDateTime lastSnapshotAt =
                    toLocalDateTime(row.get("last_snapshot_at"), businessZone);
            if (workerId > 0 && lastSnapshotAt != null) {
                result.put(workerId, lastSnapshotAt);
            }
        });
        return result;
    }

    private void persistDailyBatchDecisions(
            Collection<WorkerSnapshot> snapshots,
            Map<Long, List<WorkBatch>> batchesByWorker
    ) {
        if (snapshots.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkerSnapshot snapshot : snapshots) {
            Map<String, WorkBatch> batchesByKey = new LinkedHashMap<>();
            batchesByWorker.getOrDefault(snapshot.worker().workerId(), List.of())
                    .forEach(batch -> batchesByKey.put(batch.batchKey(), batch));
            snapshot.batchDecisions().values().forEach(decision -> {
                WorkBatch batch = batchesByKey.get(decision.batchKey());
                if (batch == null) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("progressDate", snapshot.progressDate().toString());
                row.put("workerId", snapshot.worker().workerId());
                row.put("batchKey", batch.batchKey());
                row.put("sectionCode", batch.section());
                row.put("decisionCode", decision.decisionCode().name());
                row.put("decisionOrigin", decision.decisionOrigin().name());
                row.put("cohortKey", decision.cohortKey());
                row.put("units", batch.units());
                row.put("estimatedMinutes", batch.estimatedMinutes());
                row.put("sourceAvailableAt", sqlDateTime(decision.sourceAvailableAt()));
                row.put(
                        "availableMinutesAtDecision",
                        decision.availableMinutesAtDecision()
                );
                row.put(
                        "cohortEstimatedMinutesAtDecision",
                        decision.cohortEstimatedMinutesAtDecision()
                );
                row.put("observedAt", sqlDateTime(decision.firstObservedAt()));
                rows.add(row);
            });
        }
        if (rows.isEmpty()) {
            return;
        }
        repository.upsertDailyBatchDecisions(json(rows));
    }

    private void loadOrderBatches(
            List<Long> workerIds,
            LocalDateTime observedAt,
            WorkloadShadowSettingsResponse settings,
            ZoneId businessZone,
            List<WorkBatch> target
    ) {
        repository.findOrderBatches(
                workerIds,
                observedAt,
                settings.shiftStart()
        ).forEach(row -> {
            String section = "Коррекция".equals(string(row.get("status_title")))
                    ? SECTION_CORRECTION
                    : SECTION_NEW;
            long units = longValue(row.get("units"));
            if (SECTION_NEW.equals(section) && units <= 0) {
                return;
            }
            units = Math.max(1, units);
            int unitMinutes = SECTION_CORRECTION.equals(section)
                    ? settings.correctionMinutesPerOrder()
                    : settings.newMinutesPerCard();
            target.add(batch(row, section, units, unitMinutes, businessZone));
        });
    }

    private void loadNagulBatches(
            List<Long> workerIds,
            LocalDate nagulDate,
            LocalDateTime observedAt,
            int effectiveWalkMinutes,
            WorkloadShadowSettingsResponse settings,
            ZoneId businessZone,
            List<WorkBatch> target
    ) {
        repository.findNagulBatches(
                workerIds,
                nagulDate,
                observedAt,
                settings.shiftStart()
        ).forEach(row -> target.add(batch(
                row,
                SECTION_NAGUL,
                positiveUnits(row.get("units")),
                Math.max(1, effectiveWalkMinutes),
                businessZone
        )));
    }

    private WalkEstimate calculateWalkEstimate(
            LocalDate progressDate,
            WorkloadShadowSettingsResponse settings
    ) {
        int minimumMinutes = Math.max(3, settings.walkMinimumMinutesPerCard());
        int defaultMinutes = Math.max(minimumMinutes, settings.walkMinutesPerCard());
        if (!settings.adaptiveEstimatesEnabled()) {
            return new WalkEstimate(0, 0, defaultMinutes, minimumMinutes, "DEFAULT");
        }

        Map<String, Object> row = repository.findWalkEstimate(
                progressDate.minusDays(Math.max(1, settings.lookbackDays())).atStartOfDay(),
                progressDate.plusDays(1).atStartOfDay()
        );
        if (row == null) {
            row = Map.of("sample_count", 0L, "average_seconds", 0L);
        }
        long sampleCount = longValue(row.get("sample_count"));
        long averageSeconds = longValue(row.get("average_seconds"));
        if (sampleCount < settings.adaptiveMinimumSamples() || averageSeconds <= 0) {
            return new WalkEstimate(
                    sampleCount,
                    averageSeconds,
                    defaultMinutes,
                    minimumMinutes,
                    "DEFAULT"
            );
        }

        int statisticalMinutes = (int) Math.ceil(averageSeconds / 60.0d);
        int effectiveMinutes = Math.max(minimumMinutes, Math.min(30, statisticalMinutes));
        return new WalkEstimate(
                sampleCount,
                averageSeconds,
                effectiveMinutes,
                minimumMinutes,
                "ADAPTIVE"
        );
    }

    private void persistWalkEstimate(WalkEstimate estimate, LocalDateTime calculatedAt) {
        repository.upsertWalkEstimate(
                estimate.sampleCount(),
                estimate.averageSeconds(),
                estimate.effectiveMinutes(),
                estimate.minimumMinutes(),
                estimate.source(),
                calculatedAt
        );
    }

    private void loadPublishBatches(
            List<Long> workerIds,
            LocalDate date,
            LocalDateTime observedAt,
            WorkloadShadowSettingsResponse settings,
            ZoneId businessZone,
            List<WorkBatch> target
    ) {
        repository.findPublishBatches(
                workerIds,
                date,
                observedAt,
                settings.shiftStart()
        ).forEach(row -> target.add(batch(
                row,
                SECTION_PUBLISH,
                positiveUnits(row.get("units")),
                settings.publishMinutesPerCard(),
                businessZone
        )));
    }

    private void loadBadBatches(
            List<Long> workerIds,
            LocalDate date,
            WorkloadShadowSettingsResponse settings,
            ZoneId businessZone,
            List<WorkBatch> target
    ) {
        repository.findBadBatches(
                workerIds,
                date,
                settings.shiftStart()
        ).forEach(row -> target.add(batch(
                row,
                SECTION_BAD,
                positiveUnits(row.get("units")),
                settings.badMinutesPerTask(),
                businessZone
        )));
    }

    private void loadRecoveryBatches(
            List<Long> workerIds,
            LocalDate date,
            WorkloadShadowSettingsResponse settings,
            ZoneId businessZone,
            List<WorkBatch> target
    ) {
        repository.findRecoveryBatches(
                workerIds,
                date,
                settings.shiftStart()
        ).forEach(row -> target.add(batch(
                row,
                SECTION_RECOVERY,
                positiveUnits(row.get("units")),
                settings.recoveryMinutesPerTask(),
                businessZone
        )));
    }

    private WorkBatch batch(
            Map<String, Object> row,
            String section,
            long units,
            int unitMinutes,
            ZoneId businessZone
    ) {
        Long workerId = nullableLong(row.get("worker_id"));
        Long orderId = nullableLong(row.get("order_id"));
        return new WorkBatch(
                workerId == null ? 0 : workerId,
                nullableLong(row.get("company_id")),
                orderId,
                section,
                units,
                Math.max(1, unitMinutes),
                toLocalDateTime(row.get("available_at"), businessZone),
                string(row.get("batch_key")).isBlank()
                        ? section + ":" + (orderId == null ? "unknown" : orderId)
                        : string(row.get("batch_key"))
        );
    }

    private Map<Long, CompletionStats> completedUnits(List<Long> workerIds, LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        Map<Long, CompletionStats.Mutable> mutable = new HashMap<>();

        repository.findCorrectionCompletions(workerIds, from, to).forEach(row -> {
            long workerId = longValue(row.get("worker_id"));
            long units = longValue(row.get("units"));
            CompletionStats.Mutable stats = mutable.computeIfAbsent(workerId, ignored -> new CompletionStats.Mutable());
            if ("Коррекция".equals(string(row.get("old_status")))) {
                stats.correction += units;
            } else {
                stats.newUnits += units;
            }
        });

        repository.findUnitCompletions(workerIds, date, from, to).forEach(row -> {
            long workerId = longValue(row.get("worker_id"));
            long units = longValue(row.get("units"));
            CompletionStats.Mutable stats = mutable.computeIfAbsent(workerId, ignored -> new CompletionStats.Mutable());
            switch (string(row.get("action"))) {
                case "REVIEW_TEXT_UPDATE" -> stats.newUnits += units;
                case "REVIEW_NAGUL" -> stats.nagul += units;
                case "REVIEW_PUBLISH" -> stats.publish += units;
                case "BAD_TASK_COMPLETE" -> stats.bad += units;
                case "RECOVERY_TASK_COMPLETE" -> stats.recovery += units;
                default -> {
                }
            }
        });

        Map<Long, CompletionStats> result = new HashMap<>();
        mutable.forEach((workerId, stats) -> result.put(workerId, stats.toStats()));
        return result;
    }

    private Map<Long, HistoryStats> history(
            List<Long> workerIds,
            LocalDate throughDate,
            int lookbackDays,
            boolean includeThroughDate
    ) {
        LocalDate monthFrom = throughDate.withDayOfMonth(1);
        LocalDate toExclusive = includeThroughDate ? throughDate.plusDays(1) : throughDate;
        LocalDate rollingFrom = toExclusive.minusDays(Math.max(1, lookbackDays));
        LocalDate from = monthFrom.isBefore(rollingFrom) ? monthFrom : rollingFrom;
        Map<Long, HistoryStats> result = new HashMap<>();
        repository.findHistory(
                workerIds,
                from,
                monthFrom,
                throughDate,
                toExclusive
        ).forEach(row -> result.put(
                longValue(row.get("worker_id")),
                new HistoryStats(
                        intValue(row.get("hundred_days")),
                        intValue(row.get("failure_days")),
                        intValue(row.get("protected_days")),
                        intValue(row.get("rolling_hundred_days")),
                        intValue(row.get("rolling_failure_days")),
                        booleanValue(row.get("last_day_reached_100")),
                        toLocalDate(row.get("latest_progress_date"))
                )
        ));
        return result;
    }

    private Map<Long, BigDecimal> ratings(
            List<Long> workerIds,
            LocalDate throughDate,
            int lookbackDays
    ) {
        Map<Long, BigDecimal> result = new HashMap<>();
        repository.findRatings(
                workerIds,
                throughDate.minusDays(Math.max(1, lookbackDays)),
                throughDate
        ).forEach(row -> result.put(
                longValue(row.get("worker_id")),
                decimal(row.get("rating"))
        ));
        return result;
    }

    private Map<Long, Integer> freezeCredits(List<Long> workerIds) {
        Map<Long, Integer> result = new HashMap<>();
        repository.findFreezeCredits(workerIds).forEach(row ->
                result.put(longValue(row.get("worker_id")), intValue(row.get("available_credits"))));
        return result;
    }

    private WorkerSnapshot snapshot(
            WorkerSubject worker,
            List<WorkBatch> batches,
            CompletionStats completion,
            HistoryStats history,
            BigDecimal historicalRating,
            int freezeCredits,
            WorkloadShadowSettingsResponse settings,
            LocalDate progressDate,
            LocalDateTime observedAt,
            LocalDateTime shiftStart,
            LocalDateTime shiftEnd,
            WorkloadClassification deferredAndBlocked,
            Map<String, BatchDecision> persistedBatchDecisions,
            LocalDateTime previousObservationAt
    ) {
        long active = batches.stream().mapToLong(WorkBatch::units).sum();
        long completed = completion.total();
        long lateExcluded = 0;
        long lateMinutes = 0;
        long estimatedRemainingMinutes = 0;
        long plannedUnits = 0;
        long incomingUnits = 0;
        long urgentUnits = 0;
        LocalDateTime lastAvailableAt = null;
        Map<String, Long> sectionCounts = new HashMap<>();

        List<WorkBatch> orderedBatches = batches.stream()
                .sorted(Comparator.comparing(WorkBatch::availableAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        boolean recoveredObservation = hasMissedObservationWindow(
                previousObservationAt,
                observedAt,
                shiftStart,
                settings
        );
        Map<String, BatchDecision> batchDecisions = classifyDailyBatchDecisions(
                orderedBatches,
                persistedBatchDecisions,
                progressDate,
                observedAt,
                shiftStart,
                shiftEnd,
                recoveredObservation
        );
        for (WorkBatch batch : orderedBatches) {
            long estimatedMinutes = batch.estimatedMinutes();
            estimatedRemainingMinutes += estimatedMinutes;
            sectionCounts.merge(batch.section(), batch.units(), Long::sum);
            if (batch.availableAt() == null || batch.availableAt().isBefore(shiftStart)) {
                plannedUnits += batch.units();
            } else {
                incomingUnits += batch.units();
            }
            if (SECTION_PUBLISH.equals(batch.section())
                    || SECTION_RECOVERY.equals(batch.section())
                    || SECTION_BAD.equals(batch.section())) {
                urgentUnits += batch.units();
            }
            if (batch.availableAt() != null
                    && (lastAvailableAt == null || batch.availableAt().isAfter(lastAvailableAt))) {
                lastAvailableAt = batch.availableAt();
            }
            BatchDecision decision = batchDecisions.get(batch.batchKey());
            if (decision != null && decision.decisionCode() == DecisionCode.LATE) {
                lateExcluded += batch.units();
                lateMinutes += estimatedMinutes;
            }
        }

        long feasible = Math.max(0, active - lateExcluded);
        boolean currentDayFinalized = !observedAt.isBefore(shiftEnd);
        long eligible = eligibleUnitsForSnapshot(
                completed,
                feasible,
                deferredAndBlocked.externalBlockedUnits(),
                currentDayFinalized
        );
        BigDecimal progressPercent = progressPercent(completed, eligible);
        HistoryStats finalizedHistory = includeCurrentFinalizedDay(
                history,
                progressDate,
                observedAt,
                shiftEnd,
                eligible,
                reached100ForFinalization(eligible, progressPercent),
                freezeCredits
        );
        MonthStats monthStats = currentMonthStats(finalizedHistory);
        int ratingEvaluatedDays =
                finalizedHistory.rollingHundredDays() + finalizedHistory.rollingFailureDays();
        BigDecimal fallbackRating = ratingEvaluatedDays == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(finalizedHistory.rollingHundredDays())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(ratingEvaluatedDays), 2, RoundingMode.HALF_UP);
        BigDecimal rating = historicalRating != null && historicalRating.compareTo(BigDecimal.ZERO) > 0
                ? historicalRating.min(BigDecimal.valueOf(100))
                : fallbackRating;
        int transferStage = monthStats.failureDays() <= settings.allowedFailureDays()
                ? 0
                : Math.min(3, monthStats.failureDays() - settings.allowedFailureDays());
        boolean recipientEligible = worker.managerLinkCount() == 1 && isRecipientEligible(
                worker.acceptsCompanyTransfers(),
                worker.workerGroupConnected(),
                monthStats.evaluatedDays(),
                monthStats.hundredDays(),
                finalizedHistory.lastDayReached100(),
                rating,
                monthStats.hundredPercentRate(),
                monthStats.failureDays(),
                settings.recipientMinimumRating(),
                settings.recipientMinimumHundredPercentRate(),
                settings.recipientMaximumFailureDays()
        );
        String diagnosticStatus = worker.managerLinkCount() != 1
                ? "AMBIGUOUS_MANAGER_LINK"
                : (!worker.workerGroupConnected() ? "MISSING_WORKER_GROUP" : "OK");

        return new WorkerSnapshot(
                worker,
                progressDate,
                observedAt,
                completed,
                active,
                lateExcluded,
                lateMinutes,
                eligible,
                feasible,
                progressPercent,
                estimatedRemainingMinutes,
                plannedUnits,
                incomingUnits,
                urgentUnits,
                deferredAndBlocked.externalBlockedUnits(),
                deferredAndBlocked.clientDeferredUnits(),
                deferredAndBlocked.managerDeferredUnits(),
                sectionCounts.getOrDefault(SECTION_NEW, 0L),
                sectionCounts.getOrDefault(SECTION_CORRECTION, 0L),
                sectionCounts.getOrDefault(SECTION_NAGUL, 0L),
                sectionCounts.getOrDefault(SECTION_PUBLISH, 0L),
                sectionCounts.getOrDefault(SECTION_RECOVERY, 0L),
                sectionCounts.getOrDefault(SECTION_BAD, 0L),
                rating,
                monthStats.hundredDays(),
                monthStats.failureDays(),
                Math.max(0, freezeCredits),
                transferStage,
                finalizedHistory.lastDayReached100(),
                recipientEligible,
                diagnosticStatus,
                lastAvailableAt,
                Map.copyOf(batchDecisions)
        );
    }

    private boolean hasMissedObservationWindow(
            LocalDateTime previousObservationAt,
            LocalDateTime observedAt,
            LocalDateTime shiftStart,
            WorkloadShadowSettingsResponse settings
    ) {
        if (observedAt == null || shiftStart == null || !observedAt.isAfter(shiftStart)) {
            return false;
        }
        long minutesUntilShiftEnd = ChronoUnit.MINUTES.between(
                observedAt.toLocalTime(),
                settingsService.shiftEnd(settings)
        );
        int expectedInterval = minutesUntilShiftEnd >= 0
                && minutesUntilShiftEnd <= settings.nearEndWindowMinutes()
                ? settings.nearEndIntervalMinutes()
                : settings.schedulerIntervalMinutes();
        long maximumExpectedGap = Math.max(10L, Math.multiplyExact(expectedInterval, 2L));
        LocalDateTime baseline = previousObservationAt == null
                ? shiftStart
                : previousObservationAt;
        if (baseline.isAfter(observedAt)) {
            return false;
        }
        return ChronoUnit.MINUTES.between(baseline, observedAt) > maximumExpectedGap;
    }

    static Map<String, BatchDecision> classifyDailyBatchDecisions(
            List<WorkBatch> batches,
            Map<String, BatchDecision> persistedDecisions,
            LocalDate progressDate,
            LocalDateTime observedAt,
            LocalDateTime shiftStart,
            LocalDateTime shiftEnd,
            boolean recoveredObservation
    ) {
        if (batches == null || batches.isEmpty()) {
            return Map.of();
        }
        List<WorkBatch> ordered = batches.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        WorkBatch::availableAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ).thenComparing(WorkBatch::batchKey))
                .toList();
        Map<String, BatchDecision> previous = persistedDecisions == null
                ? Map.of()
                : persistedDecisions;
        Map<String, BatchDecision> result = new LinkedHashMap<>();
        Set<String> partiallyCompletedLateCohorts =
                partiallyCompletedLateCohorts(ordered, previous);
        long remainingCapacity = remainingShiftMinutes(observedAt, shiftStart, shiftEnd);
        long mandatoryMinutesBeforeIncoming = 0;
        List<WorkBatch> unseen = new ArrayList<>();

        for (WorkBatch batch : ordered) {
            BatchDecision persisted = previous.get(batch.batchKey());
            if (persisted == null) {
                unseen.add(batch);
                continue;
            }
            BatchDecision effective = persisted;
            if (persisted.decisionCode() == DecisionCode.LATE
                    && partiallyCompletedLateCohorts.contains(
                            effectiveCohortKey(persisted)
                    )) {
                effective = new BatchDecision(
                        persisted.batchKey(),
                        DecisionCode.MANDATORY,
                        DecisionOrigin.PARTIAL_COMPLETION,
                        persisted.cohortKey(),
                        persisted.initialUnits(),
                        persisted.initialEstimatedMinutes(),
                        persisted.firstObservedAt(),
                        persisted.sourceAvailableAt(),
                        persisted.availableMinutesAtDecision(),
                        persisted.cohortEstimatedMinutesAtDecision()
                );
            }
            result.put(batch.batchKey(), effective);
            if (effective.decisionCode() == DecisionCode.MANDATORY) {
                mandatoryMinutesBeforeIncoming += batch.estimatedMinutes();
                remainingCapacity = subtractCapacity(remainingCapacity, batch.estimatedMinutes());
            }
        }

        List<WorkBatch> incoming = new ArrayList<>();
        for (WorkBatch batch : unseen) {
            LocalDateTime availableAt = batch.availableAt();
            boolean priorDayCarryOver = availableAt == null
                    || progressDate == null
                    || availableAt.toLocalDate().isBefore(progressDate);
            boolean plannedBeforeShift = !priorDayCarryOver
                    && shiftStart != null
                    && availableAt.isBefore(shiftStart);
            if (!priorDayCarryOver && !plannedBeforeShift) {
                incoming.add(batch);
                continue;
            }
            DecisionOrigin origin = priorDayCarryOver
                    ? DecisionOrigin.CARRY_OVER
                    : DecisionOrigin.LIVE;
            String cohortKey = cohortKey(batch);
            result.put(batch.batchKey(), new BatchDecision(
                    batch.batchKey(),
                    DecisionCode.MANDATORY,
                    origin,
                    cohortKey,
                    batch.units(),
                    batch.estimatedMinutes(),
                    observedAt,
                    availableAt,
                    remainingCapacity,
                    batch.estimatedMinutes()
            ));
            mandatoryMinutesBeforeIncoming += batch.estimatedMinutes();
            remainingCapacity = subtractCapacity(remainingCapacity, batch.estimatedMinutes());
        }

        Map<CohortKey, List<WorkBatch>> cohorts = new LinkedHashMap<>();
        for (WorkBatch batch : incoming) {
            LocalDateTime normalizedAvailableAt = batch.availableAt() == null
                    ? null
                    : batch.availableAt().truncatedTo(ChronoUnit.MINUTES);
            cohorts.computeIfAbsent(
                    new CohortKey(
                            batch.section(),
                            batch.orderId(),
                            normalizedAvailableAt,
                            batch.orderId() == null ? batch.batchKey() : ""
                    ),
                    ignored -> new ArrayList<>()
            ).add(batch);
        }
        LocalDateTime recoveredCursor = shiftStart;
        if (recoveredCursor != null && mandatoryMinutesBeforeIncoming > 0) {
            recoveredCursor = recoveredCursor.plusMinutes(mandatoryMinutesBeforeIncoming);
        }
        for (List<WorkBatch> cohort : cohorts.values()) {
            long cohortMinutes = cohort.stream().mapToLong(WorkBatch::estimatedMinutes).sum();
            WorkBatch first = cohort.get(0);
            long decisionCapacity = remainingCapacity;
            DecisionCode decisionCode;
            DecisionOrigin decisionOrigin;
            if (recoveredObservation) {
                LocalDateTime recoveredStart = recoveredCursor;
                if (recoveredStart == null
                        || first.availableAt() != null
                        && first.availableAt().isAfter(recoveredStart)) {
                    recoveredStart = first.availableAt();
                }
                decisionCapacity = remainingShiftMinutes(
                        recoveredStart,
                        shiftStart,
                        shiftEnd
                );
                decisionCode = cohortMinutes > decisionCapacity
                        ? DecisionCode.LATE
                        : DecisionCode.MANDATORY;
                decisionOrigin = decisionCode == DecisionCode.LATE
                        ? DecisionOrigin.RECOVERED_LATE
                        : DecisionOrigin.RECOVERED_MANDATORY;
                if (decisionCode == DecisionCode.MANDATORY && recoveredStart != null) {
                    recoveredCursor = recoveredStart.plusMinutes(cohortMinutes);
                }
            } else {
                decisionCode = cohortMinutes > remainingCapacity
                        ? DecisionCode.LATE
                        : DecisionCode.MANDATORY;
                decisionOrigin = DecisionOrigin.LIVE;
            }
            String groupKey = cohortKey(first);
            for (WorkBatch batch : cohort) {
                result.put(batch.batchKey(), new BatchDecision(
                        batch.batchKey(),
                        decisionCode,
                        decisionOrigin,
                        groupKey,
                        batch.units(),
                        batch.estimatedMinutes(),
                        observedAt,
                        batch.availableAt(),
                        decisionCapacity,
                        cohortMinutes
                ));
            }
            if (decisionCode == DecisionCode.MANDATORY) {
                remainingCapacity = subtractCapacity(remainingCapacity, cohortMinutes);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * A late cohort is forgiven only while nobody has started it. Once at least one
     * unit disappears from an otherwise still-active cohort, the specialist has
     * chosen to work on that cohort and every remaining unit becomes mandatory for
     * the current day. Persisted rows for completed units intentionally remain in
     * {@code workload_shadow_late_batches}, which lets this comparison survive
     * scheduler restarts without relying on an in-memory counter.
     */
    private static Set<String> partiallyCompletedLateCohorts(
            List<WorkBatch> activeBatches,
            Map<String, BatchDecision> persistedDecisions
    ) {
        if (activeBatches == null
                || activeBatches.isEmpty()
                || persistedDecisions == null
                || persistedDecisions.isEmpty()) {
            return Set.of();
        }
        Map<String, Long> initialUnitsByCohort = new HashMap<>();
        for (BatchDecision decision : persistedDecisions.values()) {
            if (decision == null || decision.decisionCode() != DecisionCode.LATE) {
                continue;
            }
            initialUnitsByCohort.merge(
                    effectiveCohortKey(decision),
                    Math.max(0, decision.initialUnits()),
                    WorkloadShadowProjectionService::safeAdd
            );
        }
        if (initialUnitsByCohort.isEmpty()) {
            return Set.of();
        }
        Map<String, Long> remainingUnitsByCohort = new HashMap<>();
        for (WorkBatch batch : activeBatches) {
            BatchDecision decision = persistedDecisions.get(batch.batchKey());
            if (decision == null || decision.decisionCode() != DecisionCode.LATE) {
                continue;
            }
            remainingUnitsByCohort.merge(
                    effectiveCohortKey(decision),
                    Math.max(0, batch.units()),
                    WorkloadShadowProjectionService::safeAdd
            );
        }
        Set<String> result = new java.util.LinkedHashSet<>();
        initialUnitsByCohort.forEach((cohort, initialUnits) -> {
            long remainingUnits = remainingUnitsByCohort.getOrDefault(cohort, 0L);
            if (remainingUnits > 0 && remainingUnits < initialUnits) {
                result.add(cohort);
            }
        });
        return Set.copyOf(result);
    }

    private static String effectiveCohortKey(BatchDecision decision) {
        if (decision.cohortKey() == null || decision.cohortKey().isBlank()) {
            return decision.batchKey();
        }
        return decision.cohortKey();
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long remainingShiftMinutes(
            LocalDateTime from,
            LocalDateTime shiftStart,
            LocalDateTime shiftEnd
    ) {
        if (shiftEnd == null) {
            return 0;
        }
        LocalDateTime effectiveStart = from == null ? shiftStart : from;
        if (effectiveStart == null) {
            return 0;
        }
        if (shiftStart != null && effectiveStart.isBefore(shiftStart)) {
            effectiveStart = shiftStart;
        }
        if (!effectiveStart.isBefore(shiftEnd)) {
            return 0;
        }
        return Math.max(0, ChronoUnit.MINUTES.between(effectiveStart, shiftEnd));
    }

    private static long subtractCapacity(long capacity, long requiredMinutes) {
        return Math.max(0, capacity - Math.max(0, requiredMinutes));
    }

    private static String cohortKey(WorkBatch batch) {
        String orderPart = batch.orderId() == null
                ? batch.batchKey()
                : String.valueOf(batch.orderId());
        String availablePart = batch.availableAt() == null
                ? "UNKNOWN"
                : batch.availableAt()
                        .truncatedTo(ChronoUnit.MINUTES)
                        .format(SQL_DATE_TIME);
        String value = batch.section() + ":" + orderPart + ":" + availablePart;
        return value.length() <= 190 ? value : value.substring(0, 190);
    }

    static HistoryStats includeCurrentFinalizedDay(
            HistoryStats history,
            LocalDate progressDate,
            LocalDateTime observedAt,
            LocalDateTime shiftEnd,
            long eligibleUnits,
            boolean reached100,
            int availableFreezeCredits
    ) {
        HistoryStats safeHistory = history == null ? HistoryStats.empty() : history;
        boolean currentDayAlreadyIncluded =
                progressDate != null && progressDate.equals(safeHistory.latestProgressDate());
        boolean currentDayFinalized =
                observedAt != null && shiftEnd != null && !observedAt.isBefore(shiftEnd);
        if (!currentDayFinalized
                || currentDayAlreadyIncluded
                || eligibleUnits <= 0
                || progressDate == null) {
            return safeHistory;
        }
        if (reached100) {
            return new HistoryStats(
                    safeHistory.hundredDays() + 1,
                    safeHistory.failureDays(),
                    safeHistory.protectedDays(),
                    safeHistory.rollingHundredDays() + 1,
                    safeHistory.rollingFailureDays(),
                    true,
                    progressDate
            );
        }
        boolean protectedByFreeze = availableFreezeCredits > 0;
        return new HistoryStats(
                safeHistory.hundredDays(),
                safeHistory.failureDays() + (protectedByFreeze ? 0 : 1),
                safeHistory.protectedDays() + (protectedByFreeze ? 1 : 0),
                safeHistory.rollingHundredDays(),
                safeHistory.rollingFailureDays() + (protectedByFreeze ? 0 : 1),
                false,
                progressDate
        );
    }

    static boolean reached100Now(long eligibleUnits, BigDecimal progressPercent) {
        return eligibleUnits > 0
                && progressPercent != null
                && progressPercent.compareTo(BigDecimal.valueOf(100)) >= 0;
    }

    static BigDecimal progressPercent(long completedUnits, long eligibleUnits) {
        long safeEligible = Math.max(0, eligibleUnits);
        if (safeEligible == 0) {
            return BigDecimal.valueOf(100);
        }
        return BigDecimal.valueOf(Math.max(0, completedUnits))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(safeEligible), 2, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
    }

    static long eligibleUnitsForSnapshot(
            long completedUnits,
            long feasibleUnits,
            long externalBlockedUnits,
            boolean currentDayFinalized
    ) {
        long eligible = safeAdd(
                Math.max(0, completedUnits),
                Math.max(0, feasibleUnits)
        );
        return currentDayFinalized
                ? safeAdd(eligible, Math.max(0, externalBlockedUnits))
                : eligible;
    }

    static boolean reached100ForFinalization(
            long eligibleUnits,
            BigDecimal progressPercent
    ) {
        return reached100Now(eligibleUnits, progressPercent);
    }

    static MonthStats currentMonthStats(HistoryStats history) {
        HistoryStats safeHistory = history == null ? HistoryStats.empty() : history;
        int evaluatedDays = safeHistory.hundredDays() + safeHistory.failureDays();
        BigDecimal hundredPercentRate = evaluatedDays == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(safeHistory.hundredDays())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(evaluatedDays), 2, RoundingMode.HALF_UP);
        return new MonthStats(
                safeHistory.hundredDays(),
                safeHistory.failureDays(),
                evaluatedDays,
                hundredPercentRate
        );
    }

    static boolean isRecipientEligible(
            boolean acceptsCompanyTransfers,
            boolean workerGroupConnected,
            int evaluatedDays,
            int finalizedHundredPercentDays,
            boolean lastDayReached100,
            BigDecimal finalizedRating,
            BigDecimal finalizedHundredPercentRate,
            int monthFailureDays,
            int minimumRating,
            int minimumHundredPercentRate,
            int maximumFailureDays
    ) {
        BigDecimal safeRating = finalizedRating == null ? BigDecimal.ZERO : finalizedRating;
        BigDecimal safeHundredRate = finalizedHundredPercentRate == null
                ? BigDecimal.ZERO
                : finalizedHundredPercentRate;
        return acceptsCompanyTransfers
                && workerGroupConnected
                && evaluatedDays > 0
                && finalizedHundredPercentDays > 0
                && lastDayReached100
                && safeRating.compareTo(BigDecimal.valueOf(minimumRating)) >= 0
                && safeHundredRate.compareTo(BigDecimal.valueOf(minimumHundredPercentRate)) >= 0
                && monthFailureDays <= maximumFailureDays;
    }

    private void persistSnapshots(
            Collection<WorkerSnapshot> snapshots,
            long runId,
            LocalDateTime observedAt,
            LocalDateTime shiftEnd
    ) {
        if (snapshots.isEmpty()) {
            return;
        }
        boolean finalized = !observedAt.isBefore(shiftEnd);
        String snapshotsJson = json(snapshots.stream().map(this::snapshotRow).toList());
        repository.upsertCurrentSnapshots(snapshotsJson, runId);
        repository.upsertDailySnapshots(snapshotsJson, finalized, observedAt);
    }

    private Map<String, Object> snapshotRow(WorkerSnapshot snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workerId", snapshot.worker().workerId());
        row.put("workerUserId", snapshot.worker().workerUserId());
        row.put("managerId", snapshot.worker().managerId());
        row.put("progressDate", snapshot.progressDate().toString());
        row.put("snapshotAt", sqlDateTime(snapshot.snapshotAt()));
        row.put("completedUnits", snapshot.completedUnits());
        row.put("activeUnits", snapshot.activeUnits());
        row.put("lateExcludedUnits", snapshot.lateExcludedUnits());
        row.put("eligibleUnits", snapshot.eligibleUnits());
        row.put("progressPercent", snapshot.progressPercent());
        row.put("feasibleUnits", snapshot.feasibleUnits());
        row.put("estimatedRemainingMinutes", snapshot.estimatedRemainingMinutes());
        row.put("plannedUnits", snapshot.plannedUnits());
        row.put("incomingUnits", snapshot.incomingUnits());
        row.put("urgentUnits", snapshot.urgentUnits());
        row.put("externalBlockedUnits", snapshot.externalBlockedUnits());
        row.put("clientDeferredUnits", snapshot.clientDeferredUnits());
        row.put("managerDeferredUnits", snapshot.managerDeferredUnits());
        row.put("newUnits", snapshot.newUnits());
        row.put("correctionUnits", snapshot.correctionUnits());
        row.put("nagulUnits", snapshot.nagulUnits());
        row.put("publishUnits", snapshot.publishUnits());
        row.put("recoveryUnits", snapshot.recoveryUnits());
        row.put("badUnits", snapshot.badUnits());
        row.put("rating", snapshot.rating());
        row.put("hundredPercentDays", snapshot.hundredPercentDays());
        row.put("failureDays", snapshot.failureDays());
        row.put("freezeCredits", snapshot.freezeCredits());
        row.put("transferStage", snapshot.transferStage());
        row.put("lastDayReached100", snapshot.lastDayReached100());
        row.put("acceptsCompanyTransfers", snapshot.worker().acceptsCompanyTransfers());
        row.put("recipientEligible", snapshot.recipientEligible());
        row.put("workerGroupConnected", snapshot.worker().workerGroupConnected());
        row.put("diagnosticStatus", snapshot.diagnosticStatus());
        row.put("lastAvailableAt", sqlDateTime(snapshot.lastAvailableAt()));
        row.put(
                "reached100",
                reached100Now(snapshot.eligibleUnits(), snapshot.progressPercent())
        );
        return row;
    }

    private void applyPendingFreezeSimulations(
            LocalDate throughDate,
            WorkloadShadowSettingsResponse settings
    ) {
        if (throughDate == null) {
            return;
        }
        List<Map<String, Object>> rows = repository.findPendingFreezeEvaluationRows(throughDate);
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, FreezeAccumulator> states = new LinkedHashMap<>();
        List<Map<String, Object>> dailyOutcomes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long workerId = longValue(row.get("worker_id"));
            LocalDate progressDate = toLocalDate(row.get("progress_date"));
            if (workerId <= 0 || progressDate == null) {
                continue;
            }
            FreezeAccumulator state = states.computeIfAbsent(workerId, ignored ->
                    new FreezeAccumulator(
                            workerId,
                            intValue(row.get("available_credits")),
                            intValue(row.get("successful_days")),
                            intValue(row.get("earned_total")),
                            intValue(row.get("used_total"))
                    ));
            boolean freezeApplied = state.apply(
                    longValue(row.get("eligible_units")),
                    booleanValue(row.get("reached_100")),
                    settings
            );
            state.lastEvaluatedDate = progressDate;
            dailyOutcomes.add(Map.of(
                    "workerId", workerId,
                    "progressDate", progressDate.toString(),
                    "freezeApplied", freezeApplied
            ));
        }
        if (states.isEmpty()) {
            return;
        }
        List<Map<String, Object>> accountOutcomes = states.values().stream()
                .map(FreezeAccumulator::toRow)
                .toList();
        repository.upsertFreezeAccounts(json(accountOutcomes));
        repository.applyDailyFreezes(json(dailyOutcomes));
    }

    private void refreshCurrentFreezeCredits(LocalDate progressDate) {
        repository.refreshCurrentFreezeCredits(progressDate);
    }

    private void finalizePreviousSnapshots(
            LocalDate progressDate,
            LocalDateTime now,
            WorkloadShadowSettingsResponse settings
    ) {
        repository.emitMissedFinalSnapshotEvents(
                progressDate,
                now,
                now.minusMinutes(Math.max(5, settings.alertCooldownMinutes())),
                settings.groupNotificationsEnabled(),
                settings.notificationGroupChatId()
        );
        repository.finalizePreviousSnapshots(progressDate, now);
    }

    private void upsertEvent(
            List<PendingEvent> target,
            WorkloadShadowSettingsResponse settings,
            String deduplicationKey,
            String severity,
            String eventType,
            WorkerSubject subject,
            Long companyId,
            Long transferCaseId,
            String title,
            String message,
            LocalDateTime now
    ) {
        Long targetChatId = settings.notificationGroupChatId();
        boolean notificationsEnabled = settings.groupNotificationsEnabled();
        boolean routeValid = targetChatId != null && targetChatId < 0;
        target.add(new PendingEvent(
                limit(deduplicationKey, 190),
                limit(severity, 16),
                limit(eventType, 48),
                subject == null ? null : subject.managerId(),
                subject == null ? null : subject.workerId(),
                companyId,
                transferCaseId,
                limit(title, 220),
                limit(message, 2000),
                targetChatId,
                !notificationsEnabled
                        ? "SKIPPED"
                        : routeValid ? "PENDING" : "MISSING_GROUP_BINDING",
                now,
                notificationsEnabled && routeValid ? now : null
        ));
    }

    private void persistEvents(
            List<PendingEvent> events,
            LocalDateTime observedAt,
            int cooldownMinutes
    ) {
        if (events.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = events.stream().map(event -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deduplicationKey", event.deduplicationKey());
            row.put("severity", event.severity());
            row.put("eventType", event.eventType());
            row.put("managerId", event.managerId());
            row.put("workerId", event.workerId());
            row.put("companyId", event.companyId());
            row.put("transferCaseId", event.transferCaseId());
            row.put("title", event.title());
            row.put("message", event.message());
            row.put("targetGroupChatId", event.targetGroupChatId());
            row.put("deliveryStatus", event.deliveryStatus());
            row.put("observedAt", sqlDateTime(event.observedAt()));
            row.put("nextAttemptAt", sqlDateTime(event.nextAttemptAt()));
            return row;
        }).toList();
        repository.upsertEvents(
                json(rows),
                observedAt.minusMinutes(Math.max(5, cooldownMinutes))
        );
    }

    private void resolveMissingEvents(LocalDateTime observedAt) {
        repository.resolveMissingEvents(MANAGED_EVENT_TYPES, observedAt);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать пакет workload shadow", exception);
        }
    }

    private String sqlDateTime(LocalDateTime value) {
        return value == null ? null : value.format(SQL_DATE_TIME);
    }

    private Map<Long, List<WorkBatch>> groupBatches(List<WorkBatch> batches) {
        Map<Long, List<WorkBatch>> result = new HashMap<>();
        if (batches != null) {
            for (WorkBatch batch : batches) {
                if (batch == null || batch.workerId() <= 0) {
                    continue;
                }
                result.computeIfAbsent(batch.workerId(), ignored -> new ArrayList<>()).add(batch);
            }
        }
        return result;
    }

    private long positiveUnits(Object value) {
        return Math.max(1, longValue(value));
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDateTime toLocalDateTime(Object value, ZoneId businessZone) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.time.Instant instant) {
            return LocalDateTime.ofInstant(instant, businessZone);
        }
        return null;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return null;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 16)) + "...[truncated]";
    }

    record WorkBatch(
            long workerId,
            Long companyId,
            Long orderId,
            String section,
            long units,
            int unitMinutes,
            LocalDateTime availableAt,
            String batchKey
    ) {
        long estimatedMinutes() {
            return Math.max(0, units) * Math.max(1, unitMinutes);
        }
    }

    enum DecisionCode {
        MANDATORY,
        LATE;

        private static DecisionCode fromDatabase(Object value) {
            return "MANDATORY".equalsIgnoreCase(value == null ? "" : String.valueOf(value))
                    ? MANDATORY
                    : LATE;
        }
    }

    enum DecisionOrigin {
        LIVE,
        CARRY_OVER,
        PARTIAL_COMPLETION,
        RECOVERED_MANDATORY,
        RECOVERED_LATE,
        LEGACY_LATE;

        private static DecisionOrigin fromDatabase(Object value, DecisionCode decisionCode) {
            String normalized = value == null ? "" : String.valueOf(value).trim();
            try {
                return DecisionOrigin.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return decisionCode == DecisionCode.LATE ? LEGACY_LATE : LIVE;
            }
        }
    }

    record BatchDecision(
            String batchKey,
            DecisionCode decisionCode,
            DecisionOrigin decisionOrigin,
            String cohortKey,
            long initialUnits,
            long initialEstimatedMinutes,
            LocalDateTime firstObservedAt,
            LocalDateTime sourceAvailableAt,
            long availableMinutesAtDecision,
            long cohortEstimatedMinutesAtDecision
    ) {
    }

    private record CohortKey(
            String section,
            Long orderId,
            LocalDateTime availableAt,
            String isolatedBatchKey
    ) {
    }

    private record WalkEstimate(
            long sampleCount,
            long averageSeconds,
            int effectiveMinutes,
            int minimumMinutes,
            String source
    ) {
    }

    private record WorkerSubject(
            long workerId,
            Long workerUserId,
            long managerId,
            int managerLinkCount,
            String workerName,
            String managerName,
            boolean acceptsCompanyTransfers,
            Long workerGroupChatId
    ) {
        boolean workerGroupConnected() {
            return workerGroupChatId != null && workerGroupChatId < 0;
        }
    }

    private record PendingEvent(
            String deduplicationKey,
            String severity,
            String eventType,
            Long managerId,
            Long workerId,
            Long companyId,
            Long transferCaseId,
            String title,
            String message,
            Long targetGroupChatId,
            String deliveryStatus,
            LocalDateTime observedAt,
            LocalDateTime nextAttemptAt
    ) {
    }

    private static final class FreezeAccumulator {
        private final long workerId;
        private int credits;
        private int successfulDays;
        private int earnedTotal;
        private int usedTotal;
        private LocalDate lastEvaluatedDate;

        private FreezeAccumulator(
                long workerId,
                int credits,
                int successfulDays,
                int earnedTotal,
                int usedTotal
        ) {
            this.workerId = workerId;
            this.credits = credits;
            this.successfulDays = successfulDays;
            this.earnedTotal = earnedTotal;
            this.usedTotal = usedTotal;
        }

        private boolean apply(
                long eligibleUnits,
                boolean reached100,
                WorkloadShadowSettingsResponse settings
        ) {
            if (eligibleUnits <= 0) {
                return false;
            }
            if (reached100) {
                successfulDays++;
                if (successfulDays >= settings.freezeEarnDays()) {
                    if (credits < settings.freezeMaxCredits()) {
                        credits++;
                        earnedTotal++;
                    }
                    successfulDays = 0;
                }
                return false;
            }
            successfulDays = 0;
            if (credits <= 0) {
                return false;
            }
            credits--;
            usedTotal++;
            return true;
        }

        private Map<String, Object> toRow() {
            return Map.of(
                    "workerId", workerId,
                    "credits", credits,
                    "successfulDays", successfulDays,
                    "earnedTotal", earnedTotal,
                    "usedTotal", usedTotal,
                    "lastEvaluatedDate", lastEvaluatedDate.toString()
            );
        }
    }

    record HistoryStats(
            int hundredDays,
            int failureDays,
            int protectedDays,
            int rollingHundredDays,
            int rollingFailureDays,
            boolean lastDayReached100,
            LocalDate latestProgressDate
    ) {
        static HistoryStats empty() {
            return new HistoryStats(0, 0, 0, 0, 0, false, null);
        }
    }

    record MonthStats(
            int hundredDays,
            int failureDays,
            int evaluatedDays,
            BigDecimal hundredPercentRate
    ) {
    }

    private record WorkloadClassification(
            long externalBlockedUnits,
            long clientDeferredUnits,
            long managerDeferredUnits
    ) {
        static WorkloadClassification empty() {
            return new WorkloadClassification(0, 0, 0);
        }

        private static final class Mutable {
            private long externalBlockedUnits;
            private long clientDeferredUnits;
            private long managerDeferredUnits;

            private WorkloadClassification toClassification() {
                return new WorkloadClassification(
                        externalBlockedUnits,
                        clientDeferredUnits,
                        managerDeferredUnits
                );
            }
        }
    }

    private record CompletionStats(
            long newUnits,
            long correction,
            long nagul,
            long publish,
            long recovery,
            long bad
    ) {
        long total() {
            return newUnits + correction + nagul + publish + recovery + bad;
        }

        static CompletionStats empty() {
            return new CompletionStats(0, 0, 0, 0, 0, 0);
        }

        private static final class Mutable {
            private long newUnits;
            private long correction;
            private long nagul;
            private long publish;
            private long recovery;
            private long bad;

            private CompletionStats toStats() {
                return new CompletionStats(newUnits, correction, nagul, publish, recovery, bad);
            }
        }
    }

    private record WorkerSnapshot(
            WorkerSubject worker,
            LocalDate progressDate,
            LocalDateTime snapshotAt,
            long completedUnits,
            long activeUnits,
            long lateExcludedUnits,
            long lateExcludedMinutes,
            long eligibleUnits,
            long feasibleUnits,
            BigDecimal progressPercent,
            long estimatedRemainingMinutes,
            long plannedUnits,
            long incomingUnits,
            long urgentUnits,
            long externalBlockedUnits,
            long clientDeferredUnits,
            long managerDeferredUnits,
            long newUnits,
            long correctionUnits,
            long nagulUnits,
            long publishUnits,
            long recoveryUnits,
            long badUnits,
            BigDecimal rating,
            int hundredPercentDays,
            int failureDays,
            int freezeCredits,
            int transferStage,
            boolean lastDayReached100,
            boolean recipientEligible,
            String diagnosticStatus,
            LocalDateTime lastAvailableAt,
            Map<String, BatchDecision> batchDecisions
    ) {
    }
}
