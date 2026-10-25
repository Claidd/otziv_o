package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.security.credentials.*;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ReviewArchiveNotificationsMySqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
        "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("review_archives").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ReviewArchiveNotifications queue;
    ClientMessageDelivery delivery;
    TelegramNotifications telegram;
    OutboundMessagePolicy settings;
    final String id="review-archive:30:client";

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS review_archive_notifications");
        jdbc.execute("DROP TABLE IF EXISTS review_silence_archives");
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_orders");
        jdbc.execute("CREATE TABLE common_invoice_orders(id BIGINT PRIMARY KEY)");
        for(var statement:Files.readString(Path.of("src/main/resources/db/migration/V1_10_342__review_silence_archive_notifications.sql")).split(";"))
            if(!statement.isBlank())jdbc.execute(statement);
        var props=new CredentialEncryptionProperties();props.setActiveKeyId("test");
        props.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        delivery=mock(ClientMessageDelivery.class);telegram=mock(TelegramNotifications.class);settings=mock(OutboundMessagePolicy.class);
        when(settings.clientMessagesEnabled()).thenReturn(true);
        var proxy=new ProxyFactory(new ReviewArchiveNotifications(jdbc,new ObjectMapper(),new CredentialCipher(props),delivery,telegram,
                new ClientMessageTransactionRunner(manager),settings));
        proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        queue=(ReviewArchiveNotifications)proxy.getProxy();
    }
    ReviewArchiveNotifications.Envelope draft(String text) {
        return new ReviewArchiveNotifications.Envelope(new ClientMessageDelivery.Target(20L,"company",null,null,null),"client","group",text,null);
    }
    void enqueue(){tx.executeWithoutResult(s->queue.enqueue(30,"client",draft("archived")));}
    void due(){jdbc.update("UPDATE review_archive_notifications SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE)");}
    String state(){return jdbc.queryForObject("SELECT state FROM review_archive_notifications WHERE operation_id=?",String.class,id);}

    @Test void lateFailureRollsBackArchiveEvidenceAndNotificationTogether() {
        tx.executeWithoutResult(status->{
            queue.recordArchive(30,77,"cycle",7,Map.of("paid",0),null,"deferred");
            queue.enqueue(30,"client",draft("archived"));status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_silence_archives",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_archive_notifications",Integer.class)).isZero();
        verifyNoInteractions(delivery,telegram);
    }
    @Test void repeatedIntentCannotChangeRecipientOrText() {
        enqueue();enqueue();
        assertThatThrownBy(()->tx.executeWithoutResult(s->queue.enqueue(30,"client",draft("changed")))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_archive_notifications",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT envelope FROM review_archive_notifications",String.class)).startsWith("enc:").doesNotContain("archived");
    }
    @Test void uncertainSendOnlyReadsReceiptOnNextAttempt() {
        enqueue();when(delivery.deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id)))
            .thenReturn(ClientMessageSendResult.failed("timeout","unknown"));
        queue.deliver(id);assertThat(state()).isEqualTo("UNKNOWN");due();
        when(delivery.recoverRecordedOutcomeFromChat(eq(id),any())).thenReturn(ClientMessageSendResult.sent("whatsapp","receipt"));
        queue.deliver(id);assertThat(state()).isEqualTo("SENT");due();queue.deliver(id);
        verify(delivery,times(1)).deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id));
        verify(delivery,times(1)).recoverRecordedOutcomeFromChat(eq(id),any());
    }
    @Test void concurrentWorkersOnlyDispatchOnce() throws Exception {
        enqueue();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(delivery.deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id))).thenAnswer(i->{
            entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return ClientMessageSendResult.sent("whatsapp","receipt");
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->queue.deliver(id));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            pool.submit(()->queue.deliver(id)).get(10,TimeUnit.SECONDS);release.countDown();first.get(10,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(state()).isEqualTo("SENT");
        verify(delivery,times(1)).deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id));
    }
    @Test void unavailableReceiptNeverReopensDispatchPermission() {
        enqueue();when(delivery.deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id)))
            .thenReturn(ClientMessageSendResult.failed("timeout","unknown"));
        queue.deliver(id);due();
        when(delivery.recoverRecordedOutcomeFromChat(eq(id),any())).thenReturn(ClientMessageSendResult.failed("gateway_not_ready","offline"));
        queue.deliver(id);assertThat(state()).isEqualTo("UNKNOWN");due();queue.deliver(id);
        verify(delivery,times(1)).deliverWithOperationId(any(),anyString(),anyString(),anyString(),isNull(),eq(id));
        verify(delivery,times(2)).recoverRecordedOutcomeFromChat(eq(id),any());
    }
    @Test void telegramUnknownIsNotResentAndDisabledLiveDoesNotSpendAttempt() {
        tx.executeWithoutResult(s->queue.enqueue(30,"staff:10",new ReviewArchiveNotifications.Envelope(null,null,null,"archived",10L)));
        when(settings.clientMessagesEnabled()).thenReturn(false);
        queue.drain();verifyNoInteractions(telegram);
        assertThat(jdbc.queryForObject("SELECT attempts FROM review_archive_notifications",Integer.class)).isZero();
        when(settings.clientMessagesEnabled()).thenReturn(true);
        queue.drain();due();queue.drain();
        verify(telegram,times(1)).sendTextOnce(10L,"archived");
        assertThat(jdbc.queryForObject("SELECT state FROM review_archive_notifications",String.class)).isEqualTo("UNKNOWN");
    }

    @Test void historicalDateCorrectionPreservesDeliveryAndOnlyNotifiesStaffOnce() {
        jdbc.execute("DROP TABLE IF EXISTS client_reminder_restart_audit");
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state");
        jdbc.execute("CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,company_id BIGINT,scenario VARCHAR(80),state_status VARCHAR(20),next_attempt_at DATETIME(6),sent_count INT,delivery_status VARCHAR(20),delivery_envelope TEXT,locked_until DATETIME(6))");
        try {
            String migration=Files.readString(Path.of("src/main/resources/db/migration/V1_10_341__authorized_reminder_restart.sql"));
            jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE")));
        } catch(java.io.IOException failure) { throw new AssertionError(failure); }
        jdbc.update("INSERT INTO scheduled_client_message_state(state_id,company_id) VALUES(30,20)");
        jdbc.update("INSERT INTO scheduled_client_message_state(state_id,company_id,scenario,state_status,next_attempt_at,sent_count) VALUES(31,20,'ARCHIVE_REORDER_OFFER','ACTIVE',TIMESTAMPADD(MONTH,3,NOW()),1)");
        tx.executeWithoutResult(s->{
            queue.recordArchive(30,77,"cycle",7,Map.of(),java.time.LocalDateTime.now().minusYears(1),"old date");
            queue.enqueue(30,"staff:10",new ReviewArchiveNotifications.Envelope(null,null,null,"old notice",10L));
            queue.enqueue(30,"client",draft("archived"));
        });
        jdbc.update("UPDATE review_archive_notifications SET state='SENT',attempts=1");
        queue.correctHistoricalOfferDates();queue.correctHistoricalOfferDates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_archive_notifications WHERE state='READY' AND channel='STAFF'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_archive_notifications WHERE state='SENT'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sent_count FROM scheduled_client_message_state WHERE state_id=31",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT offer_status FROM review_silence_archives",String.class)).contains("уже отправлено","повтор сейчас не отправляется");
        verifyNoInteractions(delivery,telegram);
    }
}
