package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.service.ClientChatParticipantClassifier;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WhatsAppUnlinkedEventMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("unlinked_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;
    CompanyService companies;
    ClientChatMessageTrackerService tracker;
    WhatsAppUnlinkedEventStore store;
    WhatsAppGroupWebhookDeduplicator receipts;
    GroupReplyServiceImpl service;
    WhatsAppUnlinkedEventRecovery recovery;
    WhatsAppUnlinkedGroupNotifier notifier;
    TelegramService telegram;
    ClientChatParticipantClassifier participants;
    DriverManagerDataSource ds;
    WhatsAppGroupReplyDTO reply;
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_unlinked_events");
        for(String table:new String[]{"companies","company_status","managers","users","client_chat_messages"}) jdbc.execute("DROP TABLE IF EXISTS "+table);
        // Only the columns the notice reads, with the production types.
        jdbc.execute("CREATE TABLE company_status(company_status_id BIGINT PRIMARY KEY,status_title VARCHAR(255))");
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,fio VARCHAR(255))");
        jdbc.execute("CREATE TABLE managers(manager_id BIGINT PRIMARY KEY,user_id BIGINT,client_id VARCHAR(255))");
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY,company_title VARCHAR(45),company_status BIGINT,company_manager BIGINT,"
                +"company_group_id VARCHAR(1000),company_whatsapp_invite_pending TINYINT(1) NOT NULL DEFAULT 0,company_active BIT(1))");
        jdbc.execute("CREATE TABLE client_chat_messages(id BIGINT AUTO_INCREMENT PRIMARY KEY,platform VARCHAR(24),chat_id VARCHAR(160),"
                +"chat_title VARCHAR(255),message_at DATETIME(6))");
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state");
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_inbound_receipts");
        jdbc.execute("CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE whatsapp_inbound_receipts(receipt_key CHAR(64) PRIMARY KEY,completed_at DATETIME(6) NULL)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_365__unlinked_whatsapp_and_archive_holds.sql")).execute(ds);
        noticeMigration();
        store=new WhatsAppUnlinkedEventStore(jdbc,new ObjectMapper());
        companies=mock(CompanyService.class);tracker=mock(ClientChatMessageTrackerService.class);
        service=new GroupReplyServiceImpl(companies,mock(WhatsAppGroupCompanyLinker.class),mock(PublicationProgressPreferenceService.class),
                mock(WhatsAppInboundReplyOutbox.class),tracker,store);
        var transactions=new DataSourceTransactionManager(ds);
        receipts=new WhatsAppGroupWebhookDeduplicator(jdbc,transactions);
        telegram=mock(TelegramService.class);participants=mock(ClientChatParticipantClassifier.class);
        notifier=new WhatsAppUnlinkedGroupNotifier(store,jdbc,participants,telegram);
        recovery=new WhatsAppUnlinkedEventRecovery(store,service,transactions,notifier);
        reply=new WhatsAppGroupReplyDTO("client","group@g.us","Company","sender@c.us","Client","external-message",1793635200L,false,false,"Нужен ответ");
    }
    void accept() {receipts.execute(reply,()->service.processGroupReply(reply));}
    void accept(WhatsAppGroupReplyDTO message) {receipts.execute(message,()->service.processGroupReply(message));}
    void noticeMigration() {new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_386__whatsapp_unlinked_admin_notice.sql")).execute(ds);}
    WhatsAppGroupReplyDTO mediafix(String messageId,String text) {
        return new WhatsAppGroupReplyDTO("whatsapp_vika","mediafix@g.us","ВИ Mediafix 2 гис","79990000000@c.us","Мария",messageId,1794142749L,false,false,text);
    }
    /** The real case: a staff member posted a copy of a bill into a group no company is linked to. */
    void seedMediafix() {
        jdbc.update("INSERT INTO company_status VALUES(3,'В работе'),(8,'Бан')");
        jdbc.update("INSERT INTO users VALUES(1,'Вика Ц.'),(2,'Анжелика Б.')");
        jdbc.update("INSERT INTO managers VALUES(2,1,'whatsapp_vika'),(3,2,'whatsapp_lika')");
        jdbc.update("INSERT INTO companies VALUES(748,'Авто СПА, Mediafix',3,2,'autospa@g.us',0,b'1'),(154,'Mediafix',8,3,NULL,1,b'1'),"
                +"(155,'Mediafix Archive',3,3,NULL,0,b'0'),(156,'Цайгер Алтай',3,3,NULL,0,b'1')");
        jdbc.update("INSERT INTO client_chat_messages(platform,chat_id,chat_title,message_at) VALUES"
                +"('WHATSAPP','autospa@g.us','Авто СПА старое',NOW(6)-INTERVAL 1 DAY),('WHATSAPP','autospa@g.us','АБ Авто СПА 2 гис',NOW(6))");
        when(participants.classify(eq(ClientChatPlatform.WHATSAPP),eq(ClientChatDirection.INCOMING),eq("mediafix@g.us"),
                eq("79990000000@c.us"),eq("Мария"),isNull())).thenReturn(ClientChatSenderRole.STAFF);
        when(telegram.canSendAdminAlerts()).thenReturn(true);
    }
    String sentNotice() {
        var html=ArgumentCaptor.forClass(String.class);
        verify(telegram,atLeastOnce()).sendHtmlAlertToAdmins(html.capture());
        return html.getValue();
    }
    long noticeQueue() {return store.noticeQueueHealth().getFirst().jobs();}
    long notified() {return jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_unlinked_events WHERE admin_notified_at IS NOT NULL",Long.class);}
    void bind() {
        var company=new Company();company.setGroupId(reply.getGroupId());
        when(companies.findByGroupId(reply.getGroupId())).thenReturn(java.util.Optional.of(company));
    }
    @Test void ackKeepsUnlinkedPayloadAndBindingRecoveryAppliesOriginalMessageExactlyOnce() {
        accept();accept();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_unlinked_events",Integer.class)).isEqualTo(1);
        assertThat(store.unresolvedGroups().getFirst().get("events")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM whatsapp_inbound_receipts",Boolean.class)).isTrue();
        verifyNoInteractions(tracker);
        bind();recovery.recover();recovery.recover();accept();
        verify(tracker,times(1)).track(argThat(command->command.externalMessageId().equals(reply.getMessageId())
                && command.messageText().equals(reply.getMessage()) && command.chatId().equals(reply.getGroupId())),any());
        assertThat(store.unresolvedGroups()).isEmpty();
    }
    @Test void businessFailureRollsBackCompletionAndSavedPayloadRemainsReplayable() {
        accept();bind();
        doThrow(new IllegalStateException("tracker unavailable")).when(tracker).track(any(),any());
        recovery.recover();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM whatsapp_unlinked_events",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.messageId')) FROM whatsapp_unlinked_events",String.class)).isEqualTo(reply.getMessageId());
        doNothing().when(tracker).track(any(),any());
        jdbc.update("UPDATE whatsapp_unlinked_events SET next_attempt_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND)");
        recovery.recover();
        assertThat(store.unresolvedGroups()).isEmpty();
    }

    @Test void theReleaseCountsMessagesSavedBeforeItAsAlreadyAnnounced() {
        jdbc.execute("ALTER TABLE whatsapp_unlinked_events DROP COLUMN admin_notified_at");
        accept();
        noticeMigration();
        assertThat(jdbc.queryForObject("SELECT admin_notified_at=created_at FROM whatsapp_unlinked_events",Boolean.class)).isTrue();
        assertThat(store.noticeDueGroups(10)).isEmpty();
        assertThat(noticeQueue()).isZero();
    }
    @Test void aNoticeNamesTheGroupAuthorTextAndTheCompanyLinkedElsewhereAndIsMarkedOnlyAfterTelegramAcceptedIt() {
        seedMediafix();
        accept(mediafix("bill","Авто СПА, Mediafix - Mediafix,Полярная\n\nЗдравствуйте, ваш заказ выполнен. К оплате: 2400 руб."));
        assertThat(noticeQueue()).isEqualTo(1);
        when(telegram.sendHtmlAlertToAdmins(anyString())).thenReturn(false);
        recovery.recover();
        // Not accepted: the notice stays pending and the delayed-queue alert can see it.
        assertThat(notified()).isZero();
        assertThat(noticeQueue()).isEqualTo(1);

        when(telegram.sendHtmlAlertToAdmins(anyString())).thenReturn(true);
        recovery.recover();
        String html=sentNotice();
        assertThat(html).contains("<b>Группа:</b> ВИ Mediafix 2 гис")
                .contains("<b>Аккаунт:</b> whatsapp_vika (Вика Ц.)")
                .contains("<b>Написал(а):</b> Мария — сотрудник, 08.11 20:59")
                .contains("К оплате: 2400 руб.")
                .contains("компания не найдена ни по этой группе")
                // The same title first; inactive companies are left out.
                .contains("• Mediafix — Бан, Анжелика Б. В статусе «Бан» группа к ней не привязывается.\n"
                        +"• Авто СПА, Mediafix — В работе, Вика Ц. Уже привязана к другой группе «АБ Авто СПА 2 гис».")
                .doesNotContain("Archive").doesNotContain("Цайгер");
        assertThat(notified()).isEqualTo(1);
        assertThat(noticeQueue()).isZero();

        recovery.recover();
        verify(telegram,times(2)).sendHtmlAlertToAdmins(anyString());
        assertThat(notifier.previews()).singleElement().satisfies(preview->{
            assertThat(preview.groupId()).isEqualTo("mediafix@g.us");
            assertThat(preview.html()).isEqualTo(html);
        });
    }
    @Test void messagesWithinTheGroupsPauseWaitAndAreSummedUpInItsNextNotice() {
        seedMediafix();
        when(telegram.sendHtmlAlertToAdmins(anyString())).thenReturn(true);
        accept(mediafix("first","Первое"));
        recovery.recover();
        accept(mediafix("second","Второе"));
        accept(mediafix("third","Третье"));
        recovery.recover();
        verify(telegram,times(1)).sendHtmlAlertToAdmins(anyString());
        // Waiting out the pause is not a delayed notice.
        assertThat(store.noticeDueGroups(10)).isEmpty();
        assertThat(noticeQueue()).isZero();

        // The notice went out 5 hours ago and the two messages came 2 hours after it: due for an hour now.
        jdbc.update("UPDATE whatsapp_unlinked_events SET admin_notified_at=admin_notified_at-INTERVAL 5 HOUR WHERE admin_notified_at IS NOT NULL");
        jdbc.update("UPDATE whatsapp_unlinked_events SET created_at=created_at-INTERVAL 3 HOUR WHERE admin_notified_at IS NULL");
        var health=store.noticeQueueHealth().getFirst();
        assertThat(health.jobs()).isEqualTo(2);
        assertThat(health.oldestSeconds()).isBetween(3500d,3700d);
        recovery.recover();
        assertThat(sentNotice()).contains("<b>Текст:</b> Третье").contains("<b>Новых сообщений:</b> 2, выше последнее");
        assertThat(notified()).isEqualTo(3);
        assertThat(noticeQueue()).isZero();
    }
    @Test void aGroupLinkedBeforeTheNoticeIsNeverAnnounced() {
        seedMediafix();
        when(telegram.sendHtmlAlertToAdmins(anyString())).thenReturn(true);
        accept();bind();
        recovery.recover();
        assertThat(store.unresolvedGroups()).isEmpty();
        verify(telegram,never()).sendHtmlAlertToAdmins(anyString());
        assertThat(noticeQueue()).isZero();
    }
    @Test void withSendingOffNothingIsAttemptedAndTheBacklogStaysVisible() {
        accept();
        recovery.recover();
        verify(telegram,never()).sendHtmlAlertToAdmins(anyString());
        assertThat(noticeQueue()).isEqualTo(1);
        assertThat(notifier.previews()).singleElement().satisfies(preview->assertThat(preview.html())
                .contains("<b>Группа:</b> Company").contains("Похожих компаний</b> по названию группы нет"));
    }
}
