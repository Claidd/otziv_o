package com.hunt.otziv.whatsapp;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupWebhookDeduplicator;
import com.hunt.otziv.whatsapp.service.WhatsAppInboundReplyOutbox;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.whatsapp.service.WhatsAppBusinessOperationService;
import com.hunt.otziv.whatsapp.repository.WhatsAppBusinessOperationRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WhatsAppInboundReceiptMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("wa_inbound").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private WhatsAppGroupWebhookDeduplicator receiver;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_inbound_receipts");
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_inbound_reply_outbox");
        jdbc.execute("DROP TABLE IF EXISTS inbound_test_effect");
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_business_send_operations");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_307__whatsapp_inbound_receipts.sql"), new ClassPathResource("db/migration/V1_10_312__whatsapp_reply_queue_claims.sql")).execute(ds);
        jdbc.execute("CREATE TABLE inbound_test_effect(id INT NOT NULL PRIMARY KEY, effect_count INT NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO inbound_test_effect VALUES(1,0)");
        jdbc.execute("CREATE TABLE whatsapp_business_send_operations(operation_id VARCHAR(128) PRIMARY KEY, "
                + "envelope_hash CHAR(64) NOT NULL,envelope_ciphertext MEDIUMTEXT NOT NULL, "
                + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
        receiver = new WhatsAppGroupWebhookDeduplicator(jdbc, manager);
    }

    @Test void commitThenLostAckThenBackendRestartHasExactlyOneEffect() {
        assertThat(receiver.execute(reply("one"), this::effect)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.APPLIED);
        // Sender saw no ACK and reconnects to an entirely new receiver instance.
        receiver = new WhatsAppGroupWebhookDeduplicator(jdbc, manager);
        assertThat(receiver.execute(reply("one"), this::effect)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.DUPLICATE);
        assertThat(effectCount()).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test void lateBusinessFailureAndCommitFailureRollBackReceiptAndEffect() {
        assertThatThrownBy(() -> receiver.execute(reply("one"), () -> { effect(); throw new IllegalStateException("late"); }))
                .hasMessage("late");
        assertThat(effectCount()).isZero(); assertThat(receiptCount()).isZero();
        assertThatThrownBy(() -> receiver.execute(reply("one"), () -> {
            effect(); TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("commit rejected"); }
            });
        })).hasMessage("commit rejected");
        assertThat(effectCount()).isZero(); assertThat(receiptCount()).isZero();
        assertThat(receiver.execute(reply("one"), this::effect)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.APPLIED);
        assertThat(effectCount()).isEqualTo(1);
    }

    @Test void concurrentLocalDeliveryGetsRetryableInProgressBeforeFirstRollsBack() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> receiver.execute(reply("one"), () -> {
                effect(); entered.countDown(); await(release); throw new IllegalStateException("rollback");
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receiver.execute(reply("one"), this::effect)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.IN_PROGRESS);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(receiptCount()).isZero(); assertThat(effectCount()).isZero();
        assertThat(receiver.execute(reply("one"), this::effect)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.APPLIED);
        assertThat(effectCount()).isEqualTo(1);
    }

    @Test void independentBackendInstancesSerializeOnDatabaseUniqueReceipt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        var other = new WhatsAppGroupWebhookDeduplicator(jdbc, manager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> receiver.execute(reply("one"), () -> {
                effect(); entered.countDown(); await(release); throw new IllegalStateException("rollback");
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> { started.countDown(); return other.execute(reply("one"), this::effect); });
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.APPLIED);
        }
        assertThat(effectCount()).isEqualTo(1); assertThat(receiptCount()).isEqualTo(1);
    }

    @Test void responseOutboxRollsBackWithReceiptAndDispatchSurvivesRestartAndLostAck() {
        var properties = new CredentialEncryptionProperties(); properties.setActiveKeyId("fixture");
        properties.setActiveKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        WhatsAppBusinessOperations operations = proxy(new WhatsAppBusinessOperationService(
                proxy(new WhatsAppBusinessOperationRepository(jdbc)), new CredentialCipher(properties)));
        var transport = mock(WhatsAppService.class);
        var outbox = proxy(new WhatsAppInboundReplyOutbox(jdbc, operations, transport, manager, () -> true));
        assertThatThrownBy(() -> receiver.execute(reply("one"), () -> {
            effect(); outbox.enqueue("response", "client", "12345678@g.us", "reply"); throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_inbound_reply_outbox", Integer.class)).isZero();
        assertThat(operations.findFrozen("response")).isEmpty();
        receiver.execute(reply("one"), () -> { effect(); outbox.enqueue("response", "client", "12345678@g.us", "reply"); });
        verifyNoInteractions(transport);
        assertThat(jdbc.queryForObject("SELECT envelope_ciphertext FROM whatsapp_business_send_operations", String.class))
                .startsWith("enc:v1:").doesNotContain("reply");
        when(transport.sendMessageToGroup("client", "12345678@g.us", "reply", "response"))
                .thenThrow(new IllegalStateException("ACK lost"));
        outbox.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_inbound_reply_outbox", String.class)).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)");
        when(transport.getOperationStatus("client", "response")).thenReturn(new com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus(
                "response", "SUCCEEDED", "provider-receipt", com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash("client","12345678@g.us","reply")));
        new WhatsAppInboundReplyOutbox(jdbc, operations, transport, manager, () -> true).dispatchDue();
        assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_inbound_reply_outbox", String.class)).isEqualTo("COMPLETE");
        verify(transport, times(1)).sendMessageToGroup("client", "12345678@g.us", "reply", "response");
        assertThat(effectCount()).isEqualTo(1);
    }

    private WhatsAppInboundReplyOutbox replyQueue(WhatsAppService transport, boolean enabled) {
        var properties = new CredentialEncryptionProperties(); properties.setActiveKeyId("fixture");
        properties.setActiveKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        var operations = proxy(new WhatsAppBusinessOperationService(proxy(new WhatsAppBusinessOperationRepository(jdbc)),new CredentialCipher(properties)));
        return proxy(new WhatsAppInboundReplyOutbox(jdbc,operations,transport,manager,()->enabled));
    }

    @Test void pausedQueueKeepsTheEnvelopeAndDoesNotSpendRetryBudget() {
        var transport=mock(WhatsAppService.class);var paused=replyQueue(transport,false);
        paused.enqueue("paused","client","12345678@g.us","original");
        paused.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM whatsapp_inbound_reply_outbox",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_inbound_reply_outbox",String.class)).isEqualTo("RETRYABLE");
        verifyNoInteractions(transport);
        jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)");
        when(transport.sendMessageToGroup(anyString(),anyString(),anyString(),anyString())).thenReturn("ok");
        replyQueue(transport,true).dispatchDue();
        verify(transport).sendMessageToGroup("client","12345678@g.us","original","paused");
    }

    @Test void healthMapsHistoricalQueueStatesWithoutExposingTheEnvelope() {
        var outbox = replyQueue(mock(WhatsAppService.class), true);
        assertThat(outbox.deliveryQueueHealth()).isEmpty();
        outbox.enqueue("health-operation", "client", "12345678@g.us", "private fixture");
        assertThat(outbox.deliveryQueueHealth()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.State.QUEUED);
            assertThat(row.jobs()).isEqualTo(1);
        });
        jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET state='COMPLETE'");
        assertThat(outbox.deliveryQueueHealth()).isEmpty();
    }

    @Test void knownAdmissionFailuresHaveABoundedBudgetAndUnknownNeverGetsAnotherDispatch() {
        var transport=mock(WhatsAppService.class);var outbox=replyQueue(transport,true);
        outbox.enqueue("bounded","client","12345678@g.us","original");
        when(transport.sendMessageToGroup(anyString(),anyString(),anyString(),anyString()))
                .thenReturn("{\"status\":\"error\",\"code\":\"gateway_not_ready\"}");
        for(int i=0;i<7;i++) {
            jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6)");
            outbox.dispatchDue();
        }
        verify(transport,times(5)).sendMessageToGroup("client","12345678@g.us","original","bounded");
        assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_inbound_reply_outbox",String.class)).isEqualTo("FAILED");
        outbox.enqueue("bounded","another-client","87654321@g.us","changed");
        outbox.dispatchDue();
        verifyNoMoreInteractions(transport);
    }

    @Test void aLateWorkerCannotOverwriteTheNewOwnersPositiveReceipt() throws Exception {
        var transport=mock(WhatsAppService.class);var outbox=replyQueue(transport,true);
        outbox.enqueue("fenced","client","12345678@g.us","original");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(transport.sendMessageToGroup(anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();await(release);return "unknown";
        });
        when(transport.getOperationStatus("client","fenced")).thenReturn(new com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus(
                "fenced","SUCCEEDED","provider-receipt",com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash("client","12345678@g.us","original")));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(outbox::dispatchDue);
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE whatsapp_inbound_reply_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
                replyQueue(transport,true).dispatchDue();
            } finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT state FROM whatsapp_inbound_reply_outbox",String.class)).isEqualTo("COMPLETE");
        verify(transport,times(1)).sendMessageToGroup("client","12345678@g.us","original","fenced");
        verify(transport).getOperationStatus("client","fenced");
    }

    @Test void identityIncludesClientAndChatAndReceiptHasNoExpiry() {
        receiver.execute(reply("same"), this::effect);
        jdbc.update("UPDATE whatsapp_inbound_receipts SET created_at='2000-01-01',completed_at='2000-01-01'");
        assertThat(new WhatsAppGroupWebhookDeduplicator(jdbc, manager).execute(reply("same"), this::effect))
                .isEqualTo(WhatsAppGroupWebhookDeduplicator.Result.DUPLICATE);
        var second = reply("same"); second.setClientId("another"); receiver.execute(second, this::effect);
        var third = reply("same"); third.setGroupId("2@g.us"); receiver.execute(third, this::effect);
        assertThat(effectCount()).isEqualTo(3);
    }

    private void effect() { jdbc.update("UPDATE inbound_test_effect SET effect_count=effect_count+1 WHERE id=1"); }
    private int effectCount() { return jdbc.queryForObject("SELECT effect_count FROM inbound_test_effect WHERE id=1", Integer.class); }
    private int receiptCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_inbound_receipts", Integer.class); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static WhatsAppGroupReplyDTO reply(String messageId) {
        var reply = new WhatsAppGroupReplyDTO(); reply.setClientId("client"); reply.setGroupId("1@g.us");
        reply.setMessageId(messageId); reply.setMessage("message"); return reply;
    }

    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new org.springframework.aop.framework.ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
