package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.business_audit.model.BusinessAuditEvent;
import com.hunt.otziv.business_audit.repository.BusinessAuditEventRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.security.credentials.CredentialRevealRequest;
import com.hunt.otziv.security.credentials.CredentialRevealResponse;
import com.hunt.otziv.security.credentials.service.CredentialRevealService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Real bounded Hikari pool and InnoDB locks; JDBC audit adapter isolates transaction orchestration.
 * Hibernate mappings and encrypted storage are covered by their existing integration tests. */
@Testcontainers
class WorkerCredentialPoolMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("credential_pool").withUsername("root").withPassword(UUID.randomUUID().toString());
    private HikariDataSource pool;
    private JdbcTemplate jdbc, independent;
    private DataSourceTransactionManager transactions;
    private CredentialRevealService reveal;
    private WorkerCredentialCommands commands;
    private final WorkerOrderActor actor = new WorkerOrderActor("fixture-worker", Set.of("WORKER"));
    private final CredentialRevealRequest request = new CredentialRevealRequest("password", "worker-board", "menu", "new");
    private WorkerActivityService activity;
    private final AtomicBoolean failInsert = new AtomicBoolean(), failCommit = new AtomicBoolean();
    private final AtomicReference<CountDownLatch> concurrent = new AtomicReference<>();

    @BeforeEach void prepare() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl()); config.setUsername(MYSQL.getUsername()); config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2); config.setMinimumIdle(2); config.setConnectionTimeout(800);
        pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool);
        independent = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        transactions = new DataSourceTransactionManager(pool);
        jdbc.execute("DROP TABLE IF EXISTS credential_pool_orders,credential_pool_audit,credential_pool_preparation");
        jdbc.execute("CREATE TABLE credential_pool_orders(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO credential_pool_orders VALUES(1),(2)");
        jdbc.execute("CREATE TABLE credential_pool_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor VARCHAR(150),details TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE credential_pool_preparation(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        failInsert.set(false); failCommit.set(false); concurrent.set(null);
        var audits = mock(BusinessAuditEventRepository.class);
        org.mockito.stubbing.Answer<BusinessAuditEvent> writeAudit = call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            BusinessAuditEvent event = call.getArgument(0);
            assertThat(event.getActor()).isEqualTo(actor.username());
            assertThat(event.getDetails()).doesNotContain("fixture-secret");
            if (failInsert.get()) jdbc.execute("INSERT INTO missing_credential_audit_table VALUES(1)");
            jdbc.update("INSERT INTO credential_pool_audit(actor,details) VALUES(?,?)", event.getActor(), event.getDetails());
            if (failCommit.get()) {
                long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { independent.execute("KILL CONNECTION " + id); }
                });
            }
            return event;
        };
        when(audits.save(any())).thenAnswer(writeAudit);
        when(audits.saveAndFlush(any())).thenAnswer(writeAudit);
        reveal = proxy(new CredentialRevealService(proxy(new BusinessAuditService(audits, transactions))));
        var reviews = mock(ReviewService.class);
        when(reviews.getReviewById(anyLong())).thenAnswer(call -> review(call.getArgument(0)));
        var access = mock(WorkerReviewAccessPolicy.class);
        doAnswer(call -> {
            Review review = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.queryForObject("SELECT id FROM credential_pool_orders WHERE id=? FOR UPDATE", Long.class, review.getId());
            if (concurrent.get() != null) await(concurrent.get());
            return null;
        }).when(access).enforceReviewSourceAccess(any(Review.class), anyString(), any());
        var preparation = mock(WorkerCredentialPreparationService.class);
        when(preparation.recordCopy(any(), any(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Review review = call.getArgument(1);
            jdbc.update("INSERT INTO credential_pool_preparation VALUES(?)", review.getId());
            return true;
        });
        activity = mock(WorkerActivityService.class);
        doAnswer(call -> {
            // Audit is durable and the command connection/lock is released before best-effort activity.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(independent.queryForObject("SELECT COUNT(*) FROM credential_pool_audit", Integer.class)).isPositive();
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    jdbc.queryForObject("SELECT id FROM credential_pool_orders WHERE id=? FOR UPDATE", Long.class, call.getArgument(4, Long.class)));
            return null;
        }).when(activity).recordSafely(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        commands = proxy(new WorkerCredentialCommands(reviews, mock(BadReviewTaskService.class), mock(ReviewRecoveryTaskService.class),
                mock(WorkerPublicationGateService.class), activity, preparation, mock(WorkerCellularAccessService.class),
                mock(WorkerAssignmentMutationGuardService.class), reveal, access, transactions));
    }

    @AfterEach void cleanup() { if (pool != null) pool.close(); }

    @Test void saturatedPoolStillCommitsBothCommandsWithoutASecondConnection() throws Exception {
        concurrent.set(new CountDownLatch(2));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> commands.revealReviewCredential(1L, request, actor));
            var second = executor.submit(() -> commands.revealReviewCredential(2L, request, actor));
            assertThat(first.get(8, TimeUnit.SECONDS).value()).isEqualTo("fixture-secret");
            assertThat(second.get(8, TimeUnit.SECONDS).value()).isEqualTo("fixture-secret");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_pool_audit", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_pool_preparation", Integer.class)).isEqualTo(2);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test void negativeControlReproducesRequiresNewPoolExhaustion() throws Exception {
        var locked = new CountDownLatch(2); var failed = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (long id : new long[]{1, 2}) results.add(executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM credential_pool_orders WHERE id=? FOR UPDATE", Long.class, id);
                await(locked);
                try { reveal.revealReview(review(id), request, actor.authentication()); return false; }
                catch (org.springframework.transaction.CannotCreateTransactionException expected) { await(failed); return true; }
            })));
            for (var result : results) assertThat(result.get(8, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_pool_audit", Integer.class)).isZero();
    }

    @Test void failedAuditInsertDisclosesNothingAndRollsBack() { failInsert.set(true); assertNoDisclosure(); }
    @Test void realServerCommitFailureDisclosesNothingAndRollsBackPreparation() { failCommit.set(true); assertNoDisclosure(); }

    private void assertNoDisclosure() {
        AtomicReference<CredentialRevealResponse> response = new AtomicReference<>();
        assertThatThrownBy(() -> response.set(commands.revealReviewCredential(1L, request, actor))).isInstanceOf(RuntimeException.class);
        assertThat(response.get()).isNull();
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM credential_pool_audit", Integer.class)).isZero();
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM credential_pool_preparation", Integer.class)).isZero();
        verifyNoInteractions(activity);
    }
    private Review review(long id) {
        Order order = new Order(); order.setId(id);
        OrderDetails detail = new OrderDetails(); detail.setOrder(order);
        Review review = new Review(); review.setId(id); review.setOrderDetails(detail);
        review.setBot(Bot.builder().id(100L + id).login("fixture-login").password("fixture-secret").build());
        return review;
    }
    private static void await(CountDownLatch latch) {
        latch.countDown();
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T service) {
        var factory = new ProxyFactory(service); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
