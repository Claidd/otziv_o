package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real Spring advice and MySQL commit/rollback; the repository port uses minimal fixture tables. */
@Testcontainers(disabledWithoutDocker = true)
class ManualSettlementReminderMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manual_settlement_reminder").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private PaymentInvoiceRetryScheduler scheduler;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        jdbc.execute("DROP TABLE IF EXISTS reminder");
        jdbc.execute("DROP TABLE IF EXISTS payment");
        jdbc.execute("CREATE TABLE payment (id BIGINT PRIMARY KEY, confirmed BIGINT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE reminder (id BIGINT PRIMARY KEY, status VARCHAR(20), delivery VARCHAR(20), "
                + "error_code VARCHAR(100), envelope TEXT, delivery_token VARCHAR(64), prepared_at DATETIME) ENGINE=InnoDB");
        jdbc.update("INSERT INTO payment VALUES (26375, 0)");
        jdbc.update("INSERT INTO reminder VALUES (2094420, 'ACTIVE', 'UNKNOWN', "
                + "'state_transaction_outcome_uncertain', 'original-envelope', 'original-token', ?)",
                LocalDateTime.now().minusDays(5));
        var repository = mock(ScheduledClientMessageStateRepository.class);
        when(repository.findByOrderIdInForUpdate(List.of(26375L))).thenAnswer(call -> jdbc.query(
                "SELECT * FROM reminder WHERE id=2094420 FOR UPDATE", (row, index) -> ScheduledClientMessageState.builder()
                        .id(row.getLong("id")).orderId(26375L).scenario(ClientMessageScenario.PAYMENT_REMINDER)
                        .status(ScheduledMessageStateStatus.valueOf(row.getString("status")))
                        .deliveryStatus(row.getString("delivery")).lastErrorCode(row.getString("error_code"))
                        .deliveryEnvelope(row.getString("envelope")).deliveryToken(row.getString("delivery_token"))
                        .deliveryPreparedAt(row.getTimestamp("prepared_at").toLocalDateTime()).build()));
        when(repository.saveAll(any())).thenAnswer(call -> {
            List<ScheduledClientMessageState> states = call.getArgument(0);
            for (var state : states) {
                jdbc.update("UPDATE reminder SET status=?, delivery=?, error_code=?, envelope=?, delivery_token=? WHERE id=?",
                        state.getStatus().name(), state.getDeliveryStatus(), state.getLastErrorCode(),
                        state.getDeliveryEnvelope(), state.getDeliveryToken(), state.getId());
            }
            return states;
        });
        var target = new PaymentInvoiceRetryScheduler(repository, mock(AppSettingService.class), mock(ClientMessageSlotPlanner.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        scheduler = (PaymentInvoiceRetryScheduler) proxy.getProxy();
    }

    @Test
    void paymentFailureRollsBackReminderClosureAndSuccessfulRetryCommitsBoth() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            scheduler.closePaymentAutomationForManualSettlement(26375L, "Manager verified the transfer");
            jdbc.update("UPDATE payment SET confirmed=125000 WHERE id=26375");
            throw new IllegalStateException("accounting write failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM reminder", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT confirmed FROM payment", Long.class)).isZero();

        tx.executeWithoutResult(status -> {
            assertThat(scheduler.closePaymentAutomationForManualSettlement(26375L, "Manager verified the transfer")).isEqualTo(1);
            jdbc.update("UPDATE payment SET confirmed=125000 WHERE id=26375");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM reminder", String.class)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT confirmed FROM payment", Long.class)).isEqualTo(125000L);
        assertThat(jdbc.queryForObject("SELECT delivery FROM reminder", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT envelope FROM reminder", String.class)).isEqualTo("original-envelope");
        assertThat(jdbc.queryForObject("SELECT delivery_token FROM reminder", String.class)).isEqualTo("original-token");
        assertThat(jdbc.queryForObject("SELECT error_code FROM reminder", String.class)).isEqualTo("state_transaction_outcome_uncertain");
        tx.executeWithoutResult(status -> assertThat(
                scheduler.closePaymentAutomationForManualSettlement(26375L, "Repeated confirmation")).isZero());
    }

    @Test
    void closureCannotCommitInAnIndependentTransaction() {
        assertThatThrownBy(() -> scheduler.closePaymentAutomationForManualSettlement(26375L, "No transaction"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM reminder", String.class)).isEqualTo("ACTIVE");
    }
}
