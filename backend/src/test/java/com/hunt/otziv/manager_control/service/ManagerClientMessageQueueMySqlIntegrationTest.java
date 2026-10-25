package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.util.Optional;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ManagerClientMessageQueueMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_queue").withUsername("root").withPassword(UUID.randomUUID().toString());
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private ManagerClientMessageQueue queue;
    private CredentialCipher cipher;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        manager = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(manager); jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_queue");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_313__manager_client_message_queue.sql")));
        var properties = new CredentialEncryptionProperties();
        properties.setActiveKeyId("queue-test");
        properties.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new CredentialCipher(properties); queue = instance();
    }

    private ManagerClientMessageQueue instance() {
        var proxy = new ProxyFactory(new ManagerClientMessageQueue(jdbc, new ObjectMapper(), cipher));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (ManagerClientMessageQueue) proxy.getProxy();
    }

    private ManagerClientMessageQueue.Command draft(String text) {
        return new ManagerClientMessageQueue.Command("manager-command:test", 10L, "CONTROL", text,
                new com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor(7L, "fixture", 0L, java.util.Set.of("ROLE_MANAGER")));
    }

    @Test void intentAndCiphertextRollbackWithTheBusinessTransaction() {
        tx.executeWithoutResult(status -> { queue.enqueue(draft("К оплате 1200")); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isZero();
        tx.executeWithoutResult(status -> queue.enqueue(draft("К оплате 1200")));
        assertThat(jdbc.queryForObject("SELECT envelope_ciphertext FROM manager_client_message_queue", String.class))
                .startsWith("enc:").doesNotContain("К оплате 1200", "original-group", "frozen-copy");
        assertThat(instance().snapshot("manager-command:test")).isEqualTo(draft("К оплате 1200"));
    }

    @Test void duplicateCommandCannotChangeMoneyRecipientOrText() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> queue.enqueue(draft("changed"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_queue", Integer.class)).isEqualTo(1);
        assertThat(queue.snapshot("manager-command:test").preparedJson()).isEqualTo("original");
    }

    @Test void concurrentClaimIsExclusiveAndExpiredClaimCanOnlyReadReceipts() throws Exception {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            Callable<java.util.Optional<ManagerClientMessageQueue.Claim>> action = () -> { ready.await(); return instance().claim(); };
            var a = pool.submit(action); var b = pool.submit(action); ready.countDown();
            var x = a.get(10, TimeUnit.SECONDS); var y = b.get(10, TimeUnit.SECONDS);
            assertThat(x.isPresent() ^ y.isPresent()).isTrue();
            var first = x.orElseGet(y::orElseThrow);
            assertThat(first.mayDispatch()).isTrue();
            jdbc.update("UPDATE manager_client_message_queue SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
            var recovered = instance().claim().orElseThrow();
            assertThat(recovered.mayDispatch()).isFalse();
            tx.executeWithoutResult(status -> { assertThat(queue.owns(first)).isFalse(); queue.finish(first, "SENT", null, 0); });
            assertThat(queue.status(10L, first.operationId()).status()).isEqualTo("SENDING");
            tx.executeWithoutResult(status -> { assertThat(queue.owns(recovered)).isTrue(); queue.finish(recovered, "UNKNOWN", "operation_unknown", 0); });
            var retry = instance().claim().orElseThrow();
            assertThat(retry.mayDispatch()).isFalse();
            assertThat(queue.status(10L, retry.operationId()).attempts()).isEqualTo(1);
        }
    }

    @Test void pausedSwitchDoesNotSpendAttemptsOrReleaseFrozenEnvelope() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "RETRYABLE", "live_disabled", 0));
        assertThat(queue.status(10L, claim.operationId()).attempts()).isZero();
        assertThat(instance().claim().orElseThrow().mayDispatch()).isTrue();
        assertThat(queue.snapshot(claim.operationId())).isEqualTo(draft("original"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UNKNOWN", "FAILED"})
    void supersededReminderKeepsCiphertextAndRevokesEveryOldClaim(String previousState) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS client_reminder_restart_audit");
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1_10_341__authorized_reminder_restart.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE")));
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var oldClaim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(oldClaim, previousState,
                "FAILED".equals(previousState) ? "context_changed" : "whatsapp_history_checked_no_receipt", 0));
        jdbc.update("UPDATE manager_client_message_queue SET created_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY)");
        var row = new ManagerClientMessageQueue.RecordedControl(queue.status(10, oldClaim.operationId()), 10,
                java.time.LocalDateTime.now().minusDays(3), java.time.LocalDateTime.now(), null);
        tx.executeWithoutResult(status -> queue.supersedeStaleReminder(row, "owner", java.time.LocalDateTime.now().plusDays(2)));
        tx.executeWithoutResult(status -> queue.finish(oldClaim, "SENT", null, 0));
        assertThat(queue.status(10, oldClaim.operationId()).status()).isEqualTo("SUPERSEDED");
        assertThat(queue.snapshot(oldClaim.operationId())).isEqualTo(draft("original"));
        assertThat(queue.claim()).isEmpty();
        assertThat(queue.claimReceipt(oldClaim.operationId())).isEmpty();
        assertThat(queue.blockingForCard(10)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
    }

    @Test void healthReadsOnlyTheOwnersUnfinishedJobs() {
        assertThat(queue.deliveryQueueHealth()).isEmpty();
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        assertThat(queue.deliveryQueueHealth()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.State.QUEUED);
            assertThat(row.jobs()).isEqualTo(1);
        });
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "SENT", null, 0));
        assertThat(queue.deliveryQueueHealth()).isEmpty();
    }

    @Test void cancelledReservationAuditAndReminderResumeRollbackTogetherAndPreserveRepeatDeadline() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS client_reminder_restart_audit");
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1_10_341__authorized_reminder_restart.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE")));
        jdbc.execute("DROP TABLE IF EXISTS reminder_resume_fixture");
        jdbc.execute("CREATE TABLE reminder_resume_fixture (id BIGINT PRIMARY KEY,next_at DATETIME(6),error_code VARCHAR(100),sent_count INT) ENGINE=InnoDB");
        LocalDateTime before = LocalDateTime.now().plusMinutes(5).withNano(0);
        LocalDateTime next = LocalDateTime.now().plusDays(2).withNano(0);
        jdbc.update("INSERT INTO reminder_resume_fixture VALUES (30,?,'manual_contact_reserved',3)", before);
        var source = new OrderDeliveryControlContext.Snapshot(77L, 2L, "На проверке", 1,
                LocalDateTime.now().minusDays(20).withNano(0), false, null);
        String target = "order:77:" + source.cycleAt();
        var check = new PeriodicReminderRestart.ReservedReminderCheck(30L, target, "REVIEW_CHECK_REMINDER", source,
                "fixture-client", "fixture-chat", 0, 3, LocalDateTime.now().minusDays(3), LocalDateTime.now());
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "FAILED", "context_changed", 0));
        jdbc.update("UPDATE manager_client_message_queue SET created_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 3 DAY)");
        var row = new ManagerClientMessageQueue.RecordedControl(queue.status(10, claim.operationId()), 10,
                LocalDateTime.now().minusDays(3), LocalDateTime.now(), null);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE reminder_resume_fixture SET next_at=?,error_code=NULL WHERE id=30", next);
            queue.supersedeCancelledReservation(row, "owner", next, check);
            throw new IllegalStateException("card update failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queue.status(10, claim.operationId()).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT next_at FROM reminder_resume_fixture", LocalDateTime.class)).isEqualTo(before);

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE reminder_resume_fixture SET next_at=?,error_code=NULL WHERE id=30", next);
            queue.supersedeCancelledReservation(row, "owner", next, check);
        });
        assertThat(queue.status(10, claim.operationId()).status()).isEqualTo("SUPERSEDED");
        assertThat(queue.snapshot(claim.operationId())).isEqualTo(draft("original"));
        assertThat(queue.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT old_operation_id FROM client_reminder_restart_audit", String.class)).isEqualTo(claim.operationId());
        assertThat(jdbc.queryForObject("SELECT sent_count FROM reminder_resume_fixture", Integer.class)).isEqualTo(3);

        var states = mock(ScheduledClientMessageStateRepository.class);
        when(states.findById(30L)).thenAnswer(call -> Optional.of(jdbc.queryForObject("SELECT * FROM reminder_resume_fixture", (rs,n) ->
                ScheduledClientMessageState.builder().id(30L).orderId(77L).targetKey(target)
                        .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER).status(ScheduledMessageStateStatus.ACTIVE)
                        .sentCount(rs.getInt("sent_count")).nextAttemptAt(rs.getTimestamp("next_at").toLocalDateTime())
                        .lastErrorCode(rs.getString("error_code")).build())));
        var orders = mock(OrderDeliveryControlContext.class);
        when(orders.read(77L)).thenReturn(Optional.of(source));
        var scheduled = mock(ScheduledClientMessageService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(scheduled, "stateRepository", states);
        ReflectionTestUtils.setField(scheduled, "orderDeliveryContext", orders);
        ReflectionTestUtils.setField(scheduled, "jdbcTemplate", jdbc);
        assertThat(scheduled.pendingReminderRestart(30, 2L)).contains(new PeriodicReminderRestart.PendingReminderRestart(source, next));
        assertThat(scheduled.pendingReminderRestart(30, 2L)).contains(new PeriodicReminderRestart.PendingReminderRestart(source, next));
        jdbc.update("UPDATE reminder_resume_fixture SET next_at=?", next.plusHours(1));
        assertThat(scheduled.pendingReminderRestart(30, 2L)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit", Integer.class)).isEqualTo(1);
    }

    @Test void explicitReceiptClaimNeverDispatchesOrSpendsAttemptsAndIsExclusive() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        assertThat(queue.claimReceipt("manager-command:test")).isEmpty(); // queued is not a receipt check
        jdbc.update("UPDATE manager_client_message_queue SET state='RETRYABLE'");
        assertThat(queue.claimReceipt("manager-command:test")).isEmpty();
        jdbc.update("UPDATE manager_client_message_queue SET state='UNKNOWN',attempts=1,next_attempt_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6))");
        var first = queue.claimReceipt("manager-command:test").orElseThrow();
        assertThat(first.mayDispatch()).isFalse();
        assertThat(queue.claimReceipt("manager-command:test")).isEmpty();
        assertThat(queue.status(10L, first.operationId()).attempts()).isEqualTo(1);
        jdbc.update("UPDATE manager_client_message_queue SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        var next = queue.claimReceipt("manager-command:test").orElseThrow();
        tx.executeWithoutResult(status -> {
            assertThat(queue.owns(first)).isFalse();
            assertThat(queue.owns(next)).isTrue();
            queue.finish(first, "SENT", null, 0);
        });
        assertThat(queue.status(10L, next.operationId()).status()).isEqualTo("UNKNOWN");
    }
}
