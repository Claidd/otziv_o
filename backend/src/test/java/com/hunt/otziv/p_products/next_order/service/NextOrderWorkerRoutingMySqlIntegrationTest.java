package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.repository.NextOrderWorkerRoutingRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class NextOrderWorkerRoutingMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("repeat_routing").withUsername("root").withPassword("root");
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static NextOrderWorkerRoutingService service;

    @BeforeAll static void setup() {
        ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new NextOrderWorkerRoutingService(new NextOrderWorkerRoutingRepository(jdbc)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (NextOrderWorkerRoutingService) proxy.getProxy();
        for (String ddl : new String[]{
                "CREATE TABLE companies(company_id BIGINT PRIMARY KEY,company_manager BIGINT)",
                "CREATE TABLE users(id BIGINT PRIMARY KEY,active BOOLEAN)",
                "CREATE TABLE workers(worker_id BIGINT PRIMARY KEY,user_id BIGINT)",
                "CREATE TABLE managers_users(user_id BIGINT,manager_id BIGINT)",
                "CREATE TABLE workers_companies(company_id BIGINT,worker_id BIGINT,PRIMARY KEY(company_id,worker_id))",
                "CREATE TABLE workload_transfer_executions(workload_transfer_execution_id BIGINT PRIMARY KEY,company_id BIGINT,source_worker_id BIGINT,target_worker_id BIGINT,status VARCHAR(32),applied_at DATETIME(6))",
                "CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_company BIGINT,order_worker BIGINT,order_manager BIGINT,order_complete BOOLEAN,order_pay_day DATE,order_counter INT,order_amount INT,row_version BIGINT DEFAULT 0)",
                "CREATE TABLE next_order_requests(next_order_request_id BIGINT PRIMARY KEY,source_order_id BIGINT,created_order_id BIGINT,request_status VARCHAR(32))",
                "CREATE TABLE order_details(order_detail_id BIGINT PRIMARY KEY,order_detail_order BIGINT)",
                "CREATE TABLE reviews(review_id BIGINT PRIMARY KEY,review_order_details BIGINT,review_worker BIGINT,review_publish BOOLEAN,row_version BIGINT DEFAULT 0)",
                "CREATE TABLE zp(zp_order BIGINT,zp_active BOOLEAN)",
                "CREATE TABLE payment_check(check_order BIGINT,check_active BOOLEAN)",
                "CREATE TABLE contractor_completion_reward_markers(order_id BIGINT)",
                "CREATE TABLE contractor_payment_allocations(order_id BIGINT,common_invoice_id BIGINT)",
                "CREATE TABLE common_invoice_orders(invoice_id BIGINT,order_id BIGINT)",
                "CREATE TABLE bad_review_tasks(bad_review_task_id BIGINT PRIMARY KEY,bad_review_task_order BIGINT,bad_review_task_worker BIGINT,bad_review_task_status VARCHAR(32))",
                "CREATE TABLE review_recovery_tasks(review_recovery_task_id BIGINT PRIMARY KEY,review_recovery_task_order BIGINT,review_recovery_task_worker BIGINT,review_recovery_task_status VARCHAR(32),review_recovery_task_updated_at DATETIME(6))",
                "CREATE TABLE worker_credential_preparations(review_id BIGINT)",
                "CREATE TABLE business_audit_events(created_at DATETIME(6),actor VARCHAR(150),source VARCHAR(80),action VARCHAR(80),entity_type VARCHAR(40),entity_id VARCHAR(80),order_id BIGINT,review_id BIGINT,old_value TEXT,new_value TEXT,details TEXT)"
        }) jdbc.execute(ddl);
    }

    @BeforeEach void seed() {
        for (String table : new String[]{"companies", "users", "workers", "managers_users", "workers_companies",
                "workload_transfer_executions", "orders", "next_order_requests", "order_details", "reviews", "zp",
                "payment_check", "contractor_completion_reward_markers", "contractor_payment_allocations",
                "common_invoice_orders", "bad_review_tasks", "review_recovery_tasks", "worker_credential_preparations",
                "business_audit_events"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO companies VALUES(748,2)");
        jdbc.update("INSERT INTO users VALUES(24,1),(17,1)");
        jdbc.update("INSERT INTO workers VALUES(13,24),(6,17)");
        jdbc.update("INSERT INTO managers_users VALUES(24,2),(17,2)");
        jdbc.update("INSERT INTO workers_companies VALUES(748,6)");
        jdbc.update("INSERT INTO workload_transfer_executions VALUES(8,748,13,6,'APPLIED','2026-08-23 11:17:33')");
    }

    @Test void successfulHistoryAndCurrentMembershipRouteHistoricalWorker() {
        assertThatThrownBy(() -> service.resolveWorkerId(748L, 13L, 2L)).isInstanceOf(IllegalTransactionStateException.class);
        long resolved = tx.execute(status -> service.resolveWorkerId(748L, 13L, 2L));
        assertThat(resolved).isEqualTo(6L);
        jdbc.update("UPDATE workload_transfer_executions SET status='ROLLED_BACK'");
        assertThatThrownBy(() -> tx.execute(status -> service.resolveWorkerId(748L, 13L, 2L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void seesTransferCommittedAfterSourceSnapshot() {
        jdbc.update("DELETE FROM workload_transfer_executions");
        jdbc.update("UPDATE workers_companies SET worker_id=13");
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM workers_companies WHERE worker_id=13", Long.class);
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> tx.executeWithoutResult(other -> {
                    jdbc.queryForObject("SELECT company_id FROM companies WHERE company_id=748 FOR UPDATE", Long.class);
                    jdbc.update("UPDATE workers_companies SET worker_id=6");
                    jdbc.update("INSERT INTO workload_transfer_executions VALUES(8,748,13,6,'APPLIED',NOW(6))");
                })).get(10, TimeUnit.SECONDS);
            } catch (Exception error) { throw new IllegalStateException(error); }
            assertThat(service.resolveWorkerId(748L, 13L, 2L)).isEqualTo(6L);
        });
    }

    @Test void rejectsInactiveAndCrossManagerRecipients() {
        jdbc.update("UPDATE users SET active=0 WHERE id=17");
        assertThatThrownBy(() -> tx.execute(status -> service.resolveWorkerId(748L, 13L, 2L))).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE users SET active=1 WHERE id=17");
        jdbc.update("INSERT INTO managers_users VALUES(17,3)");
        assertThatThrownBy(() -> tx.execute(status -> service.resolveWorkerId(748L, 13L, 2L))).isInstanceOf(IllegalStateException.class);
    }

    @Test void concurrentHistoricalRequestsReuseOrderCommittedWhileWaitingForCompanyLock() throws Exception {
        var requests = mock(com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository.class);
        var requestService = mock(NextOrderRequestService.class);
        var creation = mock(com.hunt.otziv.p_products.service.OrderCreationService.class);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var saved = new java.util.concurrent.ConcurrentHashMap<Long, Long>();
        Order created = withTerms(source()); created.setId(27728L); created.getWorker().setId(6L);
        when(requests.findByIdForUpdate(anyLong())).thenAnswer(call -> {
            // Both requests establish a snapshot before either owns the company.
            jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("request barrier timeout");
            Order historical = withTerms(source()); historical.setId(call.getArgument(0));
            var request = com.hunt.otziv.p_products.next_order.model.NextOrderRequest.builder()
                    .sourceOrder(historical).company(historical.getCompany())
                    .status(com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus.PENDING).build();
            request.setId(call.getArgument(0)); return java.util.Optional.of(request);
        });
        when(requestService.orderFilialIds(any())).thenReturn(java.util.Set.of());
        when(requestService.findActiveOrdersForFilials(any(), any(), any(), any())).thenAnswer(call ->
                number("SELECT COUNT(*) FROM orders WHERE order_id=27728") == 0 ? java.util.List.of() : java.util.List.of(created));
        when(creation.convertToOrderDTOToRepeat(any(), anyLong())).thenReturn(new com.hunt.otziv.p_products.dto.OrderDTO());
        when(creation.createRepeatedOrderWithReviews(any(), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO orders VALUES(27728,748,6,2,0,NULL,0,1,0)"); return created;
        });
        when(requests.save(any())).thenAnswer(call -> {
            com.hunt.otziv.p_products.next_order.model.NextOrderRequest request = call.getArgument(0);
            saved.put(request.getId(), request.getCreatedOrder().getId()); return request;
        });
        var proxy = new ProxyFactory(new NextOrderAutomationService(requests, requestService, creation, service));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        var automation = (NextOrderAutomationService) proxy.getProxy();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> automation.createNextOrder(19403L));
            var second = pool.submit(() -> automation.createNextOrder(19404L));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        verify(creation, times(1)).createRepeatedOrderWithReviews(any(), any());
        assertThat(saved).containsEntry(19403L, 27728L).containsEntry(19404L, 27728L);
        assertThat(number("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM business_audit_events")).isEqualTo(1);
    }

    @Test void repairMovesRemainingWorkPreservesPublishedAuthorsAndIsIdempotent() throws Exception {
        seedRepair();
        migrate();
        assertThat(number("SELECT order_worker FROM orders WHERE order_id=27728")).isEqualTo(6);
        assertThat(number("SELECT review_worker FROM reviews WHERE review_id=1")).isEqualTo(13);
        assertThat(number("SELECT review_worker FROM reviews WHERE review_id=2")).isEqualTo(6);
        assertThat(number("SELECT order_worker FROM orders WHERE order_id=27404")).isEqualTo(6);
        assertThat(number("SELECT COUNT(*) FROM worker_credential_preparations")).isZero();
        assertThat(number("SELECT bad_review_task_worker FROM bad_review_tasks")).isEqualTo(6);
        assertThat(number("SELECT review_recovery_task_worker FROM review_recovery_tasks")).isEqualTo(6);
        assertThat(number("SELECT COUNT(*) FROM business_audit_events")).isEqualTo(2);
        migrate();
        assertThat(number("SELECT COUNT(*) FROM business_audit_events")).isEqualTo(2);
        assertThat(number("SELECT row_version FROM orders WHERE order_id=27728")).isEqualTo(1);
    }

    @Test void repairCannotMoveOrderWithFrozenInvoiceRouteOrCompletedReward() throws Exception {
        seedRepair();
        jdbc.update("INSERT INTO common_invoice_orders VALUES(99,27728)");
        jdbc.update("INSERT INTO contractor_payment_allocations VALUES(NULL,99)");
        migrate();
        assertUnchanged();
        jdbc.update("DELETE FROM contractor_payment_allocations");
        jdbc.update("INSERT INTO contractor_completion_reward_markers VALUES(27728)");
        migrate();
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(number("SELECT order_worker FROM orders WHERE order_id=27728")).isEqualTo(13);
        assertThat(number("SELECT review_worker FROM reviews WHERE review_id=2")).isEqualTo(13);
        assertThat(number("SELECT COUNT(*) FROM business_audit_events")).isZero();
    }

    private void seedRepair() {
        jdbc.update("INSERT INTO orders VALUES(27728,748,13,2,0,NULL,6,12,0),(27404,748,6,2,0,NULL,4,5,0)");
        jdbc.update("INSERT INTO next_order_requests VALUES(4131,19403,27728,'CREATED')");
        jdbc.update("INSERT INTO order_details VALUES(1,27728)");
        jdbc.update("INSERT INTO reviews VALUES(1,1,13,1,0),(2,1,13,0,0)");
        jdbc.update("INSERT INTO worker_credential_preparations VALUES(2)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES(1,27728,13,'NEW')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES(1,27728,13,'PLANNED',NULL)");
    }

    private long number(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private void migrate() throws Exception {
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V1_10_344__repair_autospa_repeat_order_assignment.sql"));
        }
    }

    private Order source() {
        var company = new Company(); company.setId(748L);
        var manager = new Manager(); manager.setId(2L);
        var worker = new Worker(); worker.setId(13L);
        return Order.builder().id(19403L).company(company).manager(manager).worker(worker).build();
    }

    private Order withTerms(Order order) {
        var price = new java.math.BigDecimal("250");
        var review = com.hunt.otziv.r_review.model.Review.builder()
                .product(com.hunt.otziv.p_products.model.Product.builder().id(2L).build()).price(price).build();
        var detail = com.hunt.otziv.p_products.model.OrderDetails.builder()
                .amount(1).price(price).reviews(java.util.List.of(review)).build();
        order.setDetails(java.util.List.of(detail)); order.setAmount(1); order.setSum(price); return order;
    }
}
