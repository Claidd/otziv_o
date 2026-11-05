package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Testcontainers
class WorkerMonthlyRollupConcurrencyMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("monthly_activity_lock_order").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private StaffDailyProgressService service;
    private final LocalDate month = LocalDate.of(2026, 11, 1);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var settings = mock(AppSettingService.class);
        when(settings.getBoolean(AppSettingService.WORKER_PROGRESS_MONTHLY_AGGREGATE_ENABLED, true)).thenReturn(true);
        service = new StaffDailyProgressService(new NamedParameterJdbcTemplate(ds), settings,
                mock(WorkloadShadowProgressReadService.class));
        for (String table : List.of("worker_performance_monthly", "worker_daily_performance", "workload_shadow_worker_daily",
                "reviews", "review_recovery_tasks", "worker_activity_events", "workers")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE workers(worker_id BIGINT PRIMARY KEY,user_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO workers VALUES(7,17)");
        String daily = "progress_date DATE,worker_id BIGINT,worker_user_id BIGINT,";
        for (String column : List.of("completed_count", "active_count", "total_count", "opened_count",
                "progress_percent", "checked", "reached_100", "order_completed_count", "nagul_completed_count",
                "publish_completed_count", "bad_completed_count", "recovery_completed_count", "recovery_created_count",
                "order_overdue_count", "total_overdue_count", "average_close_seconds", "median_close_seconds",
                "p90_close_seconds", "speed_score", "discipline_score", "workload_score", "active_work_seconds",
                "work_window_seconds", "activity_events", "bot_change_count", "bot_block_count", "load_score", "efficiency_score")) {
            daily += column + " BIGINT NOT NULL DEFAULT 0,";
        }
        jdbc.execute("CREATE TABLE worker_daily_performance(" + daily + "PRIMARY KEY(progress_date,worker_id)) ENGINE=InnoDB");
        String monthly = "month_start DATE,worker_id BIGINT,worker_user_id BIGINT,";
        for (String column : List.of("working_days", "completed_count", "active_count", "total_count", "opened_count",
                "average_progress_percent", "checked_days", "reached_100_days", "order_completed_count", "nagul_completed_count",
                "publish_completed_count", "bad_completed_count", "recovery_completed_count", "recovery_created_count",
                "order_overdue_count", "total_overdue_count", "average_close_seconds", "median_close_seconds", "p90_close_seconds",
                "average_speed_score", "average_discipline_score", "average_workload_score", "active_work_seconds",
                "average_work_window_seconds", "activity_events", "bot_change_count", "bot_block_count", "load_score",
                "average_efficiency_score", "closed_period")) {
            monthly += column + " BIGINT NOT NULL DEFAULT 0,";
        }
        jdbc.execute("CREATE TABLE worker_performance_monthly(" + monthly
                + "updated_at DATETIME(6),PRIMARY KEY(month_start,worker_id),FOREIGN KEY(worker_id) REFERENCES workers(worker_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workload_shadow_worker_daily(progress_date DATE,worker_id BIGINT,reached_100 BOOLEAN,PRIMARY KEY(progress_date,worker_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE reviews(review_worker BIGINT,review_publish BOOLEAN,review_published_marked_at DATETIME(6),review_changed DATE) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE review_recovery_tasks(review_recovery_task_worker BIGINT,review_recovery_task_created_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE worker_activity_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,worker_user_id BIGINT,action VARCHAR(64),created_at DATETIME(6),INDEX idx_worker_activity_action_created(action,created_at)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO worker_daily_performance(progress_date,worker_id,worker_user_id,completed_count,total_count) VALUES(?,7,17,10,10)", month);
        jdbc.execute("INSERT INTO worker_activity_events(worker_user_id,action,created_at) VALUES(17,'REVIEW_BOT_CHANGE','2026-11-01 12:00:00')");
    }

    @Test void accountAuditAndMonthlyRollupBothCommitWhileTheWorkerRowIsLocked() throws Exception {
        CountDownLatch workerLocked = new CountDownLatch(1);
        CountDownLatch insertAudit = new CountDownLatch(1);
        AtomicLong workerConnection = new AtomicLong();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accountCommand = executor.submit(() -> transaction.executeWithoutResult(status -> {
                workerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.queryForObject("SELECT worker_id FROM workers WHERE worker_id=7 FOR UPDATE", Long.class);
                workerLocked.countDown();
                try { assertThat(insertAudit.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                jdbc.update("INSERT INTO worker_activity_events(worker_user_id,action,created_at) VALUES(17,'REVIEW_BOT_DEACTIVATE','2026-11-01 21:57:09')");
            }));
            assertThat(workerLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var rollup = executor.submit(() -> transaction.executeWithoutResult(status ->
                    service.rebuildMonthlyAggregates(month, false)));
            try {
                // A target FK may legitimately wait on the worker. It must not
                // also retain shared source-event locks that prevent this audit.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!rollup.isDone() && System.nanoTime() < deadline) {
                    int waits = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID WHERE t.PROCESSLIST_ID=?", Integer.class, workerConnection.get());
                    if (waits > 0) break;
                    Thread.sleep(25);
                }
                insertAudit.countDown();
                accountCommand.get(5, TimeUnit.SECONDS);
                rollup.get(5, TimeUnit.SECONDS);
            } finally { insertAudit.countDown(); }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_activity_events", Integer.class)).isEqualTo(2);
        transaction.executeWithoutResult(status -> service.rebuildMonthlyAggregates(month, false));
        assertThat(jdbc.queryForObject("SELECT bot_change_count FROM worker_performance_monthly WHERE worker_id=7", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT bot_block_count FROM worker_performance_monthly WHERE worker_id=7", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_count FROM worker_performance_monthly WHERE worker_id=7", Long.class)).isEqualTo(10);
    }

    /** PERF-15: month boundaries of the open-month subqueries after replacing COALESCE and the per-row worker lookup. */
    @Test void openMonthCountsKeepPublicationAndActivityBoundaries() {
        jdbc.execute("INSERT INTO workers VALUES(8,18)");
        jdbc.execute("""
                INSERT INTO reviews(review_worker,review_publish,review_published_marked_at,review_changed) VALUES
                  (7,1,'2026-11-03 10:00:00','2026-10-20'),
                  (7,1,'2026-11-30 23:59:59.999999',NULL),
                  (7,1,'2026-10-31 23:59:59.999999','2026-11-02'),
                  (7,1,'2026-12-01 00:00:00','2026-11-20'),
                  (7,1,NULL,'2026-11-01'),
                  (7,1,NULL,'2026-11-30'),
                  (7,1,NULL,'2026-10-31'),
                  (7,1,NULL,'2026-12-01'),
                  (7,1,NULL,NULL),
                  (7,0,'2026-11-05 10:00:00','2026-11-05'),
                  (8,1,'2026-11-05 10:00:00','2026-11-05')""");
        jdbc.execute("""
                INSERT INTO worker_activity_events(worker_user_id,action,created_at) VALUES
                  (17,'BAD_TASK_BOT_CHANGE','2026-11-30 23:59:59.999999'),
                  (17,'RECOVERY_TASK_BOT_CHANGE','2026-10-31 23:59:59.999999'),
                  (17,'RECOVERY_TASK_BOT_CHANGE','2026-12-01 00:00:00'),
                  (17,'REVIEW_COPY_LOGIN','2026-11-10 10:00:00'),
                  (18,'REVIEW_BOT_CHANGE','2026-11-10 10:00:00'),
                  (17,'REVIEW_BOT_DEACTIVATE','2026-11-10 10:00:00'),
                  (17,'REVIEW_BOT_DEACTIVATE','2026-12-01 00:00:00')""");

        transaction.executeWithoutResult(status -> service.rebuildMonthlyAggregates(month, false));

        assertThat(jdbc.queryForObject("SELECT publish_completed_count FROM worker_performance_monthly WHERE worker_id=7", Long.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT bot_change_count FROM worker_performance_monthly WHERE worker_id=7", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT bot_block_count FROM worker_performance_monthly WHERE worker_id=7", Long.class))
                .isEqualTo(1);
    }
}
