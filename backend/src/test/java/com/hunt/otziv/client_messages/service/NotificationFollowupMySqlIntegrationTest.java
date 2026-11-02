package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.api.NotificationFollowup;
import com.hunt.otziv.client_messages.api.NotificationFollowup.*;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.repository.NotificationFollowupRepository;
import com.hunt.otziv.payments.repository.PaymentSuccessNotificationRetryClaimRepository;
import com.hunt.otziv.payments.service.PaymentSuccessNotificationRetryClaimService;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.service.WhatsAppDeliveryRouteRecovery;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Actual migration, durable claims, encrypted snapshots and independent connections; no real messenger. */
@Testcontainers
class NotificationFollowupMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("notification_followup").withUsername("root").withPassword("root");
    static JdbcTemplate jdbc;
    static ClientMessageTransactionRunner transactions;
    static NotificationFollowupRepository records;
    ClientMessageDelivery delivery;
    WhatsAppBusinessOperations operations;
    WhatsAppDeliveryRouteRecovery routes;
    NotificationFollowupService service;
    final String original="payment-success:7";
    final String invite="https://chat.whatsapp.com/LocalFixtureInvite1";
    final String group="123456789@g.us";
    Request request;

    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(10);
        transactions=new ClientMessageTransactionRunner(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE scheduled_client_message_dispatch_guard(guard_id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO scheduled_client_message_dispatch_guard VALUES(1)");
        jdbc.execute("""
                CREATE TABLE payment_links(id BIGINT PRIMARY KEY,status VARCHAR(32),payment_success_notified_at DATETIME(6),
                  payment_success_notification_error VARCHAR(512),payment_success_notification_retry_eligible TINYINT NOT NULL,
                  row_version BIGINT NOT NULL DEFAULT 0,updated_at DATETIME(6)) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE payment_success_notification_retry_claims(payment_link_id BIGINT PRIMARY KEY,
                  processing_token VARCHAR(64),processing_owner VARCHAR(128),processing_started_at DATETIME(6),
                  processing_lease_until DATETIME(6)) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE archive_payment_links LIKE payment_links");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_359__authorized_notification_followups.sql")).execute(ds);
        var keys=new CredentialEncryptionProperties();keys.setRequired(true);keys.setActiveKeyId("fixture");
        keys.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        records=new NotificationFollowupRepository(jdbc,new CredentialCipher(keys));
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM client_notification_followups");jdbc.update("DELETE FROM payment_success_notification_retry_claims");jdbc.update("DELETE FROM payment_links");
        delivery=mock(ClientMessageDelivery.class);operations=mock(WhatsAppBusinessOperations.class);routes=mock(WhatsAppDeliveryRouteRecovery.class);
        when(delivery.recoverRecordedOutcomeFromChat(anyString(),nullable(LocalDateTime.class)))
                .thenReturn(ClientMessageSendResult.failed("whatsapp_history_checked_no_receipt","no receipt"));
        when(delivery.hasSettledUncertainOperation(original)).thenReturn(true);
        when(operations.findFrozen(original)).thenReturn(Optional.of(new WhatsAppBusinessOperations.FrozenMessage(original,"client","send-group",group,"original notification")));
        when(routes.reconfirmGroup(1L,invite,"client")).thenReturn(group);
        when(delivery.deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),anyString()))
                .thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return ClientMessageSendResult.sent("WhatsApp","followup-proof");});
        request=new Request(Kind.PAYMENT_SUCCESS,7L,NotificationFollowup.fingerprint("confirmed-payment-scope"),original,
                LocalDateTime.now().minusDays(2),new ClientMessageDelivery.Target(1L,"Local Fixture",invite,null,null),"client",
                "This is a repeated confirmation; do not pay again.","original immutable source snapshot");
        service=newService();
    }
    private NotificationFollowupService newService(){return new NotificationFollowupService(records,transactions,delivery,operations,routes);}
    private void prepare(){assertThat(service.recover(request,()->true).delivery().sent()).isFalse();}
    private void age(){jdbc.update("UPDATE client_notification_followups SET created_at=TIMESTAMPADD(MINUTE,-16,CURRENT_TIMESTAMP(6))");}
    private Outcome send(){prepare();age();return service.recover(request,()->true);}
    private void neverDispatched(){verify(delivery,never()).deliverWithOperationId(any(),any(),any(),any(),any(),any());}

    @Test void observesUnknownForFifteenMinutesInsteadOfUsingAnOldPaymentDate() {
        prepare();service.recover(request,()->true);
        assertThat(records.find(original).orElseThrow().state()).isEqualTo("PENDING");neverDispatched();
    }
    @Test void aLateOriginalReceiptCancelsAnUnstartedFollowup() {
        prepare();age();
        when(delivery.recoverRecordedOutcomeFromChat(eq(original),any())).thenReturn(ClientMessageSendResult.sent("WhatsApp","original-proof"));
        var result=service.recover(request,()->true);
        assertThat(result.followup()).isFalse();assertThat(result.delivery().messageId()).isEqualTo("original-proof");
        assertThat(records.find(original).orElseThrow().state()).isEqualTo("CANCELED");neverDispatched();
    }
    @Test void sendsOneFollowupAndReusesItsDurableReceiptAfterRestart() {
        var result=send();assertThat(result.delivery().sent()).isTrue();assertThat(result.operationId()).isNotEqualTo(original);
        assertThat(newService().recover(request,()->true)).isEqualTo(result);
        verify(delivery,times(1)).deliverWithOperationId(any(),eq("client"),eq(group),eq(request.message()),isNull(),eq(result.operationId()));
        String stored=jdbc.queryForObject("SELECT payload_ciphertext FROM client_notification_followups",String.class);
        assertThat(stored).startsWith("enc:v1:fixture:").doesNotContain(request.message(),invite,request.sourceSnapshot());
        assertThat(records.find(original).orElseThrow().payload()).contains(request.sourceSnapshot());
    }
    @Test void unknownFollowupNeverAllocatesAThirdSendAndCanAcceptALateReceipt() {
        when(delivery.deliverWithOperationId(any(),any(),any(),any(),any(),any())).thenReturn(ClientMessageOperationFence.unknown());
        var result=send();assertThat(result.delivery().sent()).isFalse();
        for(int i=0;i<4;i++) assertThat(newService().recover(request,()->true).delivery().sent()).isFalse();
        assertThat(records.find(original).orElseThrow().state()).isEqualTo("UNKNOWN");
        when(delivery.recoverRecordedOutcomeFromChat(eq(result.operationId()),isNull())).thenReturn(ClientMessageSendResult.sent("WhatsApp","late-proof"));
        assertThat(newService().recover(request,()->true).delivery().messageId()).isEqualTo("late-proof");
        verify(delivery,times(1)).deliverWithOperationId(any(),any(),any(),any(),any(),any());
    }
    @Test void twoHandlersCannotDispatchTheSameFollowupConcurrently() throws Exception {
        prepare();age();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(delivery.deliverWithOperationId(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return ClientMessageSendResult.sent("WhatsApp","concurrent-proof");});
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->service.recover(request,()->true));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->newService().recover(request,()->true));
            assertThat(second.get(10,TimeUnit.SECONDS).delivery().sent()).isFalse();release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).delivery().sent()).isTrue();
        } finally {release.countDown();}
        verify(delivery,times(1)).deliverWithOperationId(any(),any(),any(),any(),any(),any());
    }
    @Test void aCrashAfterDurableClaimCannotReplayTheFollowup() {
        prepare();age();assertThat(transactions.callInPreparationTransaction(()->records.claim(original,900,60))).isTrue();
        jdbc.update("UPDATE client_notification_followups SET started_at=TIMESTAMPADD(MINUTE,-16,CURRENT_TIMESTAMP(6))");
        assertThat(newService().recover(request,()->true).delivery().sent()).isFalse();
        assertThat(records.find(original).orElseThrow().state()).isEqualTo("UNKNOWN");neverDispatched();
    }
    @Test void knownAdmissionFailureRetriesTheSameIdAndFrozenPayload() {
        when(delivery.deliverWithOperationId(any(),any(),any(),any(),any(),any()))
                .thenReturn(ClientMessageSendResult.failed("gateway_not_ready","no dispatch"),ClientMessageSendResult.sent("WhatsApp","proof"));
        var initial=send();assertThat(records.find(original).orElseThrow().state()).isEqualTo("PENDING");
        jdbc.update("UPDATE client_notification_followups SET started_at=TIMESTAMPADD(MINUTE,-2,CURRENT_TIMESTAMP(6))");
        var edited=new Request(request.kind(),request.sourceId(),request.sourceVersion(),original,request.preparedAt(),request.target(),request.clientId(),"changed template",request.sourceSnapshot());
        var result=service.recover(edited,()->true);assertThat(result.delivery().sent()).isTrue();
        assertThat(result.operationId()).isEqualTo(initial.operationId());
        verify(delivery,times(2)).deliverWithOperationId(any(),eq("client"),eq(group),eq(request.message()),isNull(),eq(result.operationId()));
    }
    @Test void revocationAfterClaimProvesNoDispatchAndKeepsTheUnsentIntent() {
        prepare();age();var checks=new AtomicInteger();
        assertThat(service.recover(request,()->checks.incrementAndGet()<4).delivery().sent()).isFalse();
        assertThat(records.find(original).orElseThrow().state()).isEqualTo("PENDING");neverDispatched();
    }
    @Test void aChangedRouteCannotRedirectTheSavedFollowup() {
        prepare();age();when(routes.reconfirmGroup(1L,invite,"client")).thenReturn("987654321@g.us");
        assertThat(service.recover(request,()->true).delivery().sent()).isFalse();neverDispatched();
    }
    @Test void bareProviderSuccessWithoutAReceiptStaysUnknown() {
        when(delivery.deliverWithOperationId(any(),any(),any(),any(),any(),any())).thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        assertThat(send().delivery().sent()).isFalse();assertThat(records.find(original).orElseThrow().state()).isEqualTo("UNKNOWN");
    }
    @Test void runningOriginalOrDisabledPolicyNeverCreatesAFollowup() {
        when(delivery.hasSettledUncertainOperation(original)).thenReturn(false);
        assertThat(service.recover(request,()->true).delivery().sent()).isFalse();assertThat(records.find(original)).isEmpty();
        ReflectionTestUtils.setField(service,"enabled",false);
        assertThat(service.recover(request,()->true).delivery().sent()).isFalse();neverDispatched();
    }
    @Test void recursiveFollowupIdsAreRejected() {
        var recursive=new Request(request.kind(),7L,request.sourceVersion(),"notification-followup-v1:"+request.sourceVersion(),
                request.preparedAt(),request.target(),request.clientId(),request.message(),request.sourceSnapshot());
        service.recover(recursive,()->true);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_notification_followups",Integer.class)).isZero();neverDispatched();
    }
    @Test void paymentAcknowledgementAndRecoveryAuditCommitTogetherAndRollbackTogether() {
        var result=send();jdbc.update("INSERT INTO payment_links(id,status,payment_success_notification_retry_eligible) VALUES(7,'CONFIRMED',1)");
        var claimsRepo=new PaymentSuccessNotificationRetryClaimRepository(new NamedParameterJdbcTemplate(jdbc));
        var failing=mock(NotificationFollowup.class);
        doAnswer(call->{service.markApplied(result);throw new IllegalStateException("simulated finalization crash");}).when(failing).markApplied(result);
        var claims=new PaymentSuccessNotificationRetryClaimService(claimsRepo,Duration.ofMinutes(2),failing);
        var claim=transactions.callInNewTransaction(()->claims.tryClaim(7L)).orElseThrow();
        assertThatThrownBy(()->transactions.callInNewTransaction(()->claims.markSucceededWithRecovery(claim,result))).hasMessageContaining("simulated");
        assertThat(jdbc.queryForObject("SELECT owner_applied_at FROM client_notification_followups",java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT payment_success_notified_at FROM payment_links WHERE id=7",java.sql.Timestamp.class)).isNull();
        var working=new PaymentSuccessNotificationRetryClaimService(claimsRepo,Duration.ofMinutes(2),service);
        assertThat(transactions.callInNewTransaction(()->working.markSucceededWithRecovery(claim,result))).isTrue();
        assertThat(jdbc.queryForObject("SELECT owner_applied_at IS NOT NULL FROM client_notification_followups",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT payment_success_notification_recovery_operation_id FROM payment_links WHERE id=7",String.class)).isEqualTo(result.operationId());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_links WHERE id=7",String.class)).isEqualTo("CONFIRMED");
        verify(delivery,times(1)).deliverWithOperationId(any(),any(),any(),any(),any(),any());
    }
}
