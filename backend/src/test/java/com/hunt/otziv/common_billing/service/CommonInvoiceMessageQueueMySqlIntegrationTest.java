package com.hunt.otziv.common_billing.service;

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
class CommonInvoiceMessageQueueMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("billing_queue").withUsername("root").withPassword(UUID.randomUUID().toString());
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private CommonInvoiceMessageQueue queue;
    private CredentialCipher cipher;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        manager = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(manager); jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_message_queue");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_311__common_invoice_message_queue.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_401__reviewed_common_invoice_messages.sql")));
        var properties = new CredentialEncryptionProperties();
        properties.setActiveKeyId("queue-test");
        properties.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new CredentialCipher(properties); queue = instance();
    }

    private CommonInvoiceMessageQueue instance() {
        var proxy = new ProxyFactory(new CommonInvoiceMessageQueue(jdbc, new ObjectMapper(), cipher));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (CommonInvoiceMessageQueue) proxy.getProxy();
    }

    private PreparedCommonInvoiceMessage draft(String text) {
        return new PreparedCommonInvoiceMessage(10L, new ClientMessageDelivery.Target(7L, "Company", "https://t.me/test", -1001L, null),
                "original-client", "original-group", text, "frozen-copy", false, true, false, "invoice-message:test", false, null, 120000);
    }

    @Test void retiresOnlyUnstartedCommandsWithoutEitherProviderRecordAndKeepsHistory() {
        var delivery = org.mockito.Mockito.mock(ClientMessageDelivery.class);
        org.mockito.Mockito.when(delivery.hasNoRecordedOperation("invoice-message:test")).thenReturn(true);
        tx.executeWithoutResult(status -> queue.enqueue(draft("old amount")));
        jdbc.update("UPDATE common_invoice_message_queue SET state='FAILED',error_code='gateway_not_ready',attempts=5");
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L, "invoice-message:test", delivery))).isTrue();
        assertThat(queue.status("invoice-message:test").status()).isEqualTo("SUPERSEDED");
        assertThat(queue.status("invoice-message:test").attempts()).isEqualTo(5);
        assertThat(queue.snapshot("invoice-message:test").message()).isEqualTo("old amount");
        assertThat(queue.unresolved(10L)).isEmpty();
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L, "invoice-message:test", delivery))).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UNKNOWN", "SENDING", "SENT", "started", "lease", "claim", "provider", "uncertain", "replacement"})
    void recoveryCannotRetireUncertainOrOwnedCommands(String condition) {
        var delivery = org.mockito.Mockito.mock(ClientMessageDelivery.class);
        org.mockito.Mockito.when(delivery.hasNoRecordedOperation("invoice-message:test")).thenReturn(!condition.equals("provider"));
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        jdbc.update("UPDATE common_invoice_message_queue SET state='FAILED',error_code='gateway_not_ready'");
        switch (condition) {
            case "UNKNOWN", "SENDING", "SENT" -> jdbc.update("UPDATE common_invoice_message_queue SET state=?", condition);
            case "started" -> jdbc.update("UPDATE common_invoice_message_queue SET dispatch_started_at=NOW(6)");
            case "lease" -> jdbc.update("UPDATE common_invoice_message_queue SET lease_until=NOW(6)-INTERVAL 1 HOUR");
            case "claim" -> jdbc.update("UPDATE common_invoice_message_queue SET claim_token='owner'");
            case "uncertain" -> jdbc.update("UPDATE common_invoice_message_queue SET error_code='operation_unknown'");
            case "replacement" -> jdbc.update("UPDATE common_invoice_message_queue SET replacement_operation_id='replacement'");
            default -> { }
        }
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L, "invoice-message:test", delivery))).isFalse();
        assertThat(queue.status("invoice-message:test").status()).isNotEqualTo("SUPERSEDED");
    }

    @Test void recoveryRetirementRollsBackWhenNewInvoicePreparationFails() {
        var delivery = org.mockito.Mockito.mock(ClientMessageDelivery.class);
        org.mockito.Mockito.when(delivery.hasNoRecordedOperation("invoice-message:test")).thenReturn(true);
        tx.executeWithoutResult(s -> queue.enqueue(draft("original")));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            assertThat(queue.retireUnstarted(10L, "invoice-message:test", delivery)).isTrue();
            throw new IllegalStateException("new invoice is not ready");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queue.unresolved(10L)).isPresent();
        assertThat(queue.status("invoice-message:test").status()).isEqualTo("QUEUED");
    }

    @Test void recoveredInvoiceKeepsOneNewCommandAndAnExplicitLinkToItsOldVersion() {
        var delivery = org.mockito.Mockito.mock(ClientMessageDelivery.class);
        org.mockito.Mockito.when(delivery.hasNoRecordedOperation("invoice-message:test")).thenReturn(true);
        var old = draft("old amount");
        var replacement = new PreparedCommonInvoiceMessage(10L, old.chatCompany(), old.managerClientId(),
                old.groupId(), "new amount", old.telegramCopyTransferNumber(), false, false, false,
                "invoice-recovery:10:test", false, null, 250000);
        tx.executeWithoutResult(s -> {
            queue.enqueue(old);
            assertThat(queue.retireUnstarted(10L, old.operationId(), delivery)).isTrue();
            queue.enqueue(replacement);
            queue.linkRetiredReplacement(10L, old.operationId(), replacement.operationId());
        });
        assertThat(queue.unresolved(10L)).contains(replacement);
        assertThat(queue.snapshot(old.operationId())).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT replacement_operation_id FROM common_invoice_message_queue WHERE operation_id=?",
                String.class, old.operationId())).isEqualTo(replacement.operationId());
        tx.executeWithoutResult(s -> queue.enqueue(replacement));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_message_queue WHERE state='QUEUED'", Integer.class)).isEqualTo(1);
    }

    @Test void frozenGatewayIntentNeedsBothDurableRejectionAndLiveAbsenceEvidence() {
        var delivery = org.mockito.Mockito.mock(ClientMessageDelivery.class);
        tx.executeWithoutResult(s -> queue.enqueue(draft("original")));
        jdbc.update("UPDATE common_invoice_message_queue SET state='FAILED',error_code='gateway_not_ready'");
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L,"invoice-message:test",delivery,false))).isFalse();
        jdbc.update("UPDATE common_invoice_message_queue SET state='UNKNOWN'");
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L,"invoice-message:test",delivery,true))).isFalse();
        jdbc.update("UPDATE common_invoice_message_queue SET state='FAILED',error_code='operation_unknown'");
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L,"invoice-message:test",delivery,true))).isFalse();
        jdbc.update("UPDATE common_invoice_message_queue SET error_code='gateway_not_ready'");
        assertThat(tx.<Boolean>execute(s -> queue.retireUnstarted(10L,"invoice-message:test",delivery,true))).isTrue();
    }

    @Test void migrationRetiresOnlyProvenUnstartedFullyPaidCommands() throws Exception {
        jdbc.execute("CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY,status VARCHAR(30),amount_kopecks BIGINT,paid_kopecks BIGINT)");
        jdbc.execute("CREATE TABLE whatsapp_business_send_operations(operation_id VARCHAR(160) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE client_message_operations(operation_id VARCHAR(160) PRIMARY KEY)");
        try {
            jdbc.update("INSERT INTO common_invoices VALUES(10,'PAID',120000,120000),(11,'READY',120000,0),(12,'PARTIALLY_PAID',120000,60000)");
            for (String key : java.util.List.of("cancel", "unknown", "sent", "dispatched", "whatsapp", "telegram", "unpaid", "partial", "leased", "other-error")) {
                jdbc.update("INSERT INTO common_invoice_message_queue(operation_id,invoice_id,envelope_ciphertext,state,error_code) VALUES(?,?,'fixture','FAILED','context_changed')",
                        key, "unpaid".equals(key) ? 11 : "partial".equals(key) ? 12 : 10);
            }
            jdbc.update("UPDATE common_invoice_message_queue SET state='UNKNOWN' WHERE operation_id='unknown'");
            jdbc.update("UPDATE common_invoice_message_queue SET state='SENT' WHERE operation_id='sent'");
            jdbc.update("UPDATE common_invoice_message_queue SET dispatch_started_at=NOW(6) WHERE operation_id='dispatched'");
            jdbc.update("UPDATE common_invoice_message_queue SET lease_until=NOW(6) WHERE operation_id='leased'");
            jdbc.update("UPDATE common_invoice_message_queue SET error_code='gateway_not_ready' WHERE operation_id='other-error'");
            jdbc.update("INSERT INTO whatsapp_business_send_operations VALUES('whatsapp')");
            jdbc.update("INSERT INTO client_message_operations VALUES('telegram')");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V1_10_405__retire_unstarted_paid_invoice_notices.sql"));
            jdbc.execute(migration); jdbc.execute(migration);
            assertThat(jdbc.queryForList("SELECT operation_id FROM common_invoice_message_queue WHERE state='SUPERSEDED'", String.class)).containsExactly("cancel");
            assertThat(queue.status("cancel").errorCode()).isEqualTo("invoice_paid_before_dispatch");
            assertThat(queue.status("unknown").status()).isEqualTo("UNKNOWN");
            assertThat(queue.status("sent").status()).isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT paid_kopecks FROM common_invoices WHERE invoice_id=10", Long.class)).isEqualTo(120000);
        } finally {
            jdbc.execute("DROP TABLE client_message_operations");
            jdbc.execute("DROP TABLE whatsapp_business_send_operations");
            jdbc.execute("DROP TABLE common_invoices");
        }
    }

    @Test void intentAndCiphertextRollbackWithTheBusinessTransaction() {
        tx.executeWithoutResult(status -> { queue.enqueue(draft("К оплате 1200")); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_message_queue", Integer.class)).isZero();
        tx.executeWithoutResult(status -> queue.enqueue(draft("К оплате 1200")));
        assertThat(jdbc.queryForObject("SELECT envelope_ciphertext FROM common_invoice_message_queue", String.class))
                .startsWith("enc:").doesNotContain("К оплате 1200", "original-group", "frozen-copy");
        assertThat(instance().snapshot("invoice-message:test")).isEqualTo(draft("К оплате 1200"));
    }

    @Test void duplicateCommandCannotChangeMoneyRecipientOrText() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> queue.enqueue(draft("changed"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_message_queue", Integer.class)).isEqualTo(1);
        assertThat(queue.snapshot("invoice-message:test").message()).isEqualTo("original");
    }

    @Test void copiedCiphertextCannotAuthorizeAnotherInvoice() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        jdbc.update("UPDATE common_invoice_message_queue SET invoice_id=11");
        assertThatThrownBy(() -> queue.snapshot("invoice-message:test")).isInstanceOf(IllegalStateException.class);
    }

    @Test void manualRetryCannotReopenACommandWhoseBusinessContextChanged() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "FAILED", "context_changed", 0));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> queue.retryKnownUnsent(claim.operationId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(queue.status(claim.operationId()).status()).isEqualTo("FAILED");
    }

    @Test void concurrentClaimIsExclusiveAndExpiredClaimCanOnlyReadReceipts() throws Exception {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            Callable<java.util.Optional<CommonInvoiceMessageQueue.Claim>> action = () -> { ready.await(); return instance().claim(); };
            var a = pool.submit(action); var b = pool.submit(action); ready.countDown();
            var x = a.get(10, TimeUnit.SECONDS); var y = b.get(10, TimeUnit.SECONDS);
            assertThat(x.isPresent() ^ y.isPresent()).isTrue();
            var first = x.orElseGet(y::orElseThrow);
            assertThat(first.mayDispatch()).isTrue();
            jdbc.update("UPDATE common_invoice_message_queue SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
            var recovered = instance().claim().orElseThrow();
            assertThat(recovered.mayDispatch()).isFalse();
            tx.executeWithoutResult(status -> { assertThat(queue.owns(first)).isFalse(); queue.finish(first, "SENT", null, 0); });
            assertThat(queue.status(first.operationId()).status()).isEqualTo("SENDING");
            tx.executeWithoutResult(status -> { assertThat(queue.owns(recovered)).isTrue(); queue.finish(recovered, "UNKNOWN", "operation_unknown", 0); });
            var retry = instance().claim().orElseThrow();
            assertThat(retry.mayDispatch()).isFalse();
            assertThat(queue.status(retry.operationId()).attempts()).isEqualTo(1);
        }
    }

    @Test void reviewedReplacementKeepsPriorReceiptOnlyAndIssuesOneNewAmount() {
        var old = draft("К оплате 1400");
        var next = new PreparedCommonInvoiceMessage(10L, old.chatCompany(), old.managerClientId(), old.groupId(),
                "К оплате 1800", "frozen-copy", false, false, false, "bad-review-invoice:10:2", false, null, 180000).asReviewedUpdate();
        tx.executeWithoutResult(status -> queue.enqueue(old));
        var claim = queue.claim().orElseThrow();
        queue.markDispatchStarted(claim);
        assertThat(queue.reviewRow(10L, old.operationId()).attempted()).isNotNull();
        tx.executeWithoutResult(status -> queue.finish(claim, "UNKNOWN", "operation_unknown", 0));
        tx.executeWithoutResult(status -> assertThat(queue.recordStatementReview(10L, old.operationId(), next.operationId(), "operator")).isFalse());
        var checked = queue.claim().orElseThrow();
        assertThat(checked.mayDispatch()).isFalse();
        tx.executeWithoutResult(status -> queue.finish(checked, "UNKNOWN", "whatsapp_history_checked_no_receipt", 300));
        tx.executeWithoutResult(status -> {
            assertThat(queue.recordStatementReview(10L, old.operationId(), next.operationId(), "operator")).isTrue();
            queue.enqueue(next);
        });
        tx.executeWithoutResult(status -> assertThat(queue.recordStatementReview(10L, old.operationId(), next.operationId(), "operator")).isFalse());
        assertThat(queue.unresolved(10L)).contains(next);
        assertThat(next.reviewedUpdate()).isTrue();
        assertThat(next.telegramCopyTransferNumber()).isEqualTo(old.telegramCopyTransferNumber());
        assertThat(queue.snapshot(old.operationId())).isEqualTo(old);
        var current = queue.claim().orElseThrow();
        assertThat(current.operationId()).isEqualTo(next.operationId());
        assertThat(current.mayDispatch()).isTrue();
        var late = queue.claimRecovery(10L, old.operationId()).orElseThrow();
        assertThat(late.mayDispatch()).isFalse();
        tx.executeWithoutResult(status -> queue.finish(late, "SENT", null, 0));
        assertThat(queue.status(next.operationId()).status()).isEqualTo("SENDING");
    }

    @Test void failedRepricingRollsBackReviewAndReplacementTogether() {
        var old = draft("Previous amount");
        var next = new PreparedCommonInvoiceMessage(10L, old.chatCompany(), old.managerClientId(), old.groupId(),
                "Updated amount", old.telegramCopyTransferNumber(), false, false, false,
                "bad-review-invoice:10:2", false, null, 180000L).asReviewedUpdate();
        tx.executeWithoutResult(status -> queue.enqueue(old));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "SENT", null, 0));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(queue.recordStatementReview(10L, old.operationId(), next.operationId(), "test-manager")).isTrue();
            queue.enqueue(next);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Recipient changed");
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(queue.reviewRow(10L, old.operationId()).replacement()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_message_queue WHERE operation_id=?", Integer.class, next.operationId())).isZero();
        assertThat(queue.status(old.operationId()).status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_message_queue WHERE reviewed_at IS NOT NULL", Integer.class)).isZero();
        tx.executeWithoutResult(status ->
                assertThat(queue.recordStatementReview(10L, old.operationId(), next.operationId(), "test-manager")).isTrue());
    }

    @Test void pausedSwitchDoesNotSpendAttemptsOrReleaseFrozenEnvelope() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var claim = queue.claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(claim, "RETRYABLE", "live_disabled", 0));
        assertThat(queue.status(claim.operationId()).attempts()).isZero();
        assertThat(instance().claim().orElseThrow().mayDispatch()).isTrue();
        assertThat(queue.snapshot(claim.operationId())).isEqualTo(draft("original"));
    }

    @Test void aNightWaitingForTheBusinessWindowKeepsEveryRetryForTheRealSend() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        for (int hold = 0; hold < 150; hold++) {
            var waiting = instance().claim().orElseThrow();
            assertThat(waiting.mayDispatch()).isTrue();
            tx.executeWithoutResult(status -> queue.finish(waiting, "RETRYABLE", "outside_business_window", 0, true));
        }
        assertThat(queue.status("invoice-message:test").attempts()).isZero();
        assertThat(queue.status("invoice-message:test").errorCode()).isEqualTo("outside_business_window");

        // The window opens and the first real send fails: that one counts, and the retries remain.
        var send = instance().claim().orElseThrow();
        tx.executeWithoutResult(status -> queue.finish(send, "RETRYABLE", "gateway_unavailable", 0, false));
        assertThat(queue.status("invoice-message:test").attempts()).isEqualTo(1);
        assertThat(queue.snapshot("invoice-message:test")).isEqualTo(draft("original"));
    }

    @Test void repairClaimIsBoundToInvoiceNeverDispatchesAndCannotStealALiveLease() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        String id = draft("original").operationId();
        assertThat(queue.claimRecovery(10L, id)).isEmpty(); // A fresh queued command belongs to the normal worker.
        var first = queue.claim().orElseThrow();
        assertThat(queue.claimRecovery(10L, id)).isEmpty();
        tx.executeWithoutResult(status -> queue.finish(first, "UNKNOWN", "operation_unknown", 300));
        assertThat(queue.claimRecovery(11L, id)).isEmpty();
        var repair = queue.claimRecovery(10L, id).orElseThrow();
        assertThat(repair.mayDispatch()).isFalse();
        assertThat(queue.status(id).attempts()).isEqualTo(1);
        assertThat(queue.claimRecovery(10L, id)).isEmpty();
        assertThat(queue.preparedAt(id)).isNotNull();
        tx.executeWithoutResult(status -> queue.finish(repair, "SENT", null, 300));
        assertThat(queue.claimRecovery(10L, id)).isEmpty();
        assertThat(queue.snapshot(id)).isEqualTo(draft("original"));
    }

    @Test void authorizedSupersessionPreservesHistoryAndFencesOldClaims() {
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var old = queue.claim().orElseThrow();
        // The approved restart takes the invoice/queue lock and fences the old worker.
        jdbc.update("UPDATE common_invoice_message_queue SET state='SUPERSEDED',claim_token=NULL,lease_until=NULL");
        assertThat(queue.unresolved(10L)).isEmpty();
        assertThat(queue.claim()).isEmpty();
        assertThat(queue.claimRecovery(10L, old.operationId())).isEmpty();
        tx.executeWithoutResult(status -> {
            assertThat(queue.owns(old)).isFalse();
            queue.finish(old, "SENT", null, 0);
            queue.retryKnownUnsent(old.operationId());
        });
        assertThat(queue.status(old.operationId()).status()).isEqualTo("SUPERSEDED");
        assertThat(queue.snapshot(old.operationId())).isEqualTo(draft("original"));
        assertThat(queue.deliveryQueueHealth()).isEmpty();
    }

    @Test void healthReportsExpiredClaimsAndExcludesConfirmedHistory() {
        assertThat(queue.deliveryQueueHealth()).isEmpty();
        tx.executeWithoutResult(status -> queue.enqueue(draft("original")));
        var claim = queue.claim().orElseThrow();
        jdbc.update("UPDATE common_invoice_message_queue SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        assertThat(queue.deliveryQueueHealth()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.State.SENDING);
            assertThat(row.jobs()).isEqualTo(1); assertThat(row.expiredLeases()).isEqualTo(1);
        });
        tx.executeWithoutResult(status -> queue.finish(claim, "SENT", null, 0));
        assertThat(queue.deliveryQueueHealth()).isEmpty();
    }
}
