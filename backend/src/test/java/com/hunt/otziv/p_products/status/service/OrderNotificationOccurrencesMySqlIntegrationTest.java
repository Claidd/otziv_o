package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.payments.repository.PaymentRouteChangeNotificationOutboxRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
class OrderNotificationOccurrencesMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("order_message_occurrences").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OrderNotificationOccurrences occurrences;
    private PaymentRouteChangeNotificationOutboxRepository routeJobs;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String table : List.of("order_client_message_occurrences", "payment_route_change_notification_outbox", "orders"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_279__payment_route_change_notification_outbox.sql")).execute(ds);
        jdbc.update("INSERT INTO orders(order_id) VALUES(7)");
        // This job exists before the cutover, so its former send outcome is unknowable.
        jdbc.update("INSERT INTO payment_route_change_notification_outbox(payment_link_id,order_id) VALUES(70,7)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_305__order_notification_occurrences.sql")).execute(ds);
        manager = new DataSourceTransactionManager(ds);
        occurrences = proxy(new OrderNotificationOccurrences(jdbc));
        routeJobs = new PaymentRouteChangeNotificationOutboxRepository(new NamedParameterJdbcTemplate(ds));
    }

    @Test void unknownRetainsIdentityAcrossRestartFallbackGenerationAndConcurrentRetry() throws Exception {
        String first = occurrences.reserve(7, "action:check", 1);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return proxy(new OrderNotificationOccurrences(jdbc)).reserve(7, "action:check", 2); });
            var b = pool.submit(() -> { start.await(); return occurrences.reserve(7, "action:check", 3); });
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbc.queryForObject("SELECT business_generation FROM order_client_message_occurrences", Long.class)).isEqualTo(3);
        occurrences.confirm(7, "action:check", first);
        assertThat(occurrences.reserve(7, "action:check", 1)).isEqualTo(first);
        assertThat(occurrences.reserve(7, "action:check", 3)).isEqualTo(first);
        String next = occurrences.reserve(7, "action:check", 4);
        assertThat(next).isNotEqualTo(first);
        assertThatThrownBy(() -> occurrences.confirm(7, "action:check", first)).hasMessageContaining("obsolete");
        assertThat(jdbc.queryForObject("SELECT confirmed FROM order_client_message_occurrences", Boolean.class)).isFalse();
    }

    @Test void reservationCommitsBeforeDeliveryAndSurvivesBusinessRollback() {
        String[] captured = new String[1];
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("UPDATE orders SET client_message_generation=1 WHERE order_id=7");
            captured[0] = occurrences.reserve(7, "action:payment", 1);
            var independent = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
            assertThat(independent.queryForObject("SELECT operation_id FROM order_client_message_occurrences", String.class)).isEqualTo(captured[0]);
            throw new IllegalStateException("late order rollback");
        })).hasMessage("late order rollback");
        assertThat(jdbc.queryForObject("SELECT client_message_generation FROM orders WHERE order_id=7", Long.class)).isZero();
        assertThat(occurrences.reserve(7, "action:payment", 1)).isEqualTo(captured[0]);
    }

    @Test void legacyOrderCannotCreateOperationOrCallAnyProvider() {
        var sender = mock(ClientMessageDelivery.class);
        var notifications = notifications(sender, occurrences);
        Order order = order(0);
        assertThat(notifications.sendInformationalMessageToClientChat(order, "client", "group", "changed template", "reminder")).isFalse();
        verifyNoInteractions(sender);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_client_message_occurrences", Integer.class)).isZero();
        assertThatThrownBy(() -> occurrences.reserve(7, "action:check", 0)).hasMessage("legacy_operation_unverified");
    }

    @Test void lateConfirmationFailureDoesNotAllocateFreshIdentityOnRetry() {
        var sender = mock(ClientMessageDelivery.class);
        var failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql, Object... arguments) {
                int changed = super.update(sql, arguments);
                if (sql.startsWith("UPDATE order_client_message_occurrences SET confirmed")) {
                    throw new IllegalStateException("receipt commit unavailable");
                }
                return changed;
            }
        };
        var brokenConfirmation = proxy(new OrderNotificationOccurrences(failingJdbc));
        when(sender.deliverWithOperationId(any(), anyString(), anyString(), anyString(), isNull(), anyString()))
                .thenReturn(ClientMessageSendResult.sent("Telegram", "81"));
        Order order = order(1);
        assertThat(notifications(sender, brokenConfirmation).sendInformationalMessageToClientChat(order, "a", "b", "original", "reminder")).isFalse();
        String id = jdbc.queryForObject("SELECT operation_id FROM order_client_message_occurrences", String.class);
        assertThat(jdbc.queryForObject("SELECT confirmed FROM order_client_message_occurrences", Boolean.class)).isFalse();
        order.setClientMessageGeneration(2);
        assertThat(notifications(sender, occurrences).sendInformationalMessageToClientChat(order, "new", "new", "changed", "reminder")).isTrue();
        verify(sender).deliverWithOperationId(any(), eq("a"), eq("b"), eq("original"), isNull(), eq(id));
        verify(sender).deliverWithOperationId(any(), eq("new"), eq("new"), eq("changed"), isNull(), eq(id));
        assertThat(jdbc.queryForObject("SELECT generation FROM order_client_message_occurrences", Long.class)).isEqualTo(1);
    }

    @Test void legacyRouteJobRemainsQuarantinedEvenWhenEnqueuedAgainAndNewJobIsClaimable() {
        assertThat(routeJobs.enqueue(7, 70)).isFalse();
        assertThat(routeJobs.findDuePaymentLinkIds(10)).isEmpty();
        assertThat(routeJobs.tryAcquire(70, java.util.UUID.randomUUID().toString(), "fixture", Duration.ofMinutes(1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM payment_route_change_notification_outbox WHERE payment_link_id=70", Integer.class)).isZero();
        assertThat(routeJobs.enqueue(7, 71)).isTrue();
        assertThat(routeJobs.findDuePaymentLinkIds(10)).containsExactly(71L);
        var claimed = routeJobs.tryAcquire(71, java.util.UUID.randomUUID().toString(), "fixture", Duration.ofMinutes(1)).orElseThrow();
        assertThat(routeJobs.markSent(claimed)).isTrue();
        assertThat(routeJobs.findDuePaymentLinkIds(10)).isEmpty();
    }

    private Order order(long generation) {
        Order order = new Order(); order.setId(7L); order.setClientMessageGeneration(generation);
        var company = new com.hunt.otziv.c_companies.model.Company(); company.setId(8L); order.setCompany(company);
        return order;
    }
    private OrderStatusNotificationService notifications(ClientMessageDelivery sender, OrderNotificationOccurrences service) {
        return new OrderStatusNotificationService(mock(OrderRepository.class), mock(OrderStatusService.class),
                mock(TelegramService.class), mock(WhatsAppAuthAlertService.class), service, sender);
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
