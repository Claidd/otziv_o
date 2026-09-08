package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.*;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.*;
import com.hunt.otziv.security.credentials.*;
import com.hunt.otziv.u_users.repository.*;
import jakarta.persistence.*;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Actual producer, receiver, mapper, JDBC receipt SQL and JPA entities with real Spring TX proxies. */
@Testcontainers
class LeadVersionedDeliveryMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("lead_protocol").withUsername("root").withPassword("root");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager em;
    static JdbcTemplate jdbc;
    static JpaTransactionManager manager;
    static TransactionTemplate tx;
    static LeadCommandCodec codec;
    static JwtService jwt;
    static String source;
    static final String FOREIGN_SOURCE="cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    LeadCommandRepository queue;
    LeadCommandService producer;
    LeadCommandReceiver receiver;
    LeadInboundMutationService mutations;
    LeadServiceImpl legacyPull;

    @BeforeAll static void database() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var beans=new DefaultListableBeanFactory();var cipher=new CredentialCipher(new CredentialEncryptionProperties());
        beans.registerSingleton("credentialCipher",cipher);beans.registerSingleton("encryptedCredentialConverter",new EncryptedCredentialConverter(cipher));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.show_sql","false",
                "hibernate.resource.beans.container",new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();em=SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        manager=new JpaTransactionManager(factory.getObject());manager.setDataSource(ds);tx=new TransactionTemplate(manager);jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE lead_command_queue");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_2_9__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_91__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_92__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_10_293__lead_command_delivery.sql"),
                new ClassPathResource("db/migration/V1_10_298__lead_command_consumer_compatibility_fence.sql"),
                new ClassPathResource("db/migration/V1_10_299__lead_blocking_scope_index.sql"),
                new ClassPathResource("db/migration/V1_10_306__lead_versioned_delivery_protocol.sql")).execute(ds);
        try(var validator=Validation.buildDefaultValidatorFactory()){codec=new LeadCommandCodec(validator.getValidator());}
        jwt=new JwtService();ReflectionTestUtils.setField(jwt,"secret",UUID.randomUUID()+UUID.randomUUID().toString());
        source=new LeadCommandRepository(jdbc).sourceIdentity();
    }

    @AfterAll static void close(){if(factory!=null)factory.destroy();}

    @BeforeEach void setup() {
        for(String table:List.of("lead_inbound_receipts","lead_inbound_entities","lead_inbound_targets","lead_command_manual_requests",
                "lead_command_queue","lead_command_stream","lead_command_replay_audit","leads"))jdbc.update("DELETE FROM "+table);
        queue=proxy(new LeadCommandRepository(jdbc));
        producer=proxy(new LeadCommandService(codec,queue,new LeadMapper(),jwt,em));
        // Spring Data's interface is adapted to real JPA persistence, without replacing
        // any snapshot, version, receipt or business mapping/transaction behavior.
        var leads=mock(LeadsRepository.class);
        when(leads.findByTelephoneLead(anyString())).thenAnswer(c->em.createQuery("SELECT l FROM Lead l WHERE l.telephoneLead=:phone",Lead.class)
                .setParameter("phone",c.getArgument(0)).getResultStream().findFirst());
        when(leads.findByIdForWorkTransition(anyLong())).thenAnswer(c->Optional.ofNullable(em.find(Lead.class,c.getArgument(0,Long.class),LockModeType.PESSIMISTIC_WRITE)));
        when(leads.save(any())).thenAnswer(c->{Lead lead=c.getArgument(0);if(lead.getId()==null)em.persist(lead);else if(!em.contains(lead))lead=em.merge(lead);em.flush();return lead;});
        mutations=proxy(new LeadInboundMutationService(leads,new LeadMapper(),mock(OperatorRepository.class),mock(ManagerRepository.class),
                mock(MarketologRepository.class),mock(TelephoneRepository.class),em));
        receiver=newReceiver();
        legacyPull=proxy(new LeadServiceImpl(leads,mock(UserRepository.class),mock(com.hunt.otziv.u_users.service.ManagerService.class),
                mock(com.hunt.otziv.u_users.service.OperatorService.class),mock(com.hunt.otziv.u_users.service.MarketologService.class),
                mock(com.hunt.otziv.z_zp.service.ZpService.class),mock(com.hunt.otziv.u_users.service.UserService.class),
                mock(TelephoneService.class),new LeadMapper(),mock(com.hunt.otziv.l_lead.event.LeadEventPublisher.class),
                mock(com.hunt.otziv.gamification.service.GamificationEventService.class),
                mock(com.hunt.otziv.config.settings.service.AppSettingService.class),mock(LeadAccessService.class),
                mock(LeadWorkNotificationService.class),proxy(new LeadInboundCommandRepository(jdbc))));
    }

    @Test void duplicateAfterRestartReturnsReceiptWithoutRepeatingBusinessMutation() {
        var dto=payload(1,1,"first");
        var first=receive(dto);
        var updatedAt=jdbc.queryForObject("SELECT update_status FROM leads",java.sql.Timestamp.class);
        receiver=newReceiver();
        assertThat(receive(dto)).isEqualTo(first);
        assertThat(count("leads")).isEqualTo(1);
        assertThat(count("lead_inbound_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT update_status FROM leads",java.sql.Timestamp.class)).isEqualTo(updatedAt);
    }

    @Test void newStateArrivingBeforeOldCannotBeRolledBackByLateOrRepeatedOldCommand() {
        var newest=payload(1,2,"newest");var older=payload(1,1,"old");
        assertThat(receive(newest).outcome()).isEqualTo("APPLIED");
        var stale=receive(older);
        assertThat(stale.outcome()).isEqualTo("STALE");assertThat(stale.appliedVersion()).isEqualTo(2);
        assertThat(receive(older)).isEqualTo(stale);
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("newest");
    }

    @Test void fullSnapshotClearsOptionalFieldsAndStrictUpdateUsesTheSameReceiverVersionBoundary() {
        var initial=payload(1,1,"initial");initial.setEmails("fixture@example.invalid");receive(initial);
        var full=payload(1,2,"full");receive(full);
        assertThat(jdbc.queryForObject("SELECT emails FROM leads",String.class)).isNull();
        var identity=new LeadCommandIdentity(1,source,1,3,UUID.randomUUID().toString(),"UPDATE");
        var update=LeadUpdateDto.builder().telephoneLead("79990000001").cityLead("Fixture").createDate(LocalDate.of(2026,9,7))
                .companyName("updated").command(identity).build();
        var result=receiver.receive("UPDATE",update,jwt.generateSyncToken("POST:/api/leads/update",update),identity.operationId());
        assertThat(((LeadCommandReceipt)result.body()).entityVersion()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("updated");
        var missing=new LeadCommandIdentity(1,source,2,1,UUID.randomUUID().toString(),"UPDATE");
        update.setCommand(missing);update.setTelephoneLead("79990000009");
        assertRejected(404,()->receiver.receive("UPDATE",update,jwt.generateSyncToken("POST:/api/leads/update",update),missing.operationId()));
        assertThat(count("leads")).isEqualTo(1);assertThat(count("lead_inbound_entities")).isEqualTo(1);
        assertThat(count("lead_inbound_receipts")).isEqualTo(3);
    }

    @Test void manualImportUsesVersionedUpsertAndNeverCreatesASecondLeadOnReplay() {
        var dto=payload(1,1,"import");
        dto.setCommand(new LeadCommandIdentity(1,source,1,1,UUID.randomUUID().toString(),"IMPORT"));
        var receipt=receive(dto);assertThat(receive(dto)).isEqualTo(receipt);
        assertThat(count("leads")).isEqualTo(1);
    }

    @Test void exactReceiptArrivingAfterSenderLeaseExpiryCannotPretendTheQueueCommitSucceeded() throws Exception {
        long leadId=seed("source");producer.enqueueSync(leadId);var claim=queue.claim(20).orElseThrow();
        var rest=new org.springframework.web.client.RestTemplate();
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(rest).build();
        var worker=new LeadCommandWorker(queue,codec,jwt,rest);ReflectionTestUtils.setField(worker,"syncUrl","http://fixture/sync");
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://fixture/sync")).andRespond(request->{
            var receipt=receive((LeadDtoTransfer)codec.decode("SYNC",1,claim.json()));
            jdbc.update("UPDATE lead_command_queue SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",claim.id());
            return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt),org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
        });
        worker.deliver(claim);queue.recoverExpired(20);server.verify();
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM lead_command_queue",String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT outcome FROM lead_inbound_receipts",String.class)).isEqualTo("APPLIED");
        assertThat(queue.claim(20)).isEmpty();
    }

    @Test void sameOperationChangedPayloadOrSameVersionDifferentOperationCannotOverwriteReceipt() {
        var dto=payload(1,1,"first");var first=receive(dto);
        dto.setCompanyName("altered");assertRejected(409,()->receive(dto));
        assertRejected(409,()->receive(payload(1,1,"other operation")));
        assertThat(count("lead_inbound_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM lead_inbound_receipts",String.class)).isEqualTo(first.payloadHash());
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("first");
    }

    @Test void legacyWorksBeforeBindingButCannotOverwriteAfterVersionedCutover() {
        var legacy=payload(1,1,"legacy");legacy.setCommand(null);
        assertThat(receiver.receive("SYNC",legacy,null,null).body()).isEqualTo("Лид создан");
        receive(payload(1,1,"versioned"));
        legacy.setCompanyName("late legacy");
        assertRejected(409,()->receiver.receive("SYNC",legacy,null,null));
        assertRejected(409,()->receiver.receive("IMPORT",legacy,null,null));
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("versioned");
    }

    @Test void reverseGetPullCannotBypassTheVersionedTargetFence() {
        var incoming=Lead.builder().telephoneLead("79990000001").cityLead("Fixture").companyName("legacy pull")
                .createDate(LocalDate.of(2026,9,7)).build();
        legacyPull.saveOrUpdateByTelephoneLead(incoming);
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("legacy pull");
        receive(payload(1,1,"versioned"));
        incoming.setCompanyName("late pull");
        assertThatThrownBy(()->legacyPull.saveOrUpdateByTelephoneLead(incoming))
                .isInstanceOf(LeadInboundCommandRepository.Conflict.class).hasMessage("LEAD_TARGET_REQUIRES_BOUND_SOURCE");
        assertThat(jdbc.queryForObject("SELECT company_name FROM leads",String.class)).isEqualTo("versioned");
    }

    @Test void signedSourceIdentityAndScopeCannotBeSubstitutedByHeadersOrLegacyBearer() {
        var dto=payload(1,1,"valid");String token=token(dto);
        var original=dto.getCommand();dto.setCommand(new LeadCommandIdentity(1,source,1,2,original.operationId(),"SYNC"));
        assertRejected(403,()->receiver.receive("SYNC",dto,token,original.operationId()));
        dto.setCommand(original);
        assertRejected(400,()->receiver.receive("SYNC",dto,token,UUID.randomUUID().toString()));
        assertRejected(403,()->receiver.receive("SYNC",dto,null,original.operationId()));
        dto.setCommand(new LeadCommandIdentity(1,FOREIGN_SOURCE,1,1,original.operationId(),"SYNC"));
        assertRejected(403,()->receive(dto));
        assertThat(count("leads")).isZero();assertThat(count("lead_inbound_receipts")).isZero();
    }

    @Test void oneTargetCannotBeClaimedByAnotherSourceOrEntityAndPhoneChangeRetainsOldFence() {
        receive(payload(1,1,"original"));
        assertRejected(409,()->receive(payload(2,1,"different entity")));
        var changed=payload(1,2,"new phone");changed.setTelephoneLead("79990000002");receive(changed);
        assertThat(count("leads")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT telephone_lead FROM leads",String.class)).isEqualTo("79990000002");
        var legacy=payload(1,1,"old phone retry");legacy.setCommand(null);
        assertRejected(409,()->receiver.receive("SYNC",legacy,null,null));
    }

    @Test void lateFailureRollsBackBusinessVersionReceiptAndTargetFenceTogether() {
        var dto=payload(1,1,"rollback");
        assertThatThrownBy(()->tx.executeWithoutResult(t->{receive(dto);throw new IllegalStateException("late caller failure");}))
                .isInstanceOf(IllegalStateException.class);
        for(String table:List.of("leads","lead_inbound_receipts","lead_inbound_entities","lead_inbound_targets"))assertThat(count(table)).isZero();
        assertThat(receive(dto).outcome()).isEqualTo("APPLIED");
    }

    @Test void concurrentDuplicateWaitsForOriginalCommitAndAppliesExactlyOneDatabaseMutation() throws Exception {
        var dto=payload(1,1,"parallel");var prepared=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(t->{var receipt=receive(dto);prepared.countDown();awaitLatch(commit);return receipt;}));
            assertThat(prepared.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->receive(dto));
            awaitDatabaseWait();
            assertThat(count("leads")).isZero();assertThat(second.isDone()).isFalse();
            commit.countDown();assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(first.get(15,TimeUnit.SECONDS));
            assertThat(count("leads")).isEqualTo(1);assertThat(count("lead_inbound_receipts")).isEqualTo(1);
        } finally {commit.countDown();}
    }

    @Test void hiddenUncommittedProducerCannotBeOvertakenAndSnapshotsFollowBusinessCommitOrder() throws Exception {
        long leadId=seed("initial");var prepared=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.execute(t->{em.find(Lead.class,leadId).setCompanyName("first");
                String id=producer.enqueueSync(leadId);prepared.countDown();awaitLatch(commit);return id;}));
            assertThat(prepared.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->tx.execute(t->{em.find(Lead.class,leadId).setCompanyName("second");return producer.enqueueSync(leadId);}));
            awaitDatabaseWait();
            assertThat(count("lead_command_queue")).isZero();assertThat(queue.claim(20)).isEmpty();assertThat(second.isDone()).isFalse();
            commit.countDown();String firstId=first.get(15,TimeUnit.SECONDS);String secondId=second.get(15,TimeUnit.SECONDS);
            var rows=jdbc.query("SELECT entity_version,payload_json,command_id FROM lead_command_queue ORDER BY entity_version",(rs,n)->List.of(rs.getLong(1),rs.getString(2),rs.getString(3)));
            assertThat(rows).hasSize(2);assertThat(rows.get(0).get(0)).isEqualTo(1L);assertThat(rows.get(1).get(0)).isEqualTo(2L);
            assertThat(((LeadDtoTransfer)codec.decode("SYNC",1,(String)rows.get(0).get(1))).getCompanyName()).isEqualTo("first");
            assertThat(((LeadDtoTransfer)codec.decode("SYNC",1,(String)rows.get(1).get(1))).getCompanyName()).isEqualTo("second");
            assertThat(rows.get(0).get(2)).isEqualTo(firstId);assertThat(rows.get(1).get(2)).isEqualTo(secondId);
            assertThat(queue.claim(20).orElseThrow().commandId()).isEqualTo(firstId);assertThat(queue.claim(20)).isEmpty();
        } finally {commit.countDown();}
    }

    @Test void producerRollbackDoesNotConsumeVersionAndManualUnknownRetryKeepsOriginalIdentityAndPayload() {
        long id=seed("frozen");String key=UUID.randomUUID().toString();
        assertThatThrownBy(()->tx.executeWithoutResult(t->{producer.enqueueImport(id,key);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("lead_command_queue")).isZero();assertThat(count("lead_command_stream")).isZero();
        String first=producer.enqueueImport(id,key);
        jdbc.update("UPDATE lead_command_queue SET delivery_state='UNKNOWN'");
        tx.executeWithoutResult(t->em.find(Lead.class,id).setCompanyName("changed later"));
        assertThat(producer.enqueueImport(id,null)).isEqualTo(first);
        assertThat(producer.enqueueImport(id,key)).isEqualTo(first);
        assertThat(producer.enqueueImport(id,UUID.randomUUID().toString())).isEqualTo(first);
        assertThat(count("lead_command_queue")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entity_version FROM lead_command_queue",Long.class)).isEqualTo(1);
        var dto=(LeadDtoTransfer)codec.decode("IMPORT",1,jdbc.queryForObject("SELECT payload_json FROM lead_command_queue",String.class));
        assertThat(dto.getCompanyName()).isEqualTo("frozen");
        long other=seed("other");assertThatThrownBy(()->producer.enqueueImport(other,key)).hasMessageContaining("LEAD_REQUEST_ID_CONFLICT");
    }

    private LeadCommandReceiver newReceiver() {
        var value=new LeadCommandReceiver(proxy(new LeadInboundCommandRepository(jdbc)),mutations,codec,jwt);
        ReflectionTestUtils.setField(value,"enabled",true);ReflectionTestUtils.setField(value,"allowedSourceIds",source);
        ReflectionTestUtils.setField(value,"legacyEnabled",true);value.validateActivation();return proxy(value);
    }
    private LeadCommandReceipt receive(LeadDtoTransfer dto) {
        return (LeadCommandReceipt)receiver.receive(dto.getCommand().kind(),dto,token(dto),dto.getCommand().operationId()).body();
    }
    private String token(LeadDtoTransfer dto) {return "IMPORT".equals(dto.getCommand().kind())?jwt.generateToken(dto):jwt.generateSyncToken("POST:/api/leads/sync",dto);}
    private LeadDtoTransfer payload(long entity,long version,String name) {
        return LeadDtoTransfer.builder().telephoneLead("79990000001").cityLead("Fixture").createDate(LocalDate.of(2026,9,7)).companyName(name)
                .command(new LeadCommandIdentity(1,source,entity,version,UUID.randomUUID().toString(),"SYNC")).build();
    }
    private long seed(String name) {return tx.execute(t->{var l=Lead.builder().telephoneLead("79"+String.format("%09d",Math.abs(UUID.randomUUID().getMostSignificantBits()%1_000_000_000L)))
            .cityLead("Fixture").companyName(name).build();em.persist(l);return l.getId();});}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private static void awaitLatch(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private void awaitDatabaseWait(){await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);}
    private void assertRejected(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(LeadCommandReceiver.Rejected.class,e->assertThat(e.status).isEqualTo(status));}
    @SuppressWarnings("unchecked") private static <T> T proxy(T target) {
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();
    }
}
