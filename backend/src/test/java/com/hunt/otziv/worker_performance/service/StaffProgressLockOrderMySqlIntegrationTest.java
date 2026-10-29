package com.hunt.otziv.worker_performance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProgressReadService;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Reproduces the production order -> worker / worker FK -> order lock cycle. */
@Testcontainers
class StaffProgressLockOrderMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("staff_lock_order").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; TransactionTemplate tx; StaffDailyProgressService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP TABLE IF EXISTS worker_work_item_lifecycle");
        jdbc.execute("DROP TABLE IF EXISTS orders"); jdbc.execute("DROP TABLE IF EXISTS workers");
        jdbc.execute("CREATE TABLE workers(worker_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_worker BIGINT,order_waiting_for_client BOOLEAN) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE worker_work_item_lifecycle(work_item_key VARCHAR(100) PRIMARY KEY,worker_id BIGINT,
                  active BOOLEAN,overdue BOOLEAN,excluded BOOLEAN,exclusion_reason VARCHAR(100),
                  FOREIGN KEY(worker_id) REFERENCES workers(worker_id)) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO workers VALUES(1),(2)");
        jdbc.update("INSERT INTO orders VALUES(77,1,TRUE),(78,1,FALSE),(79,2,TRUE)");
        jdbc.update("INSERT INTO worker_work_item_lifecycle VALUES('order:77',1,TRUE,TRUE,FALSE,NULL),('order:79',2,TRUE,TRUE,FALSE,NULL)");
        service = new StaffDailyProgressService(new NamedParameterJdbcTemplate(ds), mock(AppSettingService.class),
                mock(WorkloadShadowProgressReadService.class));
    }

    @Test void progressDoesNotLockAnOrderWhileHoldingAWorkerForeignKeyLock() throws Exception {
        var orderLocked = new CountDownLatch(1); var bothReady = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> action = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=5");
                jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=77 FOR UPDATE", Long.class);
                orderLocked.countDown(); await(bothReady);
                jdbc.queryForObject("SELECT worker_id FROM workers WHERE worker_id=1 FOR UPDATE", Long.class);
            }));
            Future<?> progress = pool.submit(() -> {
                try { assertThat(orderLocked.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                tx.executeWithoutResult(status -> {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout=5");
                    // The real lifecycle upsert acquires this shared worker FK lock.
                    jdbc.update("INSERT INTO worker_work_item_lifecycle VALUES('order:78',1,TRUE,FALSE,FALSE,NULL)");
                    await(bothReady);
                    ReflectionTestUtils.invokeMethod(service, "excludeWaitingClientOrdersFromLifecycle", java.util.List.of(1L));
                });
            });
            action.get(12,TimeUnit.SECONDS); progress.get(12,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForMap("SELECT active,overdue,excluded,exclusion_reason FROM worker_work_item_lifecycle WHERE work_item_key='order:77'"))
                .containsEntry("active",false).containsEntry("overdue",false).containsEntry("excluded",true)
                .containsEntry("exclusion_reason","waiting_for_client");
        assertThat(jdbc.queryForObject("SELECT active FROM worker_work_item_lifecycle WHERE work_item_key='order:79'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM worker_work_item_lifecycle WHERE work_item_key='order:78'",Boolean.class)).isTrue();
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
