package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Production migration/JDBC/store, actual Spring transaction proxies and separate MySQL connections. */
@Testcontainers
class ClientMessageOperationFenceMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("message_fence_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;DataSourceTransactionManager manager;ClientMessageOperationStore store;ClientMessageOperationFence fence;
    AtomicBoolean failCommit=new AtomicBoolean();
    @BeforeEach void prepare(){
        var data=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(data);
        jdbc.execute("DROP TABLE IF EXISTS client_message_operation_resolutions");jdbc.execute("DROP TABLE IF EXISTS client_message_operations");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_304__client_message_operation_fence.sql")).execute(data);
        jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_outer(id INT PRIMARY KEY)");jdbc.update("DELETE FROM fixture_outer");
        manager=new DataSourceTransactionManager(data){@Override protected void doCommit(DefaultTransactionStatus status){
            if(failCommit.getAndSet(false))throw new TransactionSystemException("fixture_commit_refused");super.doCommit(status);}};
        manager.setRollbackOnCommitFailure(true);
        store=proxy(new ClientMessageOperationStore(jdbc));fence=proxy(new ClientMessageOperationFence(store));
    }
    @SuppressWarnings("unchecked") <T>T proxy(T actual){var factory=new ProxyFactory(actual);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    @AfterEach void clearActor(){SecurityContextHolder.clearContext();}
    ClientMessageSendResult send(){return ClientMessageSendResult.sent("Telegram","77");}
    ClientMessageSendResult unknown(){return ClientMessageSendResult.failed("operation_unknown","fixture");}
    @Test void providerRunsWithoutCallerTransactionAndBarrierSurvivesCallerRollback(){
        var outer=new TransactionTemplate(manager);
        outer.execute(status->{jdbc.update("INSERT INTO fixture_outer VALUES(1)");
            assertThat(fence.execute("invoice:1","TELEGRAM","-1001","[\"message\",null,null]",()->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(jdbc.queryForObject("SELECT state FROM client_message_operations WHERE operation_id='invoice:1'",String.class)).isEqualTo("PREPARED");return send();
            }).sent()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fixture_outer",Integer.class)).isZero();
        assertThat(fence.lookup("invoice:1").orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void confirmedReplayIgnoresMutableRoutingAndPayloadAndNeverCallsProviderAgain(){
        assertThat(fence.execute("invoice:1","TELEGRAM","-1001","original",this::send).sent()).isTrue();
        var replay=fence.execute("invoice:1","MAX","999","changed bank and message",()->{throw new AssertionError("replayed");});
        assertThat(replay.channel()).isEqualTo("Telegram");assertThat(replay.messageId()).isEqualTo("77");
    }
    @Test void returnedUnknownExceptionAndMissingReceiptQuarantineAcrossRestart(){
        for(int i=0;i<3;i++){
            int kind=i;String id="fixture:"+i;
            var result=fence.execute(id,"TELEGRAM","-1001","body",()->switch(kind){case 0->unknown();case 1->throw new IllegalStateException("fixture");default->ClientMessageSendResult.sent("Telegram");});
            assertThat(result.errorCode()).isEqualTo("operation_unknown");
            var restarted=proxy(new ClientMessageOperationFence(proxy(new ClientMessageOperationStore(jdbc))));
            assertThat(restarted.execute(id,"MAX","changed","changed",()->{throw new AssertionError("unknown replay");}).errorCode()).isEqualTo("operation_unknown");
            assertThat(restarted.lookup(id).orElseThrow().state()).isEqualTo("UNKNOWN");
        }
    }
    @Test void actualConcurrentCallCannotAcquireInFlightOperation()throws Exception{
        var pool=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);AtomicInteger sends=new AtomicInteger();
        try{
            var first=pool.submit(()->fence.execute("invoice:1","TELEGRAM","-1001","body",()->{sends.incrementAndGet();entered.countDown();
                try{assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return send();}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(fence.execute("invoice:1","TELEGRAM","-1001","body",()->{sends.incrementAndGet();return send();}).errorCode()).isEqualTo("operation_unknown");
            finish.countDown();assertThat(first.get(10,TimeUnit.SECONDS).sent()).isTrue();assertThat(sends).hasValue(1);
        }finally{finish.countDown();pool.shutdownNow();}
    }
    @Test void knownUnsentOnlyRetriesExactEnvelopeAndLatePriorTokenCannotOverwriteNewAttempt(){
        assertThat(fence.execute("invoice:1","TELEGRAM","-1001","[\"body\",\"copy\",\"111\"]",()->ClientMessageSendResult.failed("payload_too_large","fixture")).sent()).isFalse();
        var old=fence.lookup("invoice:1").orElseThrow();
        for(String[] args:new String[][]{{"MAX","-1001","[\"body\",\"copy\",\"111\"]"},{"TELEGRAM","-1002","[\"body\",\"copy\",\"111\"]"},{"TELEGRAM","-1001","[\"body\",\"copy\",\"222\"]"}})
            assertThat(fence.execute("invoice:1",args[0],args[1],args[2],()->{throw new AssertionError("changed envelope");}).errorCode()).isEqualTo("operation_payload_conflict");
        assertThat(fence.execute("invoice:1","TELEGRAM","-1001","[\"body\",\"copy\",\"111\"]",this::send).sent()).isTrue();
        assertThat(store.finish(old,unknown()).state()).isEqualTo("SUCCEEDED");
        assertThat(fence.lookup("invoice:1").orElseThrow().claimToken()).isNotEqualTo(old.claimToken());
    }
    @Test void commitFailureAfterActualProviderSuccessDoesNotCertifyDeliveryOrPermitReplay(){
        var result=fence.execute("invoice:1","TELEGRAM","-1001","body",()->{failCommit.set(true);return send();});
        assertThat(result.errorCode()).isEqualTo("operation_unknown");
        assertThat(fence.lookup("invoice:1").orElseThrow().state()).isEqualTo("PREPARED");
        assertThat(fence.execute("invoice:1","TELEGRAM","-1001","body",()->{throw new AssertionError("commit failure replay");}).sent()).isFalse();
    }
    @Test void explicitAdministratorProofResolvesUnknownOnceAndInvalidActorCannotBorrowAmbientAuthority(){
        fence.execute("invoice:1","TELEGRAM","-1001","body",this::unknown);var saved=fence.lookup("invoice:1").orElseThrow();
        var admin=new TestingAuthenticationToken("fixture-owner",null,"ROLE_OWNER");SecurityContextHolder.getContext().setAuthentication(admin);
        for(var actor:new org.springframework.security.core.Authentication[]{null,new TestingAuthenticationToken("fixture-worker",null,"ROLE_WORKER"),new TestingAuthenticationToken("fixture-owner",null)})
            assertThatThrownBy(()->fence.confirmDelivered(actor,"invoice:1",saved.claimToken(),saved.envelopeHash(),"77","Receipt checked in provider interface"))
                .isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
        SecurityContextHolder.clearContext();
        assertThat(fence.confirmDelivered(admin,"invoice:1",saved.claimToken(),saved.envelopeHash(),"77","Receipt checked in provider interface").state()).isEqualTo("SUCCEEDED");
        assertThat(fence.confirmDelivered(admin,"invoice:1",saved.claimToken(),saved.envelopeHash(),"77","Receipt checked in provider interface").result().messageId()).isEqualTo("77");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_message_operation_resolutions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM client_message_operation_resolutions",String.class)).isEqualTo("fixture-owner");
        assertThat(store.finish(saved,unknown()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void staleProofAndFailedAuditCannotConfirmUnknown(){
        fence.execute("invoice:1","MAX","1001","body",this::unknown);var saved=fence.lookup("invoice:1").orElseThrow();
        var owner=new TestingAuthenticationToken("fixture-owner",null,"ROLE_OWNER");
        assertThatThrownBy(()->fence.confirmDelivered(owner,"invoice:1",UUID.randomUUID().toString(),saved.envelopeHash(),"mid.test","Receipt checked in provider interface")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->fence.confirmDelivered(owner,"invoice:1",saved.claimToken(),"0".repeat(64),"mid.test","Receipt checked in provider interface")).isInstanceOf(ResponseStatusException.class);
        jdbc.execute("CREATE TRIGGER reject_fixture_resolution BEFORE INSERT ON client_message_operation_resolutions FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture audit refusal'");
        assertThatThrownBy(()->fence.confirmDelivered(owner,"invoice:1",saved.claimToken(),saved.envelopeHash(),"mid.test","Receipt checked in provider interface")).isInstanceOf(RuntimeException.class);
        assertThat(fence.lookup("invoice:1").orElseThrow().state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_message_operation_resolutions",Integer.class)).isZero();
    }
    @Test void migrationPersistsHashesWithoutMessageOrRecipientPlaintext(){
        fence.execute("invoice:1","TELEGRAM","-100123456","private fixture body",this::unknown);
        var columns=jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='client_message_operations'",String.class);
        assertThat(columns).doesNotContain("message","destination","recipient","body");
        var saved=fence.lookup("invoice:1").orElseThrow();assertThat(saved.destinationHash()).hasSize(64);assertThat(saved.envelopeHash()).hasSize(64);
    }
    @Test void dottedIdAndValidUnicodeWorkButUnpairedSurrogateAndInvalidReceiptCannotBeCertified(){
        assertThat(fence.execute("invoice.fixture:1","TELEGRAM","-1001","Клиент\n😀",this::send).sent()).isTrue();
        assertThat(fence.execute("invalid:1","TELEGRAM","-1001","broken\uD800",()->{throw new AssertionError("invalid Unicode");}).errorCode()).isEqualTo("invalid_operation_envelope");
        assertThat(fence.lookup("invalid:1")).isEmpty();
        assertThat(fence.execute("invalid:2","TELEGRAM","-1001","body",()->ClientMessageSendResult.sent("Telegram","0")).errorCode()).isEqualTo("operation_unknown");
        var saved=fence.lookup("invalid:2").orElseThrow();
        assertThatThrownBy(()->fence.confirmDelivered(new TestingAuthenticationToken("fixture-owner",null,"ROLE_OWNER"),"invalid:2",saved.claimToken(),saved.envelopeHash(),"not-a-telegram-id","Receipt checked in provider interface")).isInstanceOf(ResponseStatusException.class);
        assertThat(fence.lookup("invalid:2").orElseThrow().state()).isEqualTo("UNKNOWN");
    }
    @Test void actualAdminHttpBoundaryRequiresExplicitRoleAndExactProof()throws Exception{
        fence.execute("invoice:1","TELEGRAM","-1001","body",this::unknown);
        var saved=fence.lookup("invoice:1").orElseThrow();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.hunt.otziv.client_messages.controller.ClientMessageOperationAdminController(fence)).build();
        String path="/api/admin/client-message-operations/invoice:1";
        var owner=new TestingAuthenticationToken("fixture-owner",null,"ROLE_OWNER");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).principal(new TestingAuthenticationToken("fixture-worker",null,"ROLE_WORKER")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String view=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).principal(owner))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(view).contains("UNKNOWN",saved.envelopeHash()).doesNotContain("-1001","body");
        String body="{\"expectedClaimToken\":\""+saved.claimToken()+"\",\"expectedEnvelopeHash\":\""+saved.envelopeHash()+"\",\"providerMessageId\":\"77\",\"reason\":\"Receipt checked in provider interface\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/confirm-delivered").principal(owner)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(fence.lookup("invoice:1").orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
}
