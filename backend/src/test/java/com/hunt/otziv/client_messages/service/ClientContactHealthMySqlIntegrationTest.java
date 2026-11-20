package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import com.hunt.otziv.t_telegrambot.api.TelegramChatAccess;
import com.hunt.otziv.whatsapp.api.WhatsAppChatAccess;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
class ClientContactHealthMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("contact_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; ClientContactHealthService health; TelegramChatAccess telegram; WhatsAppChatAccess whatsapp;
    com.hunt.otziv.maxbot.api.MaxChatAccess max;
    @BeforeEach void setup() {
        var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        for (String table : new String[]{"client_contact_health","companies","managers","company_status","orders","order_statuses","next_order_requests","scheduled_client_message_state"})
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_406__client_contact_health.sql")).execute(data);
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY,company_title VARCHAR(120),company_status BIGINT,company_manager BIGINT,company_url_chat VARCHAR(500),company_group_id VARCHAR(120),company_telegram_group_chat_id BIGINT,company_max_group_chat_id BIGINT,company_active BOOLEAN)");
        jdbc.execute("CREATE TABLE managers(manager_id BIGINT PRIMARY KEY,client_id VARCHAR(120))");
        jdbc.execute("CREATE TABLE company_status(company_status_id BIGINT PRIMARY KEY,status_title VARCHAR(80))");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_company BIGINT,order_status BIGINT)");
        jdbc.execute("CREATE TABLE order_statuses(order_status_id BIGINT PRIMARY KEY,order_status_title VARCHAR(80))");
        jdbc.execute("CREATE TABLE next_order_requests(id BIGINT PRIMARY KEY,company_id BIGINT,request_status VARCHAR(40))");
        jdbc.execute("CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,company_id BIGINT,state_status VARCHAR(32),consecutive_failures INT,delivery_status VARCHAR(32))");
        jdbc.update("INSERT INTO company_status VALUES(1,'На стопе'),(2,'В работе'),(3,'Бан')");
        jdbc.update("INSERT INTO order_statuses VALUES(1,'Архив'),(2,'На проверке'),(3,'Оплачено'),(4,'Бан')");
        jdbc.update("INSERT INTO managers VALUES(13,'whatsapp_vika')");
        jdbc.update("INSERT INTO companies VALUES(2116,'Pervoцвет',1,13,'https://t.me/example',NULL,-1001,NULL,TRUE)");
        jdbc.update("INSERT INTO orders VALUES(19250,2116,1)");
        var settings=mock(BackgroundAutomationSettings.class);
        when(settings.getStringFresh(anyString(),anyString())).thenAnswer(call -> call.getArgument(1));
        telegram=mock(TelegramChatAccess.class);whatsapp=mock(WhatsAppChatAccess.class);
        max=mock(com.hunt.otziv.maxbot.api.MaxChatAccess.class);
        health=new ClientContactHealthService(jdbc,new ArchiveCompanyMessageCandidateRepository(new NamedParameterJdbcTemplate(jdbc)),settings,telegram,whatsapp,max);
    }
    ClientMessageDelivery.Target target(){return new ClientMessageDelivery.Target(2116L,"Pervoцвет","https://t.me/example",-1001L,null);}
    void unavailable(){when(telegram.check(-1001)).thenReturn(new TelegramChatAccess.Result("UNAVAILABLE","chat_not_found"));health.recheck(2116L);}
    @Test void closedMaxChatUsesTheSameIdleExclusionAndActiveOrderEscalation() {
        jdbc.update("UPDATE companies SET company_url_chat='https://max.ru/join/example',company_max_group_chat_id=-7445");
        when(max.check(-7445)).thenReturn(new com.hunt.otziv.maxbot.api.MaxChatAccess.Result("UNAVAILABLE","max_chat_closed"));
        var result=health.recheck(2116L);
        assertThat(result.reason()).isEqualTo("max_chat_closed");
        assertThat(result.excluded()).isTrue();
        assertThat(health.managerIssues(13L)).isEmpty();
        jdbc.update("UPDATE orders SET order_status=2");
        assertThat(health.excludedCompanies()).isEmpty();
        assertThat(health.managerIssues(13L)).extracting(com.hunt.otziv.client_messages.api.ClientContactHealth.Contact::reason).containsExactly("max_chat_closed");
        when(max.check(-7445)).thenReturn(new com.hunt.otziv.maxbot.api.MaxChatAccess.Result("UNKNOWN","access_unconfirmed"));
        assertThat(health.recheck(2116L).reason()).isEqualTo("max_chat_closed");
        when(max.check(-7445)).thenReturn(new com.hunt.otziv.maxbot.api.MaxChatAccess.Result("AVAILABLE","member"));
        health.recheck(2116L);
        assertThat(health.managerIssues(13L)).isEmpty();
        verifyNoInteractions(telegram,whatsapp);
    }
    @Test void onlyStoppedWithoutWorkIsExcludedAndLiveWorkMovesItToManagerImmediately() {
        unavailable();
        assertThat(health.adminOnly(2116L)).isTrue();
        assertThat(health.holdNewMessage(target(),"whatsapp_vika",null)).isTrue();
        assertThat(health.requiresManagerAttention(2116L)).isFalse();
        assertThat(health.excludedCompanies()).hasSize(1);
        assertThat(health.managerIssues(13L)).isEmpty();
        assertThat(health.routing(13L).excluded()).containsExactly(2116L);
        jdbc.update("UPDATE orders SET order_status=2");
        assertThat(health.adminOnly(2116L)).isFalse();
        assertThat(health.holdNewMessage(target(),"whatsapp_vika",null)).isFalse();
        assertThat(health.excludedCompanies()).isEmpty();
        assertThat(health.managerIssues(13L)).hasSize(1);
        assertThat(health.routing(13L).active()).containsExactly(2116L);
        jdbc.update("UPDATE orders SET order_status=3");
        assertThat(health.adminOnly(2116L)).isTrue();
        jdbc.update("INSERT INTO next_order_requests VALUES(1,2116,'PENDING')");
        assertThat(health.adminOnly(2116L)).isFalse();
        assertThat(health.routing(13L).active()).containsExactly(2116L);
        jdbc.update("UPDATE next_order_requests SET request_status='COMPLETED'");
        jdbc.update("UPDATE companies SET company_status=2");
        assertThat(health.adminOnly(2116L)).isFalse();
        assertThat(health.managerIssues(13L)).hasSize(1);
        assertThat(health.routing(13L).active()).containsExactly(2116L);
        assertThat(health.excludedCompanies()).isEmpty();
        assertThat(health.requiresManagerAttention(2116L)).isTrue();
    }
    @Test void timeoutDoesNotCreateExclusionOrClearConfirmedExclusion() {
        when(telegram.check(-1001)).thenReturn(new TelegramChatAccess.Result("UNKNOWN","timeout"));
        health.recheck(2116L); assertThat(health.excludedCompanies()).isEmpty();
        unavailable();
        when(telegram.check(-1001)).thenReturn(new TelegramChatAccess.Result("UNKNOWN","timeout"));
        var result=health.recheck(2116L);
        assertThat(result.excluded()).isTrue();assertThat(result.checkStatus()).isEqualTo("UNKNOWN");
        when(telegram.check(-1001)).thenReturn(new TelegramChatAccess.Result("AVAILABLE","member"));
        assertThat(health.recheck(2116L).excluded()).isFalse();
        assertThat(health.adminOnly(2116L)).isFalse();
    }
    @Test void changedContactNeedsPositiveCheckAndOldSnapshotCannotReleaseHold() {
        unavailable();jdbc.update("UPDATE companies SET company_url_chat='https://t.me/new',company_telegram_group_chat_id=-1002");
        assertThat(health.adminOnly(2116L)).isTrue();
        when(telegram.check(-1002)).thenReturn(new TelegramChatAccess.Result("UNKNOWN","timeout"));
        assertThat(health.recheck(2116L).excluded()).isTrue();
        assertThat(health.holdNewMessage(target(),"whatsapp_vika",null)).isFalse();
        when(telegram.check(-1002)).thenReturn(new TelegramChatAccess.Result("AVAILABLE","member"));
        assertThat(health.recheck(2116L).excluded()).isFalse();
    }
    @Test void oldSlowProbeCannotOverwriteNewerConfirmedAccess() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(telegram.check(-1001)).thenAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return new TelegramChatAccess.Result("UNAVAILABLE","chat_not_found");});
        try(var pool=Executors.newSingleThreadExecutor()) {
            var slow=pool.submit(()->health.recheck(2116L));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            doReturn(new TelegramChatAccess.Result("AVAILABLE","member")).when(telegram).check(-1001);
            health.recheck(2116L);release.countDown();slow.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(health.adminOnly(2116L)).isFalse();
    }
    @Test void oldPositiveResponseCannotReleaseExclusionAfterLinkChangedDuringProbe() {
        unavailable();
        doAnswer(call->{
            jdbc.update("UPDATE companies SET company_url_chat='https://t.me/new',company_telegram_group_chat_id=-1002");
            return new TelegramChatAccess.Result("AVAILABLE","member");
        }).when(telegram).check(-1001);
        var result=health.recheck(2116L);
        assertThat(result.excluded()).isTrue();assertThat(result.reason()).isEqualTo("route_changed_unverified");
        assertThat(result.checkStatus()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT unavailable_reason FROM client_contact_health WHERE company_id=2116",String.class)).isEqualTo("chat_not_found");
    }
    @Test void caseOnlyInviteChangeAlsoRejectsTheOldPositiveProbe() {
        unavailable();
        doAnswer(call->{
            jdbc.update("UPDATE companies SET company_url_chat='https://t.me/Example'");
            return new TelegramChatAccess.Result("AVAILABLE","member");
        }).when(telegram).check(-1001);
        var result=health.recheck(2116L);
        assertThat(result.excluded()).isTrue();
        assertThat(result.reason()).isEqualTo("route_changed_unverified");
        assertThat(jdbc.queryForObject("SELECT unavailable_reason FROM client_contact_health WHERE company_id=2116",String.class)).isEqualTo("chat_not_found");
    }
    @Test void absentBindingIsVisibleWithoutNetworkButBanAndInactiveCompanyAreNotListed() {
        jdbc.update("UPDATE companies SET company_telegram_group_chat_id=NULL");
        assertThat(health.excludedCompanies()).hasSize(1);verifyNoInteractions(telegram,whatsapp);
        jdbc.update("UPDATE companies SET company_status=3");assertThat(health.excludedCompanies()).isEmpty();
        jdbc.update("UPDATE companies SET company_status=1,company_active=FALSE");assertThat(health.excludedCompanies()).isEmpty();
    }
    @Test void periodicCheckDiscoversFailedContactWithoutChangingItsUnknownOperation() {
        jdbc.update("INSERT INTO scheduled_client_message_state VALUES(2254395,2116,'ACTIVE',1,'UNKNOWN')");
        when(telegram.check(-1001)).thenReturn(new TelegramChatAccess.Result("UNAVAILABLE","chat_not_found"));
        health.checkDueContacts();
        assertThat(health.excludedCompanies()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM scheduled_client_message_state WHERE state_id=2254395",String.class)).isEqualTo("UNKNOWN");
        health.checkDueContacts();verify(telegram,times(1)).check(-1001);
    }
    @Test void unknownAccessIsRetriedAfterFifteenMinutesWithoutResendingTheHistoricalOperation() {
        jdbc.update("UPDATE companies SET company_url_chat='https://max.ru/join/example',company_max_group_chat_id=-7445");
        jdbc.update("INSERT INTO scheduled_client_message_state VALUES(1238696,2116,'ACTIVE',0,'UNKNOWN')");
        when(max.check(-7445)).thenReturn(new com.hunt.otziv.maxbot.api.MaxChatAccess.Result("UNKNOWN","access_unconfirmed"));
        health.recheck(2116L);
        jdbc.update("UPDATE client_contact_health SET checked_at=UTC_TIMESTAMP()-INTERVAL 14 MINUTE");
        health.checkDueContacts(); verify(max,times(1)).check(-7445);
        when(max.check(-7445)).thenReturn(new com.hunt.otziv.maxbot.api.MaxChatAccess.Result("UNAVAILABLE","max_chat_closed"));
        jdbc.update("UPDATE client_contact_health SET checked_at=UTC_TIMESTAMP()-INTERVAL 16 MINUTE");
        health.checkDueContacts(); verify(max,times(2)).check(-7445);
        assertThat(health.excludedCompanies()).hasSize(1);
        assertThat(health.managerIssues(13L)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM scheduled_client_message_state WHERE state_id=1238696",String.class)).isEqualTo("UNKNOWN");
        health.checkDueContacts(); verify(max,times(2)).check(-7445);
    }
    @Test void disabledCompanyWithActiveOrderStillRequiresManagerAttention() {
        jdbc.update("UPDATE companies SET company_active=FALSE");
        jdbc.update("UPDATE orders SET order_status=2");
        assertThat(health.requiresManagerAttention(2116L)).isTrue();
        assertThat(health.routing(13L).active()).containsExactly(2116L);
        assertThat(health.adminOnly(2116L)).isFalse();
    }
}
