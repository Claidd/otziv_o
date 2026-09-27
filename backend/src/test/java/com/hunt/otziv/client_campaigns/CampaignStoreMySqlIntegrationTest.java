package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class CampaignStoreMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("offer_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; CampaignStore store;
    final LocalDateTime now = LocalDateTime.of(2026,9,17,2,0); // 10:00 Irkutsk
    @BeforeEach void prepare() {
        var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        jdbc.execute("DROP TABLE IF EXISTS client_offer_recipient");
        jdbc.execute("DROP TABLE IF EXISTS client_offer_campaign_file");
        jdbc.execute("DROP TABLE IF EXISTS client_offer_campaign");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_320__client_offer_campaigns.sql")).execute(data);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_325__client_offer_test_audience.sql")).execute(data);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_326__client_offer_lead_audiences.sql")).execute(data);
        jdbc.execute("CREATE TABLE IF NOT EXISTS users (id BIGINT PRIMARY KEY,fio VARCHAR(200),username VARCHAR(200),active BOOLEAN,telegram_chat_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS roles (id BIGINT PRIMARY KEY,name VARCHAR(50))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS users_roles (user_id BIGINT,role_id BIGINT)");
        jdbc.update("DELETE FROM users_roles"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles");
        jdbc.update("INSERT INTO roles VALUES(1,'ROLE_ADMIN'),(2,'ROLE_OWNER'),(3,'ROLE_MANAGER'),(4,'ROLE_WORKER'),(5,'ROLE_USER')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS company_status (company_status_id BIGINT PRIMARY KEY,status_title VARCHAR(30))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managers (manager_id BIGINT PRIMARY KEY,client_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS leads (id BIGINT PRIMARY KEY,company_name VARCHAR(500),lid_status VARCHAR(30),telephone_lead VARCHAR(20),manager_id BIGINT)");
        jdbc.update("DELETE FROM leads"); jdbc.update("DELETE FROM managers");
        jdbc.update("INSERT INTO managers VALUES(1,'manager'),(2,NULL),(3,'fallback')");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS companies (company_id BIGINT PRIMARY KEY,company_title VARCHAR(500),company_status BIGINT,
                company_manager BIGINT,company_url_chat VARCHAR(500),company_group_id VARCHAR(128),
                company_telegram_group_chat_id BIGINT,company_max_group_chat_id BIGINT)
                """);
        jdbc.update("DELETE FROM companies"); jdbc.update("DELETE FROM company_status");
        jdbc.update("INSERT INTO company_status VALUES(1,'В работе'),(2,'На стопе'),(3,'Бан'),(4,'Новая')");
        var properties = new com.hunt.otziv.whatsapp.config.WhatsAppProperties();
        for (String account : java.util.List.of("manager","fallback")) {
            var client = new com.hunt.otziv.whatsapp.config.WhatsAppProperties.ClientConfig();
            client.setId(account); client.setUrl("https://wa.fixture/"+account); properties.getClients().add(client);
        }
        var factory = new ProxyFactory(new CampaignStore(jdbc,new CampaignLeadAudience(jdbc,properties)));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));
        store = (CampaignStore) factory.getProxy();
    }
    Settings settings(int limit) { return new Settings("Предложение","Новая услуга",limit,10,"10:00","21:00",true,true,true,"ATTACHMENT",false); }
    void company(int id,int status,long chat) { jdbc.update("INSERT INTO companies(company_id,company_title,company_status,company_url_chat,company_telegram_group_chat_id) VALUES(?,?,?,'https://t.me/fixture',?)",id,"Компания "+id,status,chat); }
    String draft(Settings settings) { String id = UUID.randomUUID().toString(); store.save(id,settings,null,false,"owner",now); return id; }
    void sent(Claim c,LocalDateTime time) { store.finish(c,ClientMessageSendResult.sent("Telegram","77"),time); }
    Settings leadSettings(boolean inWork,boolean other,String fallback) {
        return new Settings("Leads","Offer",30,10,"10:00","21:00",false,false,false,"ATTACHMENT",false,inWork,other,fallback);
    }
    void lead(int id,String status,String phone,Integer manager) {
        jdbc.update("INSERT INTO leads VALUES(?,?,?,?,?)",id,"Lead "+id,status,phone,manager);
    }
    @Test void leadListsAreIndependentBanIsExcludedAndManagerRoutingIsPreserved() {
        lead(1,"В работе","89991111111",1); lead(2,"Отправленный","79992222222",null);
        lead(3,"Бан","79993333333",1); lead(4,"К рассылке","79994444444",2);
        lead(5,"Ошибка","invalid",1); lead(6,"В работу","79996666666",1);
        var settings = leadSettings(true,true,"fallback");
        var id = draft(settings); store.start(id,now);
        assertThat(store.get(id).settings()).isEqualTo(settings);
        var rows = store.recipients(id,0);
        assertThat(rows).extracting(Recipient::leadId).containsExactly(1L,2L,4L,5L,6L);
        assertThat(rows.getFirst().phone()).isEqualTo("79991111111");
        assertThat(rows.getFirst().clientId()).isEqualTo("manager");
        assertThat(rows.get(1).clientId()).isEqualTo("fallback");
        assertThat(rows.get(2).state()).isEqualTo("SKIPPED");
        assertThat(rows.get(3).state()).isEqualTo("SKIPPED");
        assertThat(rows).allMatch(r -> r.companyId()==null && r.userId()==null && r.operationId().contains(":lead:"));
        var activeOnly = draft(leadSettings(true,false,null)); store.start(activeOnly,now);
        assertThat(store.recipients(activeOnly,0)).extracting(Recipient::leadId).containsExactly(1L);
        var otherOnly = draft(leadSettings(false,true,"fallback")); store.start(otherOnly,now);
        assertThat(store.recipients(otherOnly,0)).extracting(Recipient::leadId).containsExactly(2L,4L,5L,6L);
    }
    @Test void duplicatesAcrossListsReceiveOneMessageAndBannedPhoneVariantsNeverEnterQueue() {
        lead(1,"В работе","89991111111",1); lead(2,"Отправленный","79991111111",null);
        lead(3,"В работе","79993333333",1); lead(4," БАН ","89993333333",1);
        var id = draft(leadSettings(true,true,"fallback")); store.start(id,now);
        assertThat(store.recipients(id,0)).extracting(Recipient::leadId).containsExactly(1L);
        assertThat(store.counts(id).pending()).isEqualTo(1);
    }
    @Test void sendTimeCheckRejectsNewBanChangedNumberManagerAndDeletedLead() {
        lead(1,"В работе","79991111111",1);
        var settings = leadSettings(true,true,"fallback"); var id=draft(settings); store.start(id,now);
        var recipient=store.recipients(id,0).getFirst();
        assertThat(store.leadRecipientAllowed(settings,recipient)).isTrue();
        jdbc.update("UPDATE leads SET lid_status='Бан' WHERE id=1");
        assertThat(store.leadRecipientAllowed(settings,recipient)).isFalse();
        jdbc.update("UPDATE leads SET lid_status='В работе',telephone_lead='79992222222' WHERE id=1");
        assertThat(store.leadRecipientAllowed(settings,recipient)).isFalse();
        jdbc.update("UPDATE leads SET telephone_lead='79991111111',manager_id=3 WHERE id=1");
        assertThat(store.leadRecipientAllowed(settings,recipient)).isFalse();
        jdbc.update("UPDATE leads SET manager_id=1 WHERE id=1"); lead(2,"ban","89991111111",1);
        assertThat(store.leadRecipientAllowed(settings,recipient)).isFalse();
        jdbc.update("DELETE FROM leads"); assertThat(store.leadRecipientAllowed(settings,recipient)).isFalse();
    }
    @Test void missingFallbackIsSkippedUnknownAccountIsRejectedAndTestModeExcludesLeads() {
        lead(1,"В работе","79991111111",null); lead(2,"Ошибка","79992222222",1);
        var settings=leadSettings(true,true,null); var id=draft(settings); store.start(id,now);
        assertThat(store.recipients(id,0).getFirst().state()).isEqualTo("SKIPPED");
        assertThatThrownBy(() -> draft(leadSettings(true,true,"unknown"))).isInstanceOf(ResponseStatusException.class);
        user(1,1,true,123L);
        var test=new Settings("Test","Body",30,10,"10:00","21:00",true,true,true,"LINK",true,true,true,"fallback");
        var testId=draft(test); store.start(testId,now);
        assertThat(store.recipients(testId,0)).hasSize(1).allMatch(r -> r.leadId()==null && r.userId()!=null);
    }
    @Test void oldCompanyCampaignDoesNotIncludeLeadsAndSharesBudgetWhenExplicitlySelected() {
        company(1,1,123L); lead(1,"В работе","79991111111",1);
        var oldId=draft(settings(1)); store.start(oldId,now);
        assertThat(store.recipients(oldId,0)).hasSize(1).allMatch(r -> r.leadId()==null);
        var mixed=new Settings("Mixed","Body",1,10,"10:00","21:00",true,false,false,"LINK",false,true,false,null);
        var id=draft(mixed); store.start(id,now); sent(store.claim(id,now),now);
        assertThat(store.claim(id,now.plusMinutes(10))).isNull();
        assertThat(store.claim(id,now.plusDays(1)).recipient().leadId()).isEqualTo(1);
    }
    @Test void snapshotOrdersSelectedListsAndDeduplicatesChatsAcrossCompanies() {
        company(1,3,1001); company(2,2,1002); company(3,1,1003); company(4,2,1003); company(5,4,1005);
        var id = draft(settings(50)); store.start(id,now); store.start(id,now);
        company(6,1,1006);
        assertThat(store.recipients(id,0)).extracting(Recipient::companyId).containsExactly(3L,2L,1L);
        assertThat(store.claim(id,now).recipient().companyId()).isEqualTo(3);
    }
    @Test void excludedListsNeverEnterQueueAndMissingChatsAreVisible() {
        company(1,1,1001); company(2,2,1002); company(3,3,1003); company(4,1,0);
        var s = new Settings("Offer","Body",10,10,"10:00","21:00",true,false,false,"LINK",false);
        var id = draft(s); store.start(id,now);
        assertThat(store.counts(id).total()).isEqualTo(2);
        assertThat(store.counts(id).skipped()).isEqualTo(1);
        assertThat(store.preview(s).getFirst().reachable()).isEqualTo(1);
    }
    @Test void dailyBudgetAndIntervalSurviveStoreRestartAndResumeNextIrkutskDay() {
        company(1,1,1001); company(2,2,1002); company(3,3,1003);
        var id = draft(settings(1)); store.start(id,now);
        sent(store.claim(id,now),now.plusMinutes(2));
        assertThat(store.claim(id,now.plusMinutes(11))).isNull();
        assertThat(store.claim(id,now.plusHours(1))).isNull();
        assertThat(store.get(id).nextAt()).isEqualTo(now.plusDays(1));
        assertThat(store.claim(id,now.plusDays(1)).recipient().companyId()).isEqualTo(2);
        assertThat(store.get(id).budgetUsed()).isEqualTo(1);
    }
    @Test void pausesAndTerminalCancellationPreserveSentRecipients() {
        company(1,1,1001); company(2,1,1002);
        var id = draft(settings(10)); store.start(id,now); sent(store.claim(id,now),now);
        store.action(id,"pause",now); assertThat(store.claim(id,now.plusHours(1))).isNull();
        store.action(id,"resume",now); store.action(id,"cancel",now);
        assertThat(store.counts(id).sent()).isEqualTo(1); assertThat(store.counts(id).skipped()).isEqualTo(1);
        assertThatThrownBy(() -> store.action(id,"resume",now)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void uncertainAndCrashedAttemptsAreNeverRequeuedButKnownFailuresCanBe() {
        company(1,1,1001); company(2,1,1002); company(3,1,1003);
        var id = draft(settings(10)); store.start(id,now);
        store.finish(store.claim(id,now),ClientMessageSendResult.failed("invalid_request","No send"),now);
        var crashed = store.claim(id,now.plusMinutes(10));
        assertThat(store.claim(id,now.plusMinutes(20))).isNull();
        var next = store.claim(id,now.plusMinutes(41)); sent(next,now.plusMinutes(41));
        assertThat(store.counts(id).unknown()).isEqualTo(1);
        assertThat(store.get(id).state()).isEqualTo("COMPLETED");
        store.action(id,"retry-failed",now.plusHours(1)); store.action(id,"resume",now.plusHours(1));
        assertThat(store.claim(id,now.plusHours(1)).recipient().companyId()).isEqualTo(1);
        assertThat(store.recipients(id,0).stream().filter(r -> r.id()==crashed.recipient().id()).findFirst().orElseThrow().state()).isEqualTo("UNKNOWN");
    }
    @Test void simultaneousWorkersClaimOnlyOneRecipient() throws Exception {
        company(1,1,1001); company(2,1,1002);
        var id = draft(settings(10)); store.start(id,now);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Claim> work = () -> { gate.await(); return store.claim(id,now); };
            var first = pool.submit(work); var second = pool.submit(work); gate.countDown();
            assertThat(java.util.stream.Stream.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        }
        assertThat(store.get(id).budgetUsed()).isEqualTo(1);
    }
    @Test void draftFileIsPrivateUntilStartAndContentCannotChangeAfterStart() {
        company(1,1,1001);
        var id = draft(settings(10)); var file = new Attachment("offer.pdf","application/pdf",new byte[]{1,2,3});
        var c = store.save(id,settings(10),file,false,"owner",now);
        assertThat(store.publicAttachment(c.fileToken())).isNull();
        store.start(id,now);
        assertThat(store.publicAttachment(c.fileToken()).bytes()).containsExactly(1,2,3);
        assertThatThrownBy(() -> store.save(id,settings(1),file,false,"owner",now)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.get(id).settings().dailyLimit()).isEqualTo(10);
    }

    void user(long id,int role,boolean active,Long chat) {
        jdbc.update("INSERT INTO users VALUES(?,?,?,?,?)",id,"User "+id,"user"+id,active,chat);
        jdbc.update("INSERT INTO users_roles VALUES(?,?)",id,role);
    }
    Settings testSettings(boolean includeClients) {
        return new Settings("Test","Body",10,10,"10:00","21:00",includeClients,includeClients,includeClients,"ATTACHMENT",true);
    }
    @Test void testAudienceExcludesAllCompaniesAndOtherRolesEvenWhenClientListsAreSelected() {
        company(1,1,9001); company(2,2,9002); company(3,3,9003);
        user(1,1,true,101L); user(2,2,true,102L); user(3,3,true,103L);
        user(4,4,true,104L); user(5,5,true,105L); user(6,1,false,106L);
        user(7,2,true,null); user(8,1,true,-108L); user(9,2,true,101L);
        jdbc.update("INSERT INTO users_roles VALUES(1,2)");
        var settings = testSettings(true);
        assertThat(store.preview(settings)).containsExactly(new AudienceCount("TEST_STAFF",4,2));
        var id = draft(settings); store.start(id,now);
        assertThat(store.get(id).settings().testOnly()).isTrue();
        var rows = store.recipients(id,0);
        assertThat(rows).extracting(Recipient::userId).containsExactly(1L,2L,7L,8L);
        assertThat(rows).allMatch(r -> r.companyId() == null && "TEST_STAFF".equals(r.audience()));
        assertThat(rows).filteredOn(r -> "PENDING".equals(r.state())).extracting(Recipient::telegramChatId).containsExactly(101L,102L);
        assertThat(store.claim(id,now).recipient().userId()).isEqualTo(1L);
    }
    @Test void testsWorkWithoutClientListsAndRevalidateRoleActiveStateAndPersonalChat() {
        user(1,2,true,101L);
        var id = draft(testSettings(false)); store.start(id,now);
        var recipient = store.recipients(id,0).getFirst();
        assertThat(store.testRecipientAllowed(recipient)).isTrue();
        jdbc.update("UPDATE users SET active=FALSE WHERE id=1");
        assertThat(store.testRecipientAllowed(recipient)).isFalse();
        jdbc.update("UPDATE users SET active=TRUE,telegram_chat_id=102 WHERE id=1");
        assertThat(store.testRecipientAllowed(recipient)).isFalse();
        jdbc.update("UPDATE users SET telegram_chat_id=101 WHERE id=1");
        jdbc.update("UPDATE users_roles SET role_id=3 WHERE user_id=1");
        assertThat(store.testRecipientAllowed(recipient)).isFalse();
    }
    @Test void missingTestChatsCannotFallBackToReachableCompanies() {
        company(1,1,9001); user(1,1,true,null);
        var id = draft(testSettings(true));
        assertThatThrownBy(() -> store.start(id,now)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.get(id).state()).isEqualTo("DRAFT");
        assertThat(store.counts(id).total()).isZero();
    }
}
