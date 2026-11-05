package com.hunt.otziv.worker_performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.api.RequiredTransactionAudit;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.api.TeamDirectoryReader;
import com.hunt.otziv.worker_performance.dto.WorkerDayExcuseResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Runs the owner day-excuse SQL against MySQL: BIT flags, row locks, streak run and exact undo. */
@Testcontainers
class WorkerDayExcuseServiceMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_day_excuse").withUsername("root").withPassword(UUID.randomUUID().toString());

    private static final LocalDate FAILED_DAY = LocalDate.of(2026, 11, 1);
    private static final Authentication OWNER = new TestingAuthenticationToken("hunt", "", "ROLE_OWNER");
    private static final Authentication ADMIN = new TestingAuthenticationToken("alex", "", "ROLE_ADMIN");

    private JdbcTemplate jdbc;
    private WorkerDayExcuseService service;
    private RequiredTransactionAudit audit;
    private StaffDailyProgressService staffDailyProgressService;
    private TeamDirectoryReader teamDirectory;
    private TelegramService telegram;
    private NotificationMediaDeliveryService media;
    private GamificationEventService gamification;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String table : List.of("end_of_day_achievement_results", "worker_day_excuses", "worker_daily_performance",
                "workload_shadow_worker_daily", "managers_users", "users")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE end_of_day_achievement_results (
                    end_of_day_achievement_result_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    result_date DATE NOT NULL, actor_role VARCHAR(20) NOT NULL, actor_id BIGINT NOT NULL,
                    actor_user_id BIGINT NULL, eligible_count BIGINT NOT NULL DEFAULT 0,
                    completed_count BIGINT NOT NULL DEFAULT 0, progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
                    ignored_late_count BIGINT NOT NULL DEFAULT 0, reached_100 BIT NOT NULL DEFAULT 0,
                    streak_days INT NOT NULL DEFAULT 0, telegram_notified_at DATETIME(6) NULL,
                    UNIQUE KEY uk_end_of_day_achievement_actor_date (result_date, actor_role, actor_id),
                    INDEX idx_end_of_day_achievement_streak (actor_role, actor_id, result_date)
                ) ENGINE=InnoDB""");
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration/V1_10_379__worker_day_excuses.sql")));
        jdbc.execute("""
                CREATE TABLE worker_daily_performance (
                    progress_date DATE NOT NULL, worker_id BIGINT NOT NULL,
                    completed_count BIGINT NOT NULL DEFAULT 0, total_count BIGINT NOT NULL DEFAULT 0,
                    progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0, reached_100 BIT(1) NOT NULL DEFAULT b'0',
                    aggregation_status VARCHAR(24) NOT NULL DEFAULT 'CALCULATED', finalized_at DATETIME(6) NULL,
                    UNIQUE KEY uk_worker_daily_performance (progress_date, worker_id)
                ) ENGINE=InnoDB""");
        jdbc.execute("""
                CREATE TABLE workload_shadow_worker_daily (
                    progress_date DATE NOT NULL, worker_id BIGINT NOT NULL,
                    completed_units BIGINT NOT NULL DEFAULT 0, eligible_units BIGINT NOT NULL DEFAULT 0,
                    reached_100 BIT(1) NOT NULL DEFAULT b'0', finalized BIT(1) NOT NULL DEFAULT b'0',
                    finalization_status VARCHAR(32) NOT NULL DEFAULT 'LIVE',
                    UNIQUE KEY uk_workload_shadow_worker_daily (progress_date, worker_id)
                ) ENGINE=InnoDB""");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(100)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE managers_users (user_id BIGINT, manager_id BIGINT) ENGINE=InnoDB");
        jdbc.update("INSERT INTO users VALUES (10, 'hunt'), (1, 'alex')");
        jdbc.update("INSERT INTO managers_users VALUES (10, 3)");

        // Worker 6: 10-30 ✓, 10-31 ✓, 11-01 28/29 ✗, 11-02 ✓, 11-03 ✓, 11-04 ✗, 11-05 ✓.
        result(6, "2026-10-30", 20, 20, true, 1);
        result(6, "2026-10-31", 29, 29, true, 2);
        result(6, "2026-11-01", 29, 28, false, 0);
        result(6, "2026-11-02", 33, 33, true, 1);
        result(6, "2026-11-03", 39, 39, true, 2);
        result(6, "2026-11-04", 30, 29, false, 0);
        result(6, "2026-11-05", 46, 46, true, 1);
        result(7, "2026-11-01", 10, 9, false, 0);
        jdbc.update("""
                INSERT INTO worker_daily_performance (progress_date, worker_id, completed_count, total_count,
                    progress_percent, reached_100, aggregation_status, finalized_at)
                VALUES ('2026-11-01', 6, 24, 25, 96, b'0', 'CALCULATED', NULL)""");
        jdbc.update("""
                INSERT INTO workload_shadow_worker_daily (progress_date, worker_id, completed_units, eligible_units,
                    reached_100, finalized, finalization_status)
                VALUES ('2026-11-01', 6, 28, 29, b'0', b'1', 'ON_TIME')""");

        var templates = new NamedParameterJdbcTemplate(ds);
        telegram = mock(TelegramService.class);
        media = mock(NotificationMediaDeliveryService.class);
        gamification = mock(GamificationEventService.class);
        var achievements = new EndOfDayAchievementService(templates, telegram, media, gamification);
        audit = mock(RequiredTransactionAudit.class);
        staffDailyProgressService = mock(StaffDailyProgressService.class);
        teamDirectory = mock(TeamDirectoryReader.class);
        when(teamDirectory.forManagers(eq(TeamDirectoryReader.Role.WORKER), any())).thenAnswer(invocation -> {
            java.util.Collection<Long> managers = invocation.getArgument(1);
            return managers.contains(3L)
                    ? List.of(new TeamDirectoryReader.Member(6L, 60L, "Альфия", "alfiya", 1L, true))
                    : List.of();
        });
        service = new WorkerDayExcuseService(templates, achievements, staffDailyProgressService, audit, teamDirectory,
                new DataSourceTransactionManager(ds));
        service.useClock(Clock.fixed(ZonedDateTime.of(2026, 11, 6, 10, 0, 0, 0,
                ZoneId.of("Asia/Irkutsk")).toInstant(), ZoneId.of("Asia/Irkutsk")));
    }

    @Test
    void countsDayKeepsEvidenceMarksIncidentAndContinuesTheRun() {
        WorkerDayExcuseResponse response = service.excuse(OWNER, 6L, FAILED_DAY, "  Задача #1160 чужой фирмы без аккаунта  ");

        assertThat(response.changed()).isTrue();
        assertThat(response.laterDaysUpdated()).isEqualTo(2);
        assertThat(response.day().reached100()).isTrue();
        assertThat(response.day().streakDays()).isEqualTo(3);
        assertThat(response.day().eligibleCount()).isEqualTo(29);
        assertThat(response.day().completedCount()).isEqualTo(28);
        assertThat(response.day().excused()).isTrue();
        assertThat(response.day().excuseReason()).isEqualTo("Задача #1160 чужой фирмы без аккаунта");
        assertThat(response.day().excusedBy()).isEqualTo("hunt");
        assertThat(response.day().canExcuse()).isFalse();
        assertThat(response.day().canRevoke()).isTrue();

        assertThat(day(6, "2026-11-01")).containsEntry("reached", 1L).containsEntry("streak_days", 3)
                .containsEntry("eligible_count", 29L).containsEntry("completed_count", 28L);
        assertThat(streaks(6)).containsExactly(1, 2, 3, 4, 5, 0, 1);
        assertThat(streaks(7)).containsExactly(0);
        assertThat(jdbc.queryForMap("""
                SELECT aggregation_status, CAST(reached_100 AS SIGNED) AS reached, CAST(finalized_at IS NOT NULL AS SIGNED) AS finalized,
                       completed_count, total_count
                FROM worker_daily_performance WHERE worker_id = 6""")).containsEntry("aggregation_status", "INCIDENT_EXCUSED")
                .containsEntry("reached", 1L).containsEntry("finalized", 1L)
                .containsEntry("completed_count", 24L).containsEntry("total_count", 25L);
        assertThat(jdbc.queryForMap("""
                SELECT finalization_status, CAST(reached_100 AS SIGNED) AS reached, completed_units, eligible_units
                FROM workload_shadow_worker_daily WHERE worker_id = 6""")).containsEntry("finalization_status", "INCIDENT_EXCUSED")
                .containsEntry("reached", 1L).containsEntry("completed_units", 28L).containsEntry("eligible_units", 29L);
        verify(audit).recordRequiredInCurrentTransaction(eq(OWNER), eq(WorkerDayExcuseService.ACTION_EXCUSED),
                eq("WORKER"), eq(6L), isNull(), isNull(),
                argThat(old -> old.toString().contains("\"reached100\":false")
                        && old.toString().contains("\"dailyStatus\":\"CALCULATED\"")
                        && old.toString().contains("\"2026-11-02\":1")),
                argThat(now -> now.toString().contains("\"reached100\":true")
                        && now.toString().contains("\"workloadStatus\":\"INCIDENT_EXCUSED\"")
                        && now.toString().contains("\"2026-11-01\":3")
                        && now.toString().contains("\"2026-11-03\":5")),
                argThat(details -> details.contains("Задача #1160 чужой фирмы без аккаунта")
                        && details.contains("\"telegramMessagesSent\":0")));
        verify(staffDailyProgressService).rebuildMonthlyAggregates(LocalDate.of(2026, 11, 1), false);
        verifyNoInteractions(telegram, media, gamification);
    }

    @Test
    void repeatedRequestIsIdempotentAndAuditedOnce() {
        service.excuse(OWNER, 6L, FAILED_DAY, "Задача без аккаунта");

        WorkerDayExcuseResponse again = service.excuse(OWNER, 6L, FAILED_DAY, "Задача без аккаунта");

        assertThat(again.changed()).isFalse();
        assertThat(again.day().streakDays()).isEqualTo(3);
        assertThat(streaks(6)).containsExactly(1, 2, 3, 4, 5, 0, 1);
        verify(audit, times(1)).recordRequiredInCurrentTransaction(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), anyString());
    }

    @Test
    void undoRestoresEveryOriginalValueAndRestartsTheRun() {
        service.excuse(OWNER, 6L, FAILED_DAY, "Задача без аккаунта");

        WorkerDayExcuseResponse revoked = service.revoke(ADMIN, 6L, FAILED_DAY, "Ошиблись днём");

        assertThat(revoked.changed()).isTrue();
        assertThat(revoked.day().reached100()).isFalse();
        assertThat(revoked.day().excused()).isFalse();
        assertThat(revoked.day().canExcuse()).isTrue();
        assertThat(streaks(6)).containsExactly(1, 2, 0, 1, 2, 0, 1);
        assertThat(day(6, "2026-11-01")).containsEntry("reached", 0L);
        assertThat(jdbc.queryForMap("""
                SELECT aggregation_status, CAST(reached_100 AS SIGNED) AS reached, finalized_at
                FROM worker_daily_performance WHERE worker_id = 6""")).containsEntry("aggregation_status", "CALCULATED")
                .containsEntry("reached", 0L).containsEntry("finalized_at", null);
        assertThat(jdbc.queryForMap("""
                SELECT finalization_status, CAST(reached_100 AS SIGNED) AS reached
                FROM workload_shadow_worker_daily WHERE worker_id = 6""")).containsEntry("finalization_status", "ON_TIME")
                .containsEntry("reached", 0L);
        assertThat(jdbc.queryForMap("""
                SELECT CAST(active AS SIGNED) AS active, revoked_by, revoke_reason, reason
                FROM worker_day_excuses WHERE worker_id = 6""")).containsEntry("active", 0L)
                .containsEntry("revoked_by", "alex").containsEntry("revoke_reason", "Ошиблись днём")
                .containsEntry("reason", "Задача без аккаунта");
        verify(audit).recordRequiredInCurrentTransaction(eq(ADMIN), eq(WorkerDayExcuseService.ACTION_REVOKED),
                eq("WORKER"), eq(6L), isNull(), isNull(),
                argThat(old -> old.toString().contains("\"2026-11-01\":3")),
                argThat(now -> now.toString().contains("\"2026-11-01\":0")
                        && now.toString().contains("\"dailyStatus\":\"CALCULATED\"")),
                argThat(details -> details.contains("Ошиблись днём") && details.contains("Задача без аккаунта")));

        assertThat(service.revoke(ADMIN, 6L, FAILED_DAY, "Ещё раз").changed()).isFalse();
        // Counting it again after an undo starts a fresh, audited decision.
        assertThat(service.excuse(ADMIN, 6L, FAILED_DAY, "Всё-таки засчитать").changed()).isTrue();
        assertThat(streaks(6)).containsExactly(1, 2, 3, 4, 5, 0, 1);
        verify(audit, times(3)).recordRequiredInCurrentTransaction(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), anyString());
        verifyNoInteractions(telegram, media, gamification);
    }

    @Test
    void reachedDayIsLeftAloneAndDayWithoutResultIsRejected() {
        WorkerDayExcuseResponse reached = service.excuse(ADMIN, 6L, LocalDate.of(2026, 11, 2), "Причина");

        assertThat(reached.changed()).isFalse();
        assertThat(reached.message()).isEqualTo("День уже засчитан на 100%");
        assertThatThrownBy(() -> service.excuse(ADMIN, 6L, LocalDate.of(2026, 10, 1), "Причина"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(audit, never()).recordRequiredInCurrentTransaction(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), anyString());
        verify(staffDailyProgressService, never()).rebuildMonthlyAggregates(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void validatesReasonClosedDayAndOwnerTeam() {
        assertStatus(() -> service.excuse(OWNER, 6L, FAILED_DAY, "   "), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.excuse(OWNER, 6L, FAILED_DAY, "x".repeat(501)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.excuse(OWNER, 6L, LocalDate.of(2026, 11, 6), "Сегодня"), HttpStatus.CONFLICT);
        assertStatus(() -> service.excuse(OWNER, 7L, FAILED_DAY, "Чужой специалист"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.revoke(OWNER, 7L, FAILED_DAY, "Чужой специалист"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.excuse(new TestingAuthenticationToken("anna", "", "ROLE_MANAGER"),
                6L, FAILED_DAY, "Менеджер"), HttpStatus.FORBIDDEN);
        assertThat(streaks(6)).containsExactly(1, 2, 0, 1, 2, 0, 1);
        verifyNoInteractions(audit);
    }

    @Test
    void listsOnlyTheCallersTeamWithExcuseState() {
        service.excuse(OWNER, 6L, FAILED_DAY, "Задача без аккаунта");

        var owner = service.dayResults(OWNER, FAILED_DAY);
        var admin = service.dayResults(ADMIN, FAILED_DAY);
        var today = service.dayResults(ADMIN, LocalDate.of(2026, 11, 6));

        assertThat(owner.editable()).isTrue();
        assertThat(owner.workers()).singleElement().satisfies(day -> {
            assertThat(day.workerId()).isEqualTo(6L);
            assertThat(day.excused()).isTrue();
            assertThat(day.canRevoke()).isTrue();
            assertThat(day.streakDays()).isEqualTo(3);
        });
        assertThat(admin.workers()).extracting(day -> day.workerId()).containsExactly(6L, 7L);
        assertThat(admin.workers().get(1).canExcuse()).isTrue();
        assertThat(admin.workers().get(1).excused()).isFalse();
        assertThat(today.editable()).isFalse();
    }

    private void result(long workerId, String date, long eligible, long completed, boolean reached, int streak) {
        jdbc.update("""
                INSERT INTO end_of_day_achievement_results (result_date, actor_role, actor_id, eligible_count,
                    completed_count, progress_percent, reached_100, streak_days, telegram_notified_at)
                VALUES (?, 'WORKER', ?, ?, ?, ?, ?, ?, '2026-11-02 00:04:17')""",
                date, workerId, eligible, completed, Math.floor(completed * 100.0 / eligible), reached, streak);
    }

    private Map<String, Object> day(long workerId, String date) {
        return jdbc.queryForMap("""
                SELECT CAST(reached_100 AS SIGNED) AS reached, streak_days, eligible_count, completed_count
                FROM end_of_day_achievement_results
                WHERE actor_role = 'WORKER' AND actor_id = ? AND result_date = ?""", workerId, date);
    }

    private List<Integer> streaks(long workerId) {
        return jdbc.queryForList("""
                SELECT streak_days FROM end_of_day_achievement_results
                WHERE actor_role = 'WORKER' AND actor_id = ? ORDER BY result_date""", Integer.class, workerId);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(status);
    }
}
