package com.hunt.otziv.whatsapp;

import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.security.credentials.CredentialEncryptionProperties;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.repository.WhatsAppBusinessOperationRepository;
import com.hunt.otziv.whatsapp.service.WhatsAppBusinessOperationService;
import com.hunt.otziv.whatsapp.service.WhatsAppServiceImpl;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WhatsAppBusinessOperationsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("wa_business_ops").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private WhatsAppBusinessOperations operations;
    private CredentialCipher cipher;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);
        for(String table:List.of("whatsapp_manual_send_operations","whatsapp_business_send_operations","performer_legacy_maintenance_rows","performer_legacy_maintenance_runs","leads"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE leads(id BIGINT PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_302__maintenance_checkpoints_and_legacy_send_operations.sql")).execute(ds);
        manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        CredentialEncryptionProperties encryption=new CredentialEncryptionProperties();encryption.setActiveKeyId("fixture");encryption.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        cipher=new CredentialCipher(encryption);operations=createOperations(cipher);
    }

    @Test void immutableEncryptedSnapshotSurvivesRestartAndRecipientTemplateChanges() {
        var first=operations.freeze("invoice-1","client1","send-group","12345678","Сумма 100 ₽ для 79990000000");
        assertThat(first.destination()).isEqualTo("12345678@g.us");
        assertThat(jdbc.queryForObject("SELECT envelope_ciphertext FROM whatsapp_business_send_operations",String.class)).startsWith("enc:v1:").doesNotContain("79990000000","Сумма");
        var restarted=createOperations(cipher);
        assertThat(restarted.freeze("invoice-1","different-client","send-group","87654321","Новый шаблон")).isEqualTo(first);
        assertThatThrownBy(()->restarted.requireMatches("invoice-1","different-client","send-group","87654321","Новый шаблон")).hasMessage("operation_payload_conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isEqualTo(1);
    }

    @Test void businessRollbackRemovesReservationAndAfterCommitDispatchReallyCommitsItsOwnReservation() {
        assertThatThrownBy(()->tx.executeWithoutResult(status->{operations.freeze("rolled-back","client1","send-group","12345678","original");throw new IllegalStateException("late business failure");})).hasMessage("late business failure");
        assertThatThrownBy(()->operations.requireFrozen("rolled-back")).hasMessage("operation_not_prepared");
        tx.executeWithoutResult(status->TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                operations.freezeForDispatch("committed-callback","client1","send-group","12345678","callback");
                assertThat(new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()))
                        .queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations WHERE operation_id='committed-callback'",Integer.class)).isEqualTo(1);
            }
        }));
    }

    @Test void parallelSameOccurrenceHasOneAuthoritativeSnapshot() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return operations.freeze("parallel","client1","send-group","12345678","A");});
            var b=pool.submit(()->{start.await();return operations.freeze("parallel","client2","send-group","87654321","B");});
            start.countDown();var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isEqualTo(1);
        }
    }

    @Test void strictTransportCannotSendMissingOrChangedOperationAndLegacyRejectionIsMeasured() {
        var properties=new WhatsAppProperties();var client=new WhatsAppProperties.ClientConfig();client.setId("client1");client.setUrl("http://fixture.invalid:3000");properties.setClients(List.of(client));
        var transport=mock(org.springframework.web.client.RestTemplate.class);var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var adapter=new WhatsAppServiceImpl(properties,transport,operations,meters);
        assertThat(adapter.sendMessage("client1","79990000000","legacy")).contains("operation_id_required");
        assertThat(adapter.sendMessageToGroup("client1","12345678","legacy")).contains("operation_id_required");
        assertThat(adapter.sendMessageToGroup("client1","12345678","message","missing")).contains("operation_not_prepared");
        operations.freeze("frozen","client1","send-group","12345678","original");
        assertThat(adapter.sendMessageToGroup("client1","12345678","changed","frozen")).contains("operation_payload_conflict");
        verifyNoInteractions(transport);
        assertThat(meters.get("otziv.whatsapp.legacy_send.rejected").tag("kind","send").counter().count()).isEqualTo(1);
        assertThat(meters.get("otziv.whatsapp.legacy_send.rejected").tag("kind","send-group").counter().count()).isEqualTo(1);
    }

    @Test void manualOperationIsDurableAndBoundToActorBeforeEnvelopeExists() {
        String id=operations.createManualOperation("operator-A");
        var restarted=createOperations(cipher);restarted.requireManualOwner(id,"operator-A");
        assertThatThrownBy(()->restarted.requireManualOwner(id,"operator-B")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT actor_hash FROM whatsapp_manual_send_operations",String.class)).doesNotContain("operator-A");
        assertThatThrownBy(()->restarted.requireFrozen(id)).hasMessage("operation_not_prepared");
    }

    @Test void missingEncryptionCannotPersistPlaintext() {
        var insecure=createOperations(new CredentialCipher(new CredentialEncryptionProperties()));
        assertThatThrownBy(()->insecure.freeze("not-stored","client1","send-group","12345678","sensitive"))
                .hasMessageContaining("Encrypted business operation storage");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isZero();
    }

    @Test void leadPrepareRollsBackWithBusinessMutationAndReplayKeepsFrozenEnvelopeOutsideTransaction() {
        var transport=mock(com.hunt.otziv.whatsapp.service.service.WhatsAppService.class);
        var notifier=proxy(new com.hunt.otziv.l_lead.service.LeadWorkNotificationService(operations,transport,manager));
        var lead=new com.hunt.otziv.l_lead.model.Lead();lead.setId(71L);lead.setWhatsappWorkGeneration(1);
        var owner=new com.hunt.otziv.u_users.model.Manager();owner.setId(3L);owner.setClientId("client1");lead.setManager(owner);
        lead.setTelephoneLead("79990000000");lead.setCityLead("Город");lead.setCommentsLead("Original");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{notifier.prepare(lead);throw new IllegalStateException("late lead failure");})).hasMessage("late lead failure");
        verifyNoInteractions(transport);assertThat(operations.findFrozen("lead-work:71:1")).isEmpty();
        when(transport.sendMessageToGroup(anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()))
                    .queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations WHERE operation_id='lead-work:71:1'",Integer.class)).isEqualTo(1);
            return "unknown";
        });
        tx.executeWithoutResult(status->notifier.prepare(lead));
        lead.setCommentsLead("New template");owner.setClientId("client2");
        tx.executeWithoutResult(status->notifier.prepare(lead));
        verify(transport,times(2)).sendMessageToGroup(eq("client1"),eq("120363399937937645@g.us"),contains("Original"),eq("lead-work:71:1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isEqualTo(1);
    }

    @Test void legacyLeadGenerationDoesNotInventHistoricalNonDelivery() {
        var transport=mock(com.hunt.otziv.whatsapp.service.service.WhatsAppService.class);
        var notifier=proxy(new com.hunt.otziv.l_lead.service.LeadWorkNotificationService(operations,transport,manager));
        var lead=new com.hunt.otziv.l_lead.model.Lead();lead.setId(71L);
        tx.executeWithoutResult(status->notifier.prepare(lead));
        verifyNoInteractions(transport);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isZero();
    }

    @Test void realManualControllerRetainsOperationAfterUnknownAndRejectsChangedFormOrActor() {
        var transport=mock(com.hunt.otziv.whatsapp.service.service.WhatsAppService.class);
        var controller=new com.hunt.otziv.whatsapp.controller.SendMessageController(transport,operations);
        var form=new java.util.HashMap<String,Object>();controller.showForm(form,()->"operator-A");
        String id=(String)form.get("operationId");
        when(transport.sendMessage(anyString(),anyString(),anyString(),anyString())).thenReturn("unknown");
        var model=new org.springframework.ui.ExtendedModelMap();
        assertThat(controller.sendMessage("client1","79990000000","original",id,()->"operator-A",model)).isEqualTo("lead/layouts/whatsapp");
        controller.sendMessage("client1","79990000000","original",id,()->"operator-A",model);
        controller.sendMessage("client1","79990000000","changed",id,()->"operator-A",model);
        assertThat(model.get("operationId")).isEqualTo(id);assertThat(model.get("result").toString()).contains("operation_invalid");
        controller.sendMessage("client1","79990000000","original",id,()->"operator-B",model);
        verify(transport,times(2)).sendMessage("client1","79990000000","original",id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_business_send_operations",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_manual_send_operations",Integer.class)).isEqualTo(1);
    }

    @Test void optionalLookupValidatesStoredEnvelopeIntegrityRatherThanReturningUntrustedCiphertext() {
        operations.freeze("integrity","client1","send","79990000000","original");
        jdbc.update("UPDATE whatsapp_business_send_operations SET envelope_hash=REPEAT('0',64) WHERE operation_id='integrity'");
        assertThatThrownBy(()->operations.findFrozen("integrity")).hasMessage("Business operation snapshot is corrupt");
    }

    private WhatsAppBusinessOperations createOperations(CredentialCipher key) {
        var repo=proxy(new WhatsAppBusinessOperationRepository(jdbc));
        return proxy(new WhatsAppBusinessOperationService(repo,key));
    }
    @SuppressWarnings("unchecked") private <T>T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();
    }
}
