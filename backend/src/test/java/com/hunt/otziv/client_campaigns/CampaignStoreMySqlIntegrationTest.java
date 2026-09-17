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
        jdbc.execute("CREATE TABLE IF NOT EXISTS company_status (company_status_id BIGINT PRIMARY KEY,status_title VARCHAR(30))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS managers (manager_id BIGINT PRIMARY KEY,client_id VARCHAR(128))");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS companies (company_id BIGINT PRIMARY KEY,company_title VARCHAR(500),company_status BIGINT,
                company_manager BIGINT,company_url_chat VARCHAR(500),company_group_id VARCHAR(128),
                company_telegram_group_chat_id BIGINT,company_max_group_chat_id BIGINT)
                """);
        jdbc.update("DELETE FROM companies"); jdbc.update("DELETE FROM company_status");
        jdbc.update("INSERT INTO company_status VALUES(1,'В работе'),(2,'На стопе'),(3,'Бан'),(4,'Новая')");
        var factory = new ProxyFactory(new CampaignStore(jdbc));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),new AnnotationTransactionAttributeSource()));
        store = (CampaignStore) factory.getProxy();
    }
    Settings settings(int limit) { return new Settings("Предложение","Новая услуга",limit,10,"10:00","21:00",true,true,true,"ATTACHMENT"); }
    void company(int id,int status,long chat) { jdbc.update("INSERT INTO companies(company_id,company_title,company_status,company_url_chat,company_telegram_group_chat_id) VALUES(?,?,?,'https://t.me/fixture',?)",id,"Компания "+id,status,chat); }
    String draft(Settings settings) { String id = UUID.randomUUID().toString(); store.save(id,settings,null,false,"owner",now); return id; }
    void sent(Claim c,LocalDateTime time) { store.finish(c,ClientMessageSendResult.sent("Telegram","77"),time); }
    @Test void snapshotOrdersSelectedListsAndDeduplicatesChatsAcrossCompanies() {
        company(1,3,1001); company(2,2,1002); company(3,1,1003); company(4,2,1003); company(5,4,1005);
        var id = draft(settings(50)); store.start(id,now); store.start(id,now);
        company(6,1,1006);
        assertThat(store.recipients(id,0)).extracting(Recipient::companyId).containsExactly(3L,2L,1L);
        assertThat(store.claim(id,now).recipient().companyId()).isEqualTo(3);
    }
    @Test void excludedListsNeverEnterQueueAndMissingChatsAreVisible() {
        company(1,1,1001); company(2,2,1002); company(3,3,1003); company(4,1,0);
        var s = new Settings("Offer","Body",10,10,"10:00","21:00",true,false,false,"LINK");
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
}
