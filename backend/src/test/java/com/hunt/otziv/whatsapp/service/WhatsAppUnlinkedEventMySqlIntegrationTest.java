package com.hunt.otziv.whatsapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import java.util.UUID;
import org.junit.jupiter.api.*;
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
    WhatsAppGroupReplyDTO reply;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_unlinked_events");
        jdbc.execute("DROP TABLE IF EXISTS scheduled_client_message_state");
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_inbound_receipts");
        jdbc.execute("CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE whatsapp_inbound_receipts(receipt_key CHAR(64) PRIMARY KEY,completed_at DATETIME(6) NULL)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_365__unlinked_whatsapp_and_archive_holds.sql")).execute(ds);
        store=new WhatsAppUnlinkedEventStore(jdbc,new ObjectMapper());
        companies=mock(CompanyService.class);tracker=mock(ClientChatMessageTrackerService.class);
        service=new GroupReplyServiceImpl(companies,mock(WhatsAppGroupCompanyLinker.class),mock(PublicationProgressPreferenceService.class),
                mock(WhatsAppInboundReplyOutbox.class),tracker,store);
        var transactions=new DataSourceTransactionManager(ds);
        receipts=new WhatsAppGroupWebhookDeduplicator(jdbc,transactions);
        recovery=new WhatsAppUnlinkedEventRecovery(store,service,transactions);
        reply=new WhatsAppGroupReplyDTO("client","group@g.us","Company","sender@c.us","Client","external-message",1793635200L,false,false,"Нужен ответ");
    }
    void accept() {receipts.execute(reply,()->service.processGroupReply(reply));}
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
}
