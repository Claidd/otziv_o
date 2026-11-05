package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workload-owned answer to "which of today's counted cards have no usable account". */
@Service
@RequiredArgsConstructor
public class MandatoryAccountBlockerService implements MandatoryAccountBlockers {

    private final WorkloadShadowProjectionService projectionService;
    private final WorkloadShadowSettingsService settingsService;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public WorkloadDay currentDay() {
        WorkloadShadowSettingsResponse settings = settingsService.current();
        // Without the workload observation the day close uses legacy progress, where an
        // account blocker is not added to the day's denominator.
        return new WorkloadDay(
                settingsService.zone(settings),
                settingsService.shiftStart(settings),
                settingsService.shiftEnd(settings),
                settings.observationEnabled()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<BlockedTask>> blockedTasks(Collection<Long> workerIds, LocalDateTime observedAt) {
        List<WorkloadShadowProjectionService.BlockedBatch> batches =
                projectionService.mandatoryAccountBlockers(workerIds, observedAt);
        if (batches.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> companies = companyTitles(batches.stream()
                .map(WorkloadShadowProjectionService.BlockedBatch::companyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Set<Long> recoveryWithoutAccount = recoveryTasksWithoutAccount(batches.stream()
                .filter(batch -> kind(batch) == Kind.RECOVERY)
                .map(MandatoryAccountBlockerService::taskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, List<BlockedTask>> result = new LinkedHashMap<>();
        for (WorkloadShadowProjectionService.BlockedBatch batch : batches) {
            Kind kind = kind(batch);
            if (kind == null) {
                continue;
            }
            Long taskId = taskId(batch);
            Long orderId = batch.orderId() == null || batch.orderId() <= 0 ? null : batch.orderId();
            result.computeIfAbsent(batch.workerId(), ignored -> new ArrayList<>()).add(new BlockedTask(
                    batch.workerId(),
                    kind,
                    taskId,
                    batch.companyId(),
                    batch.companyId() == null ? null : companies.get(batch.companyId()),
                    orderId,
                    kind == Kind.RECOVERY && taskId != null && recoveryWithoutAccount.contains(taskId)
            ));
        }
        Map<Long, List<BlockedTask>> immutable = new LinkedHashMap<>();
        result.forEach((workerId, tasks) -> immutable.put(workerId, List.copyOf(tasks)));
        return immutable;
    }

    private Map<Long, String> companyTitles(Set<Long> companyIds) {
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new HashMap<>();
        jdbc.query("""
                SELECT company_id, company_title
                FROM companies
                WHERE company_id IN (:ids)
                """, new MapSqlParameterSource("ids", companyIds), row -> {
            titles.put(row.getLong("company_id"), row.getString("company_title"));
        });
        return titles;
    }

    /** Same presence rule as the specialist screen: account login/password, else the task snapshot. */
    private Set<Long> recoveryTasksWithoutAccount(Set<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.queryForList("""
                SELECT task.review_recovery_task_id
                FROM review_recovery_tasks task
                LEFT JOIN bots bot ON bot.bot_id = task.review_recovery_task_bot
                WHERE task.review_recovery_task_id IN (:ids)
                  AND (
                      COALESCE(NULLIF(TRIM(bot.bot_login), ''),
                               NULLIF(TRIM(task.review_recovery_task_bot_login_snapshot), '')) IS NULL
                      OR COALESCE(NULLIF(TRIM(bot.bot_password), ''),
                                  NULLIF(TRIM(task.review_recovery_task_bot_password_snapshot), '')) IS NULL
                  )
                """, new MapSqlParameterSource("ids", taskIds), Long.class));
    }

    static Kind kind(WorkloadShadowProjectionService.BlockedBatch batch) {
        String section = batch.section() == null ? "" : batch.section();
        return switch (section) {
            case "NAGUL" -> Kind.NAGUL;
            case "PUBLISH" -> Kind.PUBLISH;
            case "BAD" -> Kind.BAD;
            case "RECOVERY" -> Kind.RECOVERY;
            default -> null;
        };
    }

    /** Batch keys are "<SECTION>:<review or task id>" for every account-bound source. */
    static Long taskId(WorkloadShadowProjectionService.BlockedBatch batch) {
        String key = batch.batchKey();
        int separator = key == null ? -1 : key.indexOf(':');
        if (separator < 0) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(separator + 1).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
