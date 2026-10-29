package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.security.credentials.*;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
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
class ObsoleteManualContactMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("obsolete_contact").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; DataSourceTransactionManager tm; TransactionTemplate tx;
    ManagerClientMessageQueue queue; ManagerControlObsoleteContactSettlement settlement;
    ManagerClientMessageQueue.Claim claim; ManagerClientMessageQueue.Command command;
    OrderDeliveryControlContext orders; ClientMessageDelivery delivery;
    final LocalDateTime now=LocalDateTime.now().withNano(0);

    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(tm);
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_queue");
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_retirements");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_313__manager_client_message_queue.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V1_10_347__obsolete_manual_contact_journal.sql")));
        var keys=new CredentialEncryptionProperties();keys.setActiveKeyId("fixture");
        keys.setActiveKeyBase64(Base64.getEncoder().encodeToString(new byte[32]));
        queue=proxy(new ManagerClientMessageQueue(jdbc,new ObjectMapper(),new CredentialCipher(keys)));
        command=new ManagerClientMessageQueue.Command("old-contact",7,"CONTROL",
                "{\"orderId\":77,\"concreteItemId\":7,\"deliveryToken\":\"old-contact\",\"orderStatus\":\"На проверке\"}",
                new DeferredUserAuthority.Actor(3L,"fixture",1,Set.of("ROLE_MANAGER")));
        tx.executeWithoutResult(s->queue.enqueue(command));
        var first=queue.claim().orElseThrow();
        tx.executeWithoutResult(s->queue.finish(first,"UNKNOWN","whatsapp_history_checked_no_receipt",300));
        jdbc.update("UPDATE manager_client_message_queue SET created_at=?,next_attempt_at=NOW(6)",now.minusDays(7));
        claim=queue.claim().orElseThrow();
        orders=mock(OrderDeliveryControlContext.class); delivery=mock(ClientMessageDelivery.class);
        currentCycle(now.minusDays(6));
        when(delivery.hasSettledUncertainOperation("old-contact")).thenReturn(true);
        settlement=proxy(new ManagerControlObsoleteContactSettlement(queue,orders,delivery,proxy(new ManagerControlTransactionRunner())));
    }

    private void currentCycle(LocalDateTime at) {
        var source=new OrderDeliveryControlContext.Snapshot(77L,2L,"Публикация",3,at,false,null);
        when(orders.read(77L)).thenReturn(Optional.of(source));
        when(orders.lock(77L)).thenReturn(Optional.of(source));
    }
    private String state(){return jdbc.queryForObject("SELECT state FROM manager_client_message_queue",String.class);}
    private int journal(){return jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_message_retirements",Integer.class);}

    @Test void retiresOnlyBookkeepingAndKeepsEncryptedUnknownEvidenceAcrossRepeatedChecks() {
        String cipher=jdbc.queryForObject("SELECT envelope_ciphertext FROM manager_client_message_queue",String.class);
        assertThat(settlement.retire(claim,command)).isTrue();
        assertThat(state()).isEqualTo("SUPERSEDED");assertThat(journal()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT envelope_ciphertext FROM manager_client_message_queue",String.class)).isEqualTo(cipher);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.state')) FROM manager_client_message_retirements",String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_snapshot,'$.envelopeCiphertext')) FROM manager_client_message_retirements",String.class)).isEqualTo(cipher);
        assertThat(queue.claim()).isEmpty();assertThat(queue.claimReceipt("old-contact")).isEmpty();
        assertThat(settlement.retire(claim,command)).isFalse();assertThat(journal()).isEqualTo(1);
        verify(delivery,times(2)).hasSettledUncertainOperation("old-contact");verifyNoMoreInteractions(delivery);
    }

    @Test void currentCycleUnknownMustKeepItsFenceAndQueue() {
        currentCycle(now.minusDays(8));
        assertThat(settlement.retire(claim,command)).isFalse();
        assertThat(state()).isEqualTo("SENDING");assertThat(journal()).isZero();verifyNoInteractions(delivery);
    }

    @Test void runningOrAlreadyConfirmedProviderOperationCannotBeRetired() {
        when(delivery.hasSettledUncertainOperation("old-contact")).thenReturn(false);
        assertThat(settlement.retire(claim,command)).isFalse();assertThat(journal()).isZero();
        verify(orders,never()).lock(77L);
    }

    @Test void staleLeaseCannotRetireOrCreateJournalEvidence() {
        jdbc.update("UPDATE manager_client_message_queue SET claim_token=?",UUID.randomUUID().toString());
        assertThat(settlement.retire(claim,command)).isFalse();assertThat(journal()).isZero();
        assertThat(state()).isEqualTo("SENDING");
    }

    @Test void queueFailureRollsBackTheJournalAsWell() {
        jdbc.execute("CREATE TRIGGER reject_retirement BEFORE UPDATE ON manager_client_message_queue FOR EACH ROW BEGIN IF NEW.state='SUPERSEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rejection'; END IF; END");
        assertThatThrownBy(()->settlement.retire(claim,command)).isInstanceOf(RuntimeException.class);
        assertThat(journal()).isZero();assertThat(state()).isEqualTo("SENDING");
    }
}
