package com.hunt.otziv.worker_performance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.business_audit.api.RequiredTransactionAudit;
import com.hunt.otziv.u_users.api.TeamDirectoryReader;
import com.hunt.otziv.worker_performance.dto.WorkerDayExcuseResponse;
import com.hunt.otziv.worker_performance.dto.WorkerDayResultResponse;
import com.hunt.otziv.worker_performance.dto.WorkerDayResultsResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner/admin action "Засчитать день": counts one finished specialist day as reached although the
 * final numbers were below 100% (for example, a task that could not be done). The real eligible and
 * completed numbers stay as evidence. The day is marked {@code INCIDENT_EXCUSED} in the operational
 * daily snapshot and in the finalized workload day, exactly where the 2026-09-17 incident fix did,
 * and the streak of the day and of the rest of its run is recomputed with the end-of-day rule.
 *
 * <p>Nothing here sends Telegram messages, records gamification events or touches rewards. Every
 * change and every undo writes a business audit record in the same transaction.
 */
@Service
@Slf4j
public class WorkerDayExcuseService {

    public static final String ACTION_EXCUSED = "WORKER_DAY_EXCUSED";
    public static final String ACTION_REVOKED = "WORKER_DAY_EXCUSE_REVOKED";
    static final String AUDIT_ENTITY = "WORKER";
    static final String INCIDENT_EXCUSED = "INCIDENT_EXCUSED";
    static final int REASON_LIMIT = 500;

    private static final ZoneId PROGRESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final EndOfDayAchievementService achievementService;
    private final StaffDailyProgressService staffDailyProgressService;
    private final RequiredTransactionAudit audit;
    private final TeamDirectoryReader teamDirectory;
    private final TransactionTemplate transactionTemplate;
    private Clock clock = Clock.system(PROGRESS_ZONE);

    public WorkerDayExcuseService(
            NamedParameterJdbcTemplate jdbc,
            EndOfDayAchievementService achievementService,
            StaffDailyProgressService staffDailyProgressService,
            RequiredTransactionAudit audit,
            TeamDirectoryReader teamDirectory,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.achievementService = achievementService;
        this.staffDailyProgressService = staffDailyProgressService;
        this.audit = audit;
        this.teamDirectory = teamDirectory;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Test seam: the business day is evaluated in the progress time zone. */
    void useClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public WorkerDayResultsResponse dayResults(Authentication authentication, LocalDate date) {
        requireActor(authentication);
        if (date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите дату");
        }
        boolean editable = date.isBefore(today());
        Set<Long> scope = visibleWorkerIds(authentication);
        if (scope != null && scope.isEmpty()) {
            return new WorkerDayResultsResponse(date, editable, List.of());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("role", EndOfDayAchievementService.ROLE_WORKER)
                .addValue("workerIds", scope == null ? List.of(-1L) : scope)
                .addValue("allWorkers", scope == null);
        List<WorkerDayResultResponse> rows = jdbc.query("""
                SELECT result.actor_id, result.result_date, result.eligible_count, result.completed_count,
                       result.progress_percent, result.reached_100, result.streak_days,
                       excuse.worker_id AS excuse_worker_id, excuse.reason AS excuse_reason,
                       excuse.excused_by, excuse.excused_at
                FROM end_of_day_achievement_results result
                LEFT JOIN worker_day_excuses excuse
                       ON excuse.worker_id = result.actor_id
                      AND excuse.result_date = result.result_date
                      AND excuse.active = TRUE
                WHERE result.result_date = :date
                  AND result.actor_role = :role
                  AND (:allWorkers OR result.actor_id IN (:workerIds))
                ORDER BY result.actor_id
                """, params, (rs, index) -> dayResponse(
                rs.getLong("actor_id"),
                date,
                rs.getLong("eligible_count"),
                rs.getLong("completed_count"),
                decimal(rs.getBigDecimal("progress_percent")),
                rs.getBoolean("reached_100"),
                rs.getInt("streak_days"),
                rs.getObject("excuse_worker_id") != null,
                rs.getString("excuse_reason"),
                rs.getString("excused_by"),
                toLocalDateTime(rs.getTimestamp("excused_at")),
                editable
        ));
        return new WorkerDayResultsResponse(date, editable, rows);
    }

    public WorkerDayExcuseResponse excuse(Authentication authentication, Long workerId, LocalDate date, String reason) {
        String actor = requireActor(authentication);
        String cleanReason = requireReason(reason);
        LocalDate day = requireClosedDay(date);
        requireWorkerInScope(authentication, workerId);
        WorkerDayExcuseResponse response = transactionTemplate.execute(status ->
                excuseInTransaction(authentication, actor, workerId, day, cleanReason));
        if (response != null && response.changed()) {
            refreshMonthlyAggregate(day);
        }
        return response;
    }

    public WorkerDayExcuseResponse revoke(Authentication authentication, Long workerId, LocalDate date, String reason) {
        String actor = requireActor(authentication);
        String cleanReason = requireReason(reason);
        LocalDate day = requireClosedDay(date);
        requireWorkerInScope(authentication, workerId);
        WorkerDayExcuseResponse response = transactionTemplate.execute(status ->
                revokeInTransaction(authentication, actor, workerId, day, cleanReason));
        if (response != null && response.changed()) {
            refreshMonthlyAggregate(day);
        }
        return response;
    }

    private WorkerDayExcuseResponse excuseInTransaction(
            Authentication authentication, String actor, Long workerId, LocalDate day, String reason) {
        Map<String, Object> result = lockDayResult(workerId, day);
        if (longValue(result.get("eligible_count")) <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "В этот день у специалиста не было обязательной нагрузки — засчитывать нечего");
        }
        Map<String, Object> excuse = lockExcuse(workerId, day);
        if (excuse != null && booleanValue(excuse.get("active"))) {
            return unchanged(workerId, day, "День уже засчитан владельцем");
        }
        if (booleanValue(result.get("reached_100"))) {
            return unchanged(workerId, day, "День уже засчитан на 100%");
        }
        Map<String, Object> daily = lockDaily(workerId, day);
        Map<String, Object> workload = lockFinalWorkload(workerId, day);
        LocalDateTime now = LocalDateTime.now(clock);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("date", day)
                .addValue("role", EndOfDayAchievementService.ROLE_WORKER)
                .addValue("reason", reason)
                .addValue("actor", actor)
                .addValue("now", now)
                .addValue("originalReached", booleanValue(result.get("reached_100")))
                .addValue("dailyStatus", daily == null ? null : daily.get("aggregation_status"))
                .addValue("dailyReached", daily == null ? null : booleanValue(daily.get("reached_100")))
                .addValue("dailyFinalizedAt", daily == null ? null : daily.get("finalized_at"))
                .addValue("workloadStatus", workload == null ? null : workload.get("finalization_status"))
                .addValue("workloadReached", workload == null ? null : booleanValue(workload.get("reached_100")))
                .addValue("excusedStatus", INCIDENT_EXCUSED);
        if (excuse == null) {
            jdbc.update("""
                    INSERT INTO worker_day_excuses (
                        worker_id, result_date, active, reason, excused_by, excused_at,
                        original_reached_100, original_daily_status, original_daily_reached_100,
                        original_daily_finalized_at, original_workload_status, original_workload_reached_100
                    ) VALUES (
                        :workerId, :date, TRUE, :reason, :actor, :now,
                        :originalReached, :dailyStatus, :dailyReached,
                        :dailyFinalizedAt, :workloadStatus, :workloadReached
                    )
                    """, params);
        } else {
            jdbc.update("""
                    UPDATE worker_day_excuses
                    SET active = TRUE,
                        reason = :reason,
                        excused_by = :actor,
                        excused_at = :now,
                        original_reached_100 = :originalReached,
                        original_daily_status = :dailyStatus,
                        original_daily_reached_100 = :dailyReached,
                        original_daily_finalized_at = :dailyFinalizedAt,
                        original_workload_status = :workloadStatus,
                        original_workload_reached_100 = :workloadReached,
                        revoked_by = NULL,
                        revoked_at = NULL,
                        revoke_reason = NULL
                    WHERE worker_id = :workerId
                      AND result_date = :date
                    """, params);
        }
        // Only the verdict changes: eligible/completed/percent stay as the evidence of the day.
        jdbc.update("""
                UPDATE end_of_day_achievement_results
                SET reached_100 = TRUE
                WHERE result_date = :date
                  AND actor_role = :role
                  AND actor_id = :workerId
                """, params);
        if (daily != null) {
            // StaffDailyProgressService.excusedDailyResults keeps such a row instead of rebuilding
            // the closed day from the live queue.
            jdbc.update("""
                    UPDATE worker_daily_performance
                    SET aggregation_status = :excusedStatus,
                        reached_100 = TRUE,
                        finalized_at = COALESCE(finalized_at, :now)
                    WHERE progress_date = :date
                      AND worker_id = :workerId
                    """, params);
        }
        if (workload != null) {
            jdbc.update("""
                    UPDATE workload_shadow_worker_daily
                    SET finalization_status = :excusedStatus,
                        reached_100 = TRUE
                    WHERE progress_date = :date
                      AND worker_id = :workerId
                      AND finalized = TRUE
                    """, params);
        }
        List<EndOfDayAchievementService.StreakChange> streaks = achievementService.recalculateStreakRun(
                day, EndOfDayAchievementService.ROLE_WORKER, workerId);
        WorkerDayResultResponse updated = currentDay(workerId, day);

        Map<String, Object> before = stateJson(day, result, false,
                daily == null ? null : daily.get("aggregation_status"),
                daily == null ? null : booleanValue(daily.get("reached_100")),
                workload == null ? null : workload.get("finalization_status"),
                workload == null ? null : booleanValue(workload.get("reached_100")),
                previousStreaks(result, day, streaks));
        Map<String, Object> after = stateJson(day, result, true,
                daily == null ? null : INCIDENT_EXCUSED, daily == null ? null : true,
                workload == null ? null : INCIDENT_EXCUSED, workload == null ? null : true,
                newStreaks(updated, streaks));
        audit.recordRequiredInCurrentTransaction(authentication, ACTION_EXCUSED, AUDIT_ENTITY, workerId,
                null, null, json(before), json(after), json(details(workerId, day, reason, streaks)));
        log.info("Owner action: specialist day counted workerId={}, date={}, actor={}, streak={}, laterDaysUpdated={}",
                workerId, day, actor, updated.streakDays(), laterDays(day, streaks));
        return new WorkerDayExcuseResponse(true, "День засчитан", laterDays(day, streaks), updated);
    }

    private WorkerDayExcuseResponse revokeInTransaction(
            Authentication authentication, String actor, Long workerId, LocalDate day, String reason) {
        Map<String, Object> result = lockDayResult(workerId, day);
        Map<String, Object> excuse = lockExcuse(workerId, day);
        if (excuse == null || !booleanValue(excuse.get("active"))) {
            return unchanged(workerId, day, "День не был засчитан вручную");
        }
        Map<String, Object> daily = lockDaily(workerId, day);
        Map<String, Object> workload = lockFinalWorkload(workerId, day);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean originalReached = booleanValue(excuse.get("original_reached_100"));
        Object dailyStatus = excuse.get("original_daily_status");
        Object workloadStatus = excuse.get("original_workload_status");

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("date", day)
                .addValue("role", EndOfDayAchievementService.ROLE_WORKER)
                .addValue("reason", reason)
                .addValue("actor", actor)
                .addValue("now", now)
                .addValue("originalReached", originalReached)
                .addValue("dailyStatus", dailyStatus)
                .addValue("dailyReached", booleanValue(excuse.get("original_daily_reached_100")))
                .addValue("dailyFinalizedAt", excuse.get("original_daily_finalized_at"))
                .addValue("workloadStatus", workloadStatus)
                .addValue("workloadReached", booleanValue(excuse.get("original_workload_reached_100")));
        jdbc.update("""
                UPDATE end_of_day_achievement_results
                SET reached_100 = :originalReached
                WHERE result_date = :date
                  AND actor_role = :role
                  AND actor_id = :workerId
                """, params);
        if (dailyStatus != null && daily != null) {
            jdbc.update("""
                    UPDATE worker_daily_performance
                    SET aggregation_status = :dailyStatus,
                        reached_100 = :dailyReached,
                        finalized_at = :dailyFinalizedAt
                    WHERE progress_date = :date
                      AND worker_id = :workerId
                    """, params);
        }
        if (workloadStatus != null && workload != null) {
            jdbc.update("""
                    UPDATE workload_shadow_worker_daily
                    SET finalization_status = :workloadStatus,
                        reached_100 = :workloadReached
                    WHERE progress_date = :date
                      AND worker_id = :workerId
                      AND finalized = TRUE
                    """, params);
        }
        jdbc.update("""
                UPDATE worker_day_excuses
                SET active = FALSE,
                    revoked_by = :actor,
                    revoked_at = :now,
                    revoke_reason = :reason
                WHERE worker_id = :workerId
                  AND result_date = :date
                """, params);
        List<EndOfDayAchievementService.StreakChange> streaks = achievementService.recalculateStreakRun(
                day, EndOfDayAchievementService.ROLE_WORKER, workerId);
        WorkerDayResultResponse updated = currentDay(workerId, day);

        Map<String, Object> before = stateJson(day, result, true,
                daily == null ? null : daily.get("aggregation_status"),
                daily == null ? null : booleanValue(daily.get("reached_100")),
                workload == null ? null : workload.get("finalization_status"),
                workload == null ? null : booleanValue(workload.get("reached_100")),
                previousStreaks(result, day, streaks));
        Map<String, Object> after = stateJson(day, result, originalReached,
                dailyStatus == null || daily == null ? null : dailyStatus,
                dailyStatus == null || daily == null ? null : booleanValue(excuse.get("original_daily_reached_100")),
                workloadStatus == null || workload == null ? null : workloadStatus,
                workloadStatus == null || workload == null ? null : booleanValue(excuse.get("original_workload_reached_100")),
                newStreaks(updated, streaks));
        Map<String, Object> details = details(workerId, day, reason, streaks);
        details.put("excuseReason", excuse.get("reason"));
        details.put("excusedBy", excuse.get("excused_by"));
        audit.recordRequiredInCurrentTransaction(authentication, ACTION_REVOKED, AUDIT_ENTITY, workerId,
                null, null, json(before), json(after), json(details));
        log.info("Owner action: specialist day excuse revoked workerId={}, date={}, actor={}, streak={}, laterDaysUpdated={}",
                workerId, day, actor, updated.streakDays(), laterDays(day, streaks));
        return new WorkerDayExcuseResponse(true, "Зачёт дня отменён", laterDays(day, streaks), updated);
    }

    private Map<String, Object> lockDayResult(Long workerId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT eligible_count, completed_count, progress_percent, reached_100, streak_days
                FROM end_of_day_achievement_results
                WHERE result_date = :date
                  AND actor_role = :role
                  AND actor_id = :workerId
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("date", day)
                .addValue("role", EndOfDayAchievementService.ROLE_WORKER)
                .addValue("workerId", workerId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Итог этого дня ещё не подведён — засчитать можно только закрытый день");
        }
        return rows.getFirst();
    }

    private Map<String, Object> lockExcuse(Long workerId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT active, reason, excused_by, original_reached_100, original_daily_status,
                       original_daily_reached_100, original_daily_finalized_at,
                       original_workload_status, original_workload_reached_100
                FROM worker_day_excuses
                WHERE worker_id = :workerId
                  AND result_date = :date
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("date", day));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> lockDaily(Long workerId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT aggregation_status, reached_100, finalized_at
                FROM worker_daily_performance
                WHERE progress_date = :date
                  AND worker_id = :workerId
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("date", day));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> lockFinalWorkload(Long workerId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT finalization_status, reached_100
                FROM workload_shadow_worker_daily
                WHERE progress_date = :date
                  AND worker_id = :workerId
                  AND finalized = TRUE
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("date", day));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WorkerDayResultResponse currentDay(Long workerId, LocalDate day) {
        List<WorkerDayResultResponse> rows = jdbc.query("""
                SELECT result.eligible_count, result.completed_count, result.progress_percent,
                       result.reached_100, result.streak_days,
                       excuse.worker_id AS excuse_worker_id, excuse.reason AS excuse_reason,
                       excuse.excused_by, excuse.excused_at
                FROM end_of_day_achievement_results result
                LEFT JOIN worker_day_excuses excuse
                       ON excuse.worker_id = result.actor_id
                      AND excuse.result_date = result.result_date
                      AND excuse.active = TRUE
                WHERE result.result_date = :date
                  AND result.actor_role = :role
                  AND result.actor_id = :workerId
                """, new MapSqlParameterSource()
                .addValue("date", day)
                .addValue("role", EndOfDayAchievementService.ROLE_WORKER)
                .addValue("workerId", workerId), (rs, index) -> dayResponse(
                workerId,
                day,
                rs.getLong("eligible_count"),
                rs.getLong("completed_count"),
                decimal(rs.getBigDecimal("progress_percent")),
                rs.getBoolean("reached_100"),
                rs.getInt("streak_days"),
                rs.getObject("excuse_worker_id") != null,
                rs.getString("excuse_reason"),
                rs.getString("excused_by"),
                toLocalDateTime(rs.getTimestamp("excused_at")),
                day.isBefore(today())
        ));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Итог дня не найден");
        }
        return rows.getFirst();
    }

    private WorkerDayExcuseResponse unchanged(Long workerId, LocalDate day, String message) {
        return new WorkerDayExcuseResponse(false, message, 0, currentDay(workerId, day));
    }

    static WorkerDayResultResponse dayResponse(
            Long workerId, LocalDate date, long eligible, long completed, double percent, boolean reached100,
            int streakDays, boolean excused, String reason, String excusedBy, LocalDateTime excusedAt, boolean editable) {
        return new WorkerDayResultResponse(
                workerId, date, eligible, completed, percent, reached100, streakDays,
                excused,
                excused ? reason : null,
                excused ? excusedBy : null,
                excused ? excusedAt : null,
                editable && eligible > 0 && !reached100,
                editable && excused
        );
    }

    private void refreshMonthlyAggregate(LocalDate day) {
        LocalDate monthStart = day.withDayOfMonth(1);
        LocalDate currentMonth = today().withDayOfMonth(1);
        // The scheduled jobs rebuild only these two months; older months may already have lost
        // their raw daily rows to retention, so they are never rebuilt from partial data.
        if (monthStart.isBefore(currentMonth.minusMonths(1))) {
            return;
        }
        try {
            staffDailyProgressService.rebuildMonthlyAggregates(monthStart, monthStart.isBefore(currentMonth));
        } catch (RuntimeException failure) {
            log.warn("Monthly specialist progress was not refreshed after a day excuse for {}: {}",
                    monthStart, failure.getClass().getSimpleName());
        }
    }

    private Set<Long> visibleWorkerIds(Authentication authentication) {
        if (hasRole(authentication, "ROLE_ADMIN")) {
            return null;
        }
        if (!hasRole(authentication, "ROLE_OWNER")) {
            return Set.of();
        }
        List<Long> managerIds = jdbc.queryForList("""
                SELECT DISTINCT link.manager_id
                FROM managers_users link
                JOIN users owner_user ON owner_user.id = link.user_id
                WHERE owner_user.username = :username
                """, new MapSqlParameterSource("username", authentication.getName()), Long.class);
        return teamDirectory.forManagers(TeamDirectoryReader.Role.WORKER, managerIds).stream()
                .map(TeamDirectoryReader.Member::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void requireWorkerInScope(Authentication authentication, Long workerId) {
        if (workerId == null || workerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не указан");
        }
        Set<Long> scope = visibleWorkerIds(authentication);
        if (scope != null && !scope.contains(workerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Специалист не входит в вашу команду");
        }
    }

    private String requireActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход");
        }
        if (!hasRole(authentication, "ROLE_ADMIN") && !hasRole(authentication, "ROLE_OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Действие доступно владельцу");
        }
        String name = authentication.getName().trim();
        return name.length() <= 150 ? name : name.substring(0, 150);
    }

    private static String requireReason(String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину");
        }
        if (clean.length() > REASON_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Причина длиннее " + REASON_LIMIT + " символов");
        }
        return clean;
    }

    private LocalDate requireClosedDay(LocalDate date) {
        if (date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите дату");
        }
        if (!date.isBefore(today())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Засчитать можно только закрытый день: итог дня подводится после полуночи");
        }
        return date;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority != null && role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> stateJson(
            LocalDate day, Map<String, Object> result, boolean reached100,
            Object dailyStatus, Boolean dailyReached, Object workloadStatus, Boolean workloadReached,
            Map<String, Integer> streaks) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("date", day.toString());
        state.put("eligibleCount", longValue(result.get("eligible_count")));
        state.put("completedCount", longValue(result.get("completed_count")));
        state.put("reached100", reached100);
        state.put("dailyStatus", dailyStatus == null ? null : dailyStatus.toString());
        state.put("dailyReached100", dailyReached);
        state.put("workloadStatus", workloadStatus == null ? null : workloadStatus.toString());
        state.put("workloadReached100", workloadReached);
        state.put("streakDays", streaks);
        return state;
    }

    private static Map<String, Integer> previousStreaks(
            Map<String, Object> result, LocalDate day, List<EndOfDayAchievementService.StreakChange> changes) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(day.toString(), (int) longValue(result.get("streak_days")));
        changes.forEach(change -> values.put(change.date().toString(), change.previousStreakDays()));
        return values;
    }

    private static Map<String, Integer> newStreaks(
            WorkerDayResultResponse day, List<EndOfDayAchievementService.StreakChange> changes) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(day.date().toString(), day.streakDays());
        changes.forEach(change -> values.put(change.date().toString(), change.streakDays()));
        return values;
    }

    private static Map<String, Object> details(
            Long workerId, LocalDate day, String reason, List<EndOfDayAchievementService.StreakChange> changes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workerId", workerId);
        details.put("date", day.toString());
        details.put("reason", reason);
        details.put("laterDaysUpdated", laterDays(day, changes));
        details.put("telegramMessagesSent", 0);
        details.put("gamificationEventsRecorded", 0);
        return details;
    }

    private static int laterDays(LocalDate day, List<EndOfDayAchievementService.StreakChange> changes) {
        return (int) changes.stream().filter(change -> change.date().isAfter(day)).count();
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Audit value could not be serialized", failure);
        }
    }

    private static double decimal(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof byte[] bytes) return bytes.length > 0 && bytes[0] != 0;
        return value != null && ("1".equals(value.toString()) || Boolean.parseBoolean(value.toString()));
    }
}
