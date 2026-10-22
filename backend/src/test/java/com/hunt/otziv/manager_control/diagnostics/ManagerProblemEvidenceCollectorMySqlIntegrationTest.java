package com.hunt.otziv.manager_control.diagnostics;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Runs every collector query against column names/types from the actual application entity mappings. */
@Testcontainers
class ManagerProblemEvidenceCollectorMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_problem_collector").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private ManagerProblemEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        for (Class<?> type : List.of(ManagerDailyControl.class, ManagerDailyControlConcreteItem.class, CommonInvoice.class,
                CommonInvoiceOrder.class, ScheduledClientMessageState.class, ScheduledClientMessageAttempt.class,
                ClientChatMessage.class, ClientChatUnansweredItem.class, CommonBillingAccount.class, Company.class, Order.class,
                OrderStatus.class)) createFromEntityMapping(type);
        jdbc.execute("DROP TABLE IF EXISTS manager_client_message_queue");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/migration/V1_10_313__manager_client_message_queue.sql")).execute(jdbc.getDataSource());
        collector = new ManagerProblemEvidenceCollector(jdbc);
        jdbc.update("INSERT INTO manager_daily_controls(daily_control_id,manager_id) VALUES(1,2)");
    }

    @Test
    void collectsLiveInvoiceCountsAllAutomationCausesAndBoundedAttempts() {
        invoiceFixture();
        var evidence = collector.collect(10L);
        assertThat(evidence.managerId()).isEqualTo(2);
        assertThat(evidence.facts()).anySatisfy(f -> { assertThat(f.reference()).isEqualTo("invoice:189:status"); assertThat(f.value()).isEqualTo("COLLECTING"); });
        assertThat(evidence.facts()).anySatisfy(f -> { assertThat(f.field()).isEqualTo("orderIds"); assertThat(f.value()).isEqualTo("25734,25735"); });
        assertThat(evidence.facts()).anySatisfy(f -> { assertThat(f.field()).isEqualTo("orderCount"); assertThat(f.value()).isEqualTo("2"); });
        assertThat(evidence.facts()).anySatisfy(f -> { assertThat(f.field()).isEqualTo("readyCount"); assertThat(f.value()).isEqualTo("1"); });
        assertThat(evidence.facts().stream().filter(f -> f.field().equals("last_error_code")).map(ManagerProblemDiagnosticsService.Fact::value))
                .containsExactly("delivery_uncertain", "payment_instruction_failed");
        assertThat(evidence.facts().stream().filter(f -> f.subject().equals("automation_attempt") && f.field().equals("attempt_id"))).hasSize(5);
        assertThat(evidence.facts()).anyMatch(f -> f.field().equals("attempt_history_truncated"));
        assertThat(evidence.facts().toString()).doesNotContain("neverExportMe", "private-bank.test");
        assertThat(collector.sourcesForEntity("AUTOMATION_FAILURE", 40L)).contains(10L);
    }

    @Test
    void laterCardWinsAndStandaloneAutomationUsesItsOwnState() {
        invoiceFixture();
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status,title) VALUES(11,1,'invoice:189','COMMON_INVOICE',189,'RESOLVED','new state')");
        assertThat(collector.collect(10L).sourceTaskId()).isEqualTo(11L);
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status,title) VALUES(12,1,'automation:40','AUTOMATION_FAILURE',40,'OPEN','delivery')");
        assertThat(collector.collect(12L).facts()).anyMatch(f -> f.reference().equals("automation:40:state_status"));
        assertThat(collector.scan(10, 1)).containsExactly(12L);
    }

    @Test
    void backgroundScanVisitsDistinctLatestCasesAndDoesNotReviveClosedDailyCopies() {
        jdbc.update("INSERT INTO manager_daily_controls(daily_control_id,manager_id) VALUES(2,3)");
        jdbc.update("""
                INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status) VALUES
                  (10,1,'invoice:189','COMMON_INVOICE',189,'OPEN'),
                  (11,1,'invoice:189','COMMON_INVOICE_AUTOMATION',189,'DEFERRED'),
                  (12,1,'invoice:189','COMMON_INVOICE',189,'RESOLVED'),
                  (20,1,'chat:55','CLIENT_CHAT_UNANSWERED',55,'OPEN'),
                  (21,1,'chat:55','CLIENT_CHAT_AUDIT',55,'DEFERRED'),
                  (30,2,'chat:55','CLIENT_CHAT_AUDIT',55,'OPEN'),
                  (40,1,'manual:first','MANUAL',NULL,'OPEN'),
                  (41,1,'manual:first','MANUAL',NULL,'DEFERRED'),
                  (42,1,'manual:second','MANUAL',NULL,'OPEN')
                """);
        assertThat(collector.scan(0, 25)).containsExactly(21L, 30L, 41L, 42L);
        assertThat(collector.scan(0, 2)).containsExactly(21L, 30L);
        assertThat(collector.scan(30, 2)).containsExactly(41L, 42L);
        assertThat(collector.scan(42, 25)).isEmpty();
    }

    @Test
    void reassignedInvoiceAndAutomationDoNotLeakNewOwnersLogsThroughOldCard() {
        invoiceFixture();
        jdbc.update("UPDATE common_billing_accounts SET manager_id=99");
        jdbc.update("UPDATE orders SET order_manager=99");
        var unavailable = collector.collect(10L);
        assertThat(unavailable.facts()).anyMatch(f -> f.field().equals("availability"));
        assertThat(unavailable.facts()).noneMatch(f -> f.subject().equals("automation") || f.field().equals("amount_kopecks"));
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status,title) VALUES(12,1,'automation:40','AUTOMATION_FAILURE',40,'OPEN','delivery')");
        assertThat(collector.collect(12L).facts()).anyMatch(f -> f.field().equals("availability"));
        assertThat(collector.collect(12L).facts()).noneMatch(f -> f.subject().equals("automation"));
    }

    @Test
    void boundedSlicesKeepTrueAggregateCountsAndDeclareUnseenOrdersAndStates() {
        invoiceFixture();
        for (int i = 4; i <= 105; i++) jdbc.update("INSERT INTO common_invoice_orders(invoice_order_id,invoice_id,order_id,ready,paid,active_membership) VALUES(?,189,?,TRUE,FALSE,TRUE)", i, 30000 + i);
        for (int i = 42; i <= 82; i++) jdbc.update("INSERT INTO scheduled_client_message_state(state_id,order_id,scenario,state_status,last_error_code) VALUES(?,25735,'REVIEW_CHECK','ACTIVE','delivery_uncertain')", i);
        var facts = collector.collect(10L).facts();
        assertThat(facts).anyMatch(f -> f.field().equals("orderCount") && f.value().equals("104"));
        assertThat(facts).anyMatch(f -> f.field().equals("readyCount") && f.value().equals("103"));
        assertThat(facts).noneMatch(f -> f.reference().equals("invoice:189:orderIds"));
        assertThat(facts).anyMatch(f -> f.subject().equals("coverage") && f.field().equals("orderIds"));
        assertThat(facts).anyMatch(f -> f.field().equals("automation_states_truncated"));
        assertThat(facts.stream().filter(f -> f.subject().equals("invoice_order") && f.field().equals("order_id"))).hasSize(100);
        assertThat(facts.stream().filter(f -> f.subject().equals("automation") && f.field().equals("state_id"))).hasSize(40);
    }

    @Test
    void chatEvidenceIncludesRecordedQuestionReplyAndDeliveryMetadataWithManagerScope() {
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status,title) VALUES(10,1,'chat:55','CLIENT_CHAT_AUDIT',55,'OPEN','client question')");
        jdbc.update("""
                INSERT INTO client_chat_unanswered_items(id,manager_id,status,last_client_message_id,last_client_message_at,first_opened_at,
                  resolution_message_id,resolution_reply_text,last_message_text,resolution_type,reply_quality,audit_required)
                VALUES(55,2,'ANSWERED',70,NOW(),NOW(),71,'Проверю завтра','Когда будет готов заказ?','MANAGER_REPLY','PARTIAL',TRUE)
                """);
        jdbc.update("INSERT INTO client_chat_messages(id,manager_id,platform,direction,sender_role,message_text,message_at,routing_ambiguous) VALUES(70,2,'TELEGRAM','INBOUND','CLIENT','Когда будет готов заказ?',NOW(),FALSE),(71,2,'TELEGRAM','OUTBOUND','MANAGER','Проверю завтра api_key=secretAnswer',NOW(),FALSE)");
        var evidence = collector.collect(10L);
        assertThat(evidence.facts()).anyMatch(f -> f.reference().equals("message:70:recorded_text") && f.value().equals("Когда будет готов заказ?"));
        assertThat(evidence.facts()).anyMatch(f -> f.reference().equals("chat:55:audit_required") && f.value().equals("true"));
        assertThat(evidence.facts()).noneMatch(f -> f.subject().equals("coverage"));
        assertThat(evidence.facts().toString()).doesNotContain("secretAnswer");
        jdbc.update("UPDATE client_chat_messages SET message_text=? WHERE id=70", "Длинный вопрос клиента. ".repeat(150));
        var partial = collector.collect(10L);
        assertThat(partial.facts()).anyMatch(f -> f.reference().equals("message:70:recorded_text") && f.value().length() == 2001);
        assertThat(partial.facts()).anyMatch(f -> f.reference().equals("message:70:recorded_text:coverage") && f.field().equals("recorded_text_truncated"));
        jdbc.update("UPDATE client_chat_unanswered_items SET manager_id=99 WHERE id=55");
        assertThat(collector.collect(10L).facts()).anyMatch(f -> f.subject().equals("coverage"));
        assertThat(collector.collect(10L).facts()).noneMatch(f -> f.subject().equals("recorded_chat_message"));
    }

    @Test
    void orderEvidenceIncludesCurrentCycleAndManualDeliveryAcrossDaysWithoutExportingEnvelopes() {
        orderFixture();
        for (int i = 1; i <= 7; i++) {
            jdbc.update("INSERT INTO scheduled_client_message_attempts(attempt_id,state_id,attempt_status,error_code,attempted_at) VALUES(?,40,'UNKNOWN','operation_unknown',NOW())", i);
            jdbc.update("""
                    INSERT INTO manager_client_message_queue(operation_id,card_id,command_kind,envelope_ciphertext,state,error_code,created_at)
                    VALUES(?,10,'CONTROL','enc:do-not-export-secret',?,?,TIMESTAMPADD(MINUTE,?, '2026-10-22 11:00:00'))
                    """, "operation-" + i, i == 7 ? "UNKNOWN" : "SENT", i == 7 ? "operation_unknown" : null, i);
        }
        jdbc.update("INSERT INTO manager_daily_controls(daily_control_id,manager_id) VALUES(2,99)");
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status) VALUES(12,2,'order:9970','ORDER',9970,'OPEN')");
        jdbc.update("INSERT INTO manager_client_message_queue(operation_id,card_id,command_kind,envelope_ciphertext,state,error_code) VALUES('foreign-operation',12,'CONTROL','enc:hidden','FAILED','foreign_error')");

        var evidence = collector.collect(10L);
        assertThat(evidence.sourceTaskId()).isEqualTo(11L);
        assertThat(evidence.facts()).anyMatch(f -> f.reference().equals("order:9970:status") && f.value().equals("На проверке"));
        assertThat(evidence.facts()).anyMatch(f -> f.reference().equals("automation:40:delivery_status") && f.value().equals("UNKNOWN"));
        assertThat(evidence.facts()).noneMatch(f -> f.reference().startsWith("automation:41:"));
        assertThat(evidence.facts().stream().filter(f -> f.subject().equals("automation_attempt") && f.field().equals("attempt_id"))).hasSize(5);
        assertThat(evidence.facts().stream().filter(f -> f.subject().equals("manager_delivery") && f.field().equals("state")).map(ManagerProblemDiagnosticsService.Fact::value))
                .containsExactly("UNKNOWN", "SENT", "SENT", "SENT", "SENT");
        assertThat(evidence.facts()).anyMatch(f -> f.field().equals("manual_delivery_history_truncated"));
        assertThat(evidence.facts()).anyMatch(f -> f.field().equals("manual_delivery_cycle_unverified"));
        assertThat(evidence.facts()).anyMatch(f -> f.field().equals("business_resolution"));
        assertThat(evidence.facts().toString()).doesNotContain("do-not-export-secret", "foreign-operation", "foreign_error");
        assertThat(collector.sourcesForEntity("AUTOMATION_FAILURE", 40L)).contains(11L);
    }

    @Test
    void reassignedOrderDoesNotExposeAnotherManagersDeliveryLogs() {
        orderFixture();
        jdbc.update("UPDATE orders SET order_manager=99 WHERE order_id=9970");
        var facts = collector.collect(10L).facts();
        assertThat(facts).anyMatch(f -> f.reference().equals("order:9970:availability"));
        assertThat(facts).noneMatch(f -> List.of("order", "automation", "automation_attempt", "manager_delivery").contains(f.subject()));
    }

    @Test
    void missingOrderCycleDoesNotAttributeOldAutomationToTheCurrentProblem() {
        orderFixture();
        jdbc.update("UPDATE orders SET order_status_changed_at=NULL,order_changed=NULL,order_created=NULL WHERE order_id=9970");
        var facts = collector.collect(10L).facts();
        assertThat(facts).anyMatch(f -> f.reference().equals("order:9970:cycle_coverage"));
        assertThat(facts).noneMatch(f -> f.subject().equals("automation"));
    }

    private void orderFixture() {
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status) VALUES(10,1,'order:9970','ORDER',9970,'OPEN'),(11,1,'order:9970','ORDER',9970,'OPEN')");
        jdbc.update("INSERT INTO order_statuses(order_status_id,order_status_title) VALUES(3,'На проверке')");
        jdbc.update("INSERT INTO orders(order_id,order_manager,order_status,order_status_changed_at,client_message_generation) VALUES(9970,2,3,'2026-07-14 10:00:00',1)");
        jdbc.update("""
                INSERT INTO scheduled_client_message_state(state_id,order_id,scenario,target_key,state_status,delivery_status,last_error_code)
                VALUES(40,9970,'REVIEW_CHECK_REMINDER','order:9970:2026-07-14T10:00','ACTIVE','UNKNOWN','state_transaction_outcome_uncertain'),
                      (41,9970,'REVIEW_CHECK_REMINDER','order:9970:2026-06-01T10:00','DONE','SENT',NULL)
                """);
    }

    private void invoiceFixture() {
        jdbc.update("INSERT INTO manager_daily_control_concrete_items(control_entity_id,control_id,entity_key,entity_type,entity_id,item_status,title) VALUES(10,1,'invoice:189','COMMON_INVOICE_AUTOMATION',189,'DEFERRED','invoice')");
        jdbc.update("INSERT INTO common_billing_accounts(account_id,manager_id) VALUES(1,2)");
        jdbc.update("INSERT INTO common_invoices(invoice_id,account_id,status,amount_kopecks,paid_kopecks,last_error,updated_at) VALUES(189,1,'COLLECTING',300000,100000,'api_key=neverExportMe https://private-bank.test/payment',NOW())");
        jdbc.update("INSERT INTO orders(order_id,order_manager) VALUES(25734,2),(25735,2),(99,2)");
        jdbc.update("INSERT INTO common_invoice_orders(invoice_order_id,invoice_id,order_id,ready,paid,active_membership) VALUES(1,189,25734,TRUE,FALSE,TRUE),(2,189,25735,FALSE,FALSE,TRUE),(3,189,99,FALSE,FALSE,FALSE)");
        jdbc.update("INSERT INTO scheduled_client_message_state(state_id,order_id,scenario,state_status,last_error_code,consecutive_failures) VALUES(40,25735,'REVIEW_CHECK','ACTIVE','delivery_uncertain',3),(41,25734,'PAYMENT_REMINDER','ACTIVE','payment_instruction_failed',2)");
        for (int i = 1; i <= 8; i++) jdbc.update("INSERT INTO scheduled_client_message_attempts(attempt_id,state_id,attempt_status,error_code,error_message,attempted_at) VALUES(?,40,'FAILED','delivery_uncertain','api_key=neverExportMe',NOW())", i);
    }

    private void createFromEntityMapping(Class<?> entity) {
        String table = entity.getAnnotation(Table.class).name();
        jdbc.execute("DROP TABLE IF EXISTS `" + table + "`");
        List<String> columns = new ArrayList<>();
        for (var field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)
                    || Collection.class.isAssignableFrom(field.getType()) || Map.class.isAssignableFrom(field.getType())) continue;
            Column column = field.getAnnotation(Column.class);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            String name = join != null ? join.name() : column != null && !column.name().isBlank() ? column.name()
                    : field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            Class<?> type = field.getType();
            String sqlType = join != null ? "BIGINT" : type == boolean.class || type == Boolean.class ? "BOOLEAN"
                    : type == LocalDateTime.class ? "DATETIME(6)" : type == LocalDate.class ? "DATE"
                    : Number.class.isAssignableFrom(type) || type.isPrimitive() ? "BIGINT" : "TEXT";
            columns.add("`" + name + "` " + sqlType + (field.isAnnotationPresent(Id.class) ? " PRIMARY KEY" : " NULL"));
        }
        jdbc.execute("CREATE TABLE `" + table + "` (" + String.join(",", columns) + ")");
    }
}
