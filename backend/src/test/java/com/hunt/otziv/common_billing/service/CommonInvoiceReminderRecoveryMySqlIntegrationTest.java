package com.hunt.otziv.common_billing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.api.*;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.security.credentials.*;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Real encrypted queue, audit SQL, locks and independent Spring/MySQL transactions. */
@Testcontainers
class CommonInvoiceReminderRecoveryMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("invoice_reminder_recovery").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; DataSourceTransactionManager transactions; TransactionTemplate tx;
    CommonInvoiceMessageQueue queue; CommonInvoiceReminderRecoveryStore store;
    CommonInvoiceRepository invoices; CommonInvoiceSettlementService settlement;
    ClientMessageDelivery delivery; CommonInvoiceFutureReminderRecovery recovery;
    CommonBillingAccount account; Company company; Manager manager; CommonInvoiceOrder position;
    LocalDateTime next;

    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds); transactions = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(transactions);
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_message_queue");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_311__common_invoice_message_queue.sql")));
        jdbc.execute("DROP TABLE IF EXISTS client_reminder_restart_audit");
        String audit = Files.readString(Path.of("src/main/resources/db/migration/V1_10_341__authorized_reminder_restart.sql"));
        jdbc.execute(audit.substring(audit.indexOf("CREATE TABLE")));
        jdbc.execute("DROP TABLE IF EXISTS invoice_recovery_fixture");
        jdbc.execute("""
                CREATE TABLE invoice_recovery_fixture(id BIGINT PRIMARY KEY,status VARCHAR(32),amount BIGINT,paid BIGINT,
                  sent DATETIME(6),last_reminder DATETIME(6),next_reminder DATETIME(6),updated DATETIME(6),closed DATETIME(6),
                  error VARCHAR(100),operation_id VARCHAR(160),kind VARCHAR(32),confirmed BOOLEAN,channel VARCHAR(32)) ENGINE=InnoDB
                """);
        var keys = new CredentialEncryptionProperties(); keys.setActiveKeyId("fixture");
        keys.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        queue = proxy(new CommonInvoiceMessageQueue(jdbc, new ObjectMapper(), new CredentialCipher(keys)));
        company = new Company(); company.setId(7L); company.setTitle("Fixture"); company.setUrlChat("https://t.me/fixture");
        company.setTelegramGroupChatId(-1001L); company.setGroupId("fixture-group");
        manager = new Manager(); manager.setId(2L); manager.setClientId("fixture-client");
        account = new CommonBillingAccount(); account.setId(3L); account.setEnabled(true); account.setManager(manager); account.setInvoiceCompany(company);
        var order = new Order(); order.setId(77L); order.setClientMessageGeneration(2); order.setStatusChangedAt(LocalDateTime.now().minusDays(10));
        position = new CommonInvoiceOrder(); position.setId(1L); position.setOrder(order); position.setReady(true); position.setActiveMembership(true); position.setAmountKopecks(120000);
        jdbc.update("""
                INSERT INTO invoice_recovery_fixture VALUES(10,'REMINDER',120000,0,
                  DATE_SUB(NOW(6),INTERVAL 10 DAY),DATE_SUB(NOW(6),INTERVAL 2 DAY),NOW(6),NOW(6),NULL,
                  'message_send_in_progress','invoice-message:fixture','REMINDER',FALSE,NULL)
                """);
        tx.executeWithoutResult(s -> queue.enqueue(draft(true, false, false, "invoice-message:fixture")));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(s -> queue.finish(claim, "UNKNOWN", "operation_unknown", 300));
        jdbc.update("UPDATE common_invoice_message_queue SET created_at=?", LocalDateTime.now().minusHours(1));
        invoices = mock(CommonInvoiceRepository.class);
        when(invoices.findByIdWithAccount(10L)).thenAnswer(c -> Optional.of(invoice(false)));
        when(invoices.save(any())).thenAnswer(c -> {
            CommonInvoice i = c.getArgument(0);
            jdbc.update("UPDATE invoice_recovery_fixture SET operation_id=?,kind=?,confirmed=?,channel=?,error=?,next_reminder=?,updated=NOW(6) WHERE id=10",
                    i.getPaymentMessageOperationId(), i.getPaymentMessageOperationKind(), i.isPaymentMessageConfirmed(), i.getPaymentMessageChannel(), i.getLastError(), i.getNextReminderAt());
            return i;
        });
        settlement = mock(CommonInvoiceSettlementService.class);
        when(settlement.automaticPaymentRemindersEnabled()).thenReturn(true);
        when(settlement.lockedInvoice(10L)).thenAnswer(c -> Optional.of(invoice(true)));
        var items = mock(CommonInvoiceOrderRepository.class); when(items.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(position));
        var initialization = mock(CommonInvoiceInitializationService.class, CALLS_REAL_METHODS);
        doReturn(company).when(initialization).chatCompany(any()); doReturn(manager).when(initialization).manager(any());
        org.springframework.test.util.ReflectionTestUtils.setField(initialization, "invoiceOrderRepository", items);
        var planner = mock(PeriodicReminderRestart.class); next = LocalDateTime.now().plusDays(3).withNano(0);
        when(planner.futurePaymentReminderAt(any())).thenReturn(next);
        store = proxy(new CommonInvoiceReminderRecoveryStore(invoices, initialization, settlement, queue, jdbc, planner));
        delivery = mock(ClientMessageDelivery.class);
        when(delivery.hasSettledUncertainOperation(anyString())).thenAnswer(c -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return true;
        });
        recovery = proxy(new CommonInvoiceFutureReminderRecovery(store, delivery, mock(SchedulerLeaseService.class)));
    }

    PreparedCommonInvoiceMessage draft(boolean reminder, boolean manual, boolean changedRoute, String operation) {
        return new PreparedCommonInvoiceMessage(10L, new ClientMessageDelivery.Target(7L, "Fixture", "https://t.me/fixture", -1001L, null),
                "fixture-client", "fixture-group", "fixture payment reminder", null, reminder, manual, changedRoute, operation, false, null, 120000);
    }

    CommonInvoice invoice(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM invoice_recovery_fixture WHERE id=10" + (lock ? " FOR UPDATE" : ""), (r,n) -> {
            var i = new CommonInvoice(); i.setId(10L); i.setAccount(account); i.setStatus(CommonInvoiceStatus.valueOf(r.getString("status")));
            i.setAmountKopecks(r.getLong("amount")); i.setPaidKopecks(r.getLong("paid"));
            i.setSentAt(r.getTimestamp("sent") == null ? null : r.getTimestamp("sent").toLocalDateTime());
            i.setLastReminderAt(r.getTimestamp("last_reminder").toLocalDateTime());
            i.setNextReminderAt(r.getTimestamp("next_reminder").toLocalDateTime()); i.setUpdatedAt(r.getTimestamp("updated").toLocalDateTime());
            i.setClosedAt(r.getTimestamp("closed") == null ? null : r.getTimestamp("closed").toLocalDateTime());
            i.setLastError(r.getString("error")); i.setPaymentMessageOperationId(r.getString("operation_id")); i.setPaymentMessageOperationKind(r.getString("kind"));
            i.setPaymentMessageConfirmed(r.getBoolean("confirmed")); i.setPaymentMessageChannel(r.getString("channel")); return i;
        });
    }

    @Test void automaticRecoveryAuditsTheUnknownAndFencesOldClaimsWithoutChangingMoneyOrConfirmedContact() {
        var before = invoice(false); var prepared = queue.snapshot("invoice-message:fixture");
        assertThat(store.candidates(0, 20)).containsExactly(10L);
        assertThat(recovery.recover(10)).contains(next);
        assertThat(recovery.recover(10)).contains(next);
        assertThat(store.candidates(0, 20)).isEmpty();
        assertThat(queue.status("invoice-message:fixture").status()).isEqualTo("SUPERSEDED");
        assertThat(queue.snapshot("invoice-message:fixture")).isEqualTo(prepared);
        assertThat(queue.claim()).isEmpty(); assertThat(queue.claimRecovery(10,"invoice-message:fixture")).isEmpty();
        var after = invoice(false);
        assertThat(after.getAmountKopecks()).isEqualTo(before.getAmountKopecks()); assertThat(after.getPaidKopecks()).isZero();
        assertThat(after.getStatus()).isEqualTo(before.getStatus()); assertThat(after.getSentAt()).isEqualTo(before.getSentAt());
        assertThat(after.getLastReminderAt()).isEqualTo(before.getLastReminderAt()); assertThat(after.isPaymentMessageConfirmed()).isFalse();
        assertThat(after.getPaymentMessageOperationId()).isNull(); assertThat(after.getNextReminderAt()).isEqualTo(next);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.state')) FROM client_reminder_restart_audit", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
        verify(delivery).hasSettledUncertainOperation("invoice-message:fixture"); verifyNoMoreInteractions(delivery);
    }

    @Test void databaseFailureRollsBackQueueRetirementAuditAndInvoicePointerTogether() {
        doThrow(new IllegalStateException("fixture invoice save failed")).when(invoices).save(any());
        assertThatThrownBy(() -> recovery.recover(10)).hasMessageContaining("invoice save failed");
        assertThat(queue.status("invoice-message:fixture").status()).isEqualTo("UNKNOWN");
        assertThat(invoice(false).getPaymentMessageOperationId()).isEqualTo("invoice-message:fixture");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isZero();
    }

    @Test void concurrentRepairsCanRetireTheOldCommandOnlyOnce() throws Exception {
        var checked = store.inspect(10).orElseThrow(); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> work = () -> { start.await(); return store.restore(checked).isPresent(); };
        try {
            var a = pool.submit(work); var b = pool.submit(work); start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS), b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"needs-attention","initial-send","route-change","manual","not-ready","paid","partial-payment","missing-sent","lease","fresh","second-command","disabled","recipient"})
    void unrelatedOrChangedBusinessAndDispatchStateCannotBeRetired(String change) {
        switch (change) {
            case "needs-attention" -> jdbc.update("UPDATE invoice_recovery_fixture SET status='NEEDS_ATTENTION'");
            case "initial-send" -> jdbc.update("UPDATE invoice_recovery_fixture SET kind='INVOICE'");
            case "paid" -> jdbc.update("UPDATE invoice_recovery_fixture SET paid=amount");
            case "partial-payment" -> jdbc.update("UPDATE invoice_recovery_fixture SET paid=1000");
            case "missing-sent" -> jdbc.update("UPDATE invoice_recovery_fixture SET sent=NULL");
            case "lease" -> jdbc.update("UPDATE common_invoice_message_queue SET lease_until=DATE_ADD(NOW(6),INTERVAL 1 MINUTE)");
            case "fresh" -> jdbc.update("UPDATE common_invoice_message_queue SET created_at=NOW(6)");
            case "not-ready" -> position.setReady(false);
            case "disabled" -> account.setEnabled(false);
            case "recipient" -> company.setTelegramGroupChatId(-1002L);
            case "second-command" -> tx.executeWithoutResult(s -> queue.enqueue(draft(true,false,false,"other-operation")));
            case "manual", "route-change" -> {
                jdbc.update("DELETE FROM common_invoice_message_queue");
                tx.executeWithoutResult(s -> queue.enqueue(draft(true,"manual".equals(change),"route-change".equals(change),"invoice-message:fixture")));
                var claim = queue.claim().orElseThrow(); tx.executeWithoutResult(s -> queue.finish(claim,"UNKNOWN","operation_unknown",300));
                jdbc.update("UPDATE common_invoice_message_queue SET created_at=?", LocalDateTime.now().minusHours(1));
            }
        }
        assertThat(recovery.recover(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isZero();
        assertThat(invoice(false).getPaymentMessageOperationId()).isEqualTo("invoice-message:fixture");
        verifyNoInteractions(delivery);
    }

    @Test void aNewPaymentBetweenInspectionAndLockCancelsFuturePlanning() {
        var checked = store.inspect(10).orElseThrow(); jdbc.update("UPDATE invoice_recovery_fixture SET paid=1000");
        assertThat(store.restore(checked)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isZero();
        assertThat(queue.status("invoice-message:fixture").status()).isEqualTo("UNKNOWN");
    }
}
