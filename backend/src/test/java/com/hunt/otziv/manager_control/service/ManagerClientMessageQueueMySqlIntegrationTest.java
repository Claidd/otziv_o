package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
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
}
