package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.*;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual repair, invoice-repair and outcome collaborators under Spring transaction advice, with
 * InnoDB writes and connection-identity assertions. JDBC-backed repository adapters isolate this
 * transaction contract; this is not a Hibernate mapping or real billing-provider acceptance test.
 */
@Testcontainers
class ManagerControlRepairTransactionMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_repair_transactions").withUsername("root")
            .withPassword(UUID.randomUUID().toString());

    private static final Principal PRINCIPAL = () -> "fixture-manager";
    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken("fixture-manager", "n/a", List.of());
    private final AtomicReference<Long> controlConnection = new AtomicReference<>();
    private final AtomicReference<Long> billingConnection = new AtomicReference<>();
    private final AtomicBoolean failAudit = new AtomicBoolean();
    private final AtomicBoolean throwProvider = new AtomicBoolean();
    private final AtomicReference<String> providerError = new AtomicReference<>("");
    private final AtomicInteger providerCalls = new AtomicInteger();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private ManagerControlRepairWorkflow workflow;
    private ManagerDailyControlConcreteItemRepository cards;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        controlConnection.set(null); billingConnection.set(null); failAudit.set(false);
        throwProvider.set(false); providerError.set(""); providerCalls.set(0);
        jdbc.execute("DROP TABLE IF EXISTS mc_repair_card,mc_repair_parent,mc_repair_control,mc_repair_event,mc_repair_billing,mc_repair_probe");
        jdbc.execute("CREATE TABLE mc_repair_card(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30),comment TEXT,episodes BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_repair_parent(id BIGINT PRIMARY KEY,status VARCHAR(30),action_type VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_repair_control(id BIGINT PRIMARY KEY,status VARCHAR(30),started DATETIME(6),active_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_repair_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,event_type VARCHAR(30),comment TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_repair_billing(id BIGINT AUTO_INCREMENT PRIMARY KEY,outcome VARCHAR(30)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_repair_probe(name VARCHAR(30)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO mc_repair_card VALUES(1,'OPEN',NULL,'original',0)");
        jdbc.update("INSERT INTO mc_repair_parent VALUES(2,'OPEN',NULL)");
        jdbc.update("INSERT INTO mc_repair_control VALUES(3,'RED',NULL,NULL)");

        cards = mock(ManagerDailyControlConcreteItemRepository.class);
        var items = mock(ManagerDailyControlItemRepository.class);
        var controls = mock(ManagerDailyControlRepository.class);
        var events = mock(ManagerDailyControlEventRepository.class);
        var invoices = mock(CommonInvoiceRepository.class);
        var billing = mock(CommonBillingService.class);
        var users = mock(UserService.class);
        var permissions = mock(ManagerPermissionService.class);
        var examples = mock(ManagerControlProblemExamples.class);
        when(examples.safe(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).trim());
        when(examples.limit(anyString(), anyInt())).thenAnswer(call -> {
            String text = ((String) call.getArgument(0)).trim();
            return text.substring(0, Math.min(text.length(), call.getArgument(1)));
        });
        when(cards.findById(1L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            controlConnection.set(connectionId());
            return Optional.of(readCard());
        });
        when(cards.save(any())).thenAnswer(call -> {
            assertControlTransactionResumed();
            ManagerDailyControlConcreteItem card = call.getArgument(0);
            jdbc.update("UPDATE mc_repair_card SET status=?,action_type=?,comment=?,episodes=? WHERE id=1",
                    card.getStatus().name(), card.getActionType().name(), card.getComment(), card.getResolvedEpisodeCount());
            return card;
        });
        when(cards.findByParentItem(any())).thenAnswer(call -> List.of(readCard()));
        when(items.findByControl(any())).thenAnswer(call -> List.of(readParent()));
        when(items.save(any())).thenAnswer(call -> {
            assertControlTransactionResumed();
            ManagerDailyControlItem item = call.getArgument(0);
            jdbc.update("UPDATE mc_repair_parent SET status=?,action_type=? WHERE id=2", item.getStatus().name(), item.getActionType().name());
            return item;
        });
        when(controls.save(any())).thenAnswer(call -> {
            assertControlTransactionResumed();
            ManagerDailyControl control = call.getArgument(0);
            jdbc.update("UPDATE mc_repair_control SET status=?,started=?,active_at=? WHERE id=3",
                    control.getStatus().name(), control.getStartedAt(), control.getLastActivityAt());
            return control;
        });
        when(events.save(any())).thenAnswer(call -> {
            assertControlTransactionResumed();
            // The failure occurs after all preceding outcome writes and an actual audit INSERT.
            assertThat(state()).isEqualTo("RESOLVED");
            assertThat(jdbc.queryForObject("SELECT status FROM mc_repair_control", String.class)).isEqualTo("GREEN");
            ManagerDailyControlEvent event = call.getArgument(0);
            jdbc.update("INSERT INTO mc_repair_event(actor_id,event_type,comment) VALUES(?,?,?)",
                    event.getActorUserId(), event.getEventType().name(), event.getComment());
            if (failAudit.get()) throw new IllegalStateException("fixture late audit failure");
            return event;
        });
        when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(true);
        User actor = new User(); actor.setId(5L);
        when(users.findByUserName(PRINCIPAL.getName())).thenReturn(Optional.of(actor));
        when(invoices.findByIdWithAccount(88L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            billingConnection.set(connectionId());
            assertThat(billingConnection.get()).isNotEqualTo(controlConnection.get());
            var invoice = new CommonInvoice(); invoice.setId(88L); invoice.setStatus(CommonInvoiceStatus.READY);
            return Optional.of(invoice);
        });
        CommonInvoiceDetailsResponse ready = details(CommonInvoiceStatus.READY, "");
        when(billing.invoice(88L)).thenReturn(ready);
        when(billing.sendInvoice(88L, true)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(connectionId()).isEqualTo(billingConnection.get());
            assertThat(jdbc.queryForObject("SELECT @@autocommit", Integer.class)).isEqualTo(1);
            assertThat(state()).isEqualTo("OPEN");
            assertThat(count("mc_repair_probe")).isZero(); // Caller work is not visible to suspended billing.
            providerCalls.incrementAndGet();
            jdbc.update("INSERT INTO mc_repair_billing(outcome) VALUES(?)",
                    throwProvider.get() || !providerError.get().isBlank() ? "ATTEMPT_FAILED" : "SENT");
            if (throwProvider.get()) throw new IllegalStateException("fixture provider failure");
            return details(providerError.get().isBlank() ? CommonInvoiceStatus.INVOICED : CommonInvoiceStatus.NEEDS_ATTENTION,
                    providerError.get());
        });

        var access = new ManagerControlAccessPolicy(mock(ManagerRepository.class), users, mock(ManagerAccessService.class), permissions);
        var lifecycle = new ManagerControlCardLifecycle(cards, items, events, mock(ManagerPerformanceService.class), mock(ManagerControlReadSnapshots.class));
        var lookup = mock(ManagerControlWorkerTaskLookup.class);
        when(lookup.userDisplayName(any())).thenReturn("");
        var presenter = new ManagerControlConcretePresenter(mock(WorkerRiskIncidentRepository.class), lookup,
                mock(ManagerControlInvoiceDiagnostics.class), mock(ScheduledClientMessageStateRepository.class),
                mock(CompanyRepository.class), new ManagerControlSlaPolicy(mock(AppSettingService.class)));
        var outcome = proxy(new ManagerControlRepairOutcome(examples, access, lifecycle, presenter, controls, cards));
        var invoiceRepair = proxy(new ManagerControlInvoiceRepairWorkflow(invoices, mock(CommonInvoiceOrderRepository.class),
                mock(CommonInvoicePublicationBlockerService.class), billing, mock(ManagerControlInvoiceDiagnostics.class)));
        workflow = proxy(new ManagerControlRepairWorkflow(mock(ManagerControlAutomationRepairWorkflow.class),
                mock(ManagerControlChatRepairWorkflow.class), outcome, examples, access, mock(ManagerControlClientMessageText.class),
                presenter, mock(ManagerControlOrderAutomationDiagnostics.class), mock(ScheduledClientMessageService.class),
                mock(ReviewRepository.class), mock(OrderRepository.class), mock(CompanyRepository.class),
                mock(OrderPaymentIntegrityService.class), invoiceRepair, mock(OrderPublicationApprovalService.class), cards));
        assertThat(List.of(workflow, invoiceRepair, outcome)).allMatch(AopUtils::isAopProxy);
    }

    @Test
    void requiredEntryCommitsOutcomeAfterIndependentBillingAndResumesTheOriginalConnection() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(workflow.repairConcreteItem(1L, PRINCIPAL, AUTH).itemStatus()).isEqualTo("RESOLVED");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(state()).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT episodes FROM mc_repair_card", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM mc_repair_parent", String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT status FROM mc_repair_control", String.class)).isEqualTo("GREEN");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM mc_repair_event", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT event_type FROM mc_repair_event", String.class)).isEqualTo("ITEM_RESOLVED");
        assertBillingCommitted("SENT");
    }

    @Test
    void callerRollbackRevertsCardParentControlAndAuditButKeepsIndependentBilling() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Long callerConnection = connectionId();
            jdbc.update("INSERT INTO mc_repair_probe VALUES('caller')");
            assertThat(workflow.repairConcreteItem(1L, PRINCIPAL, AUTH).itemStatus()).isEqualTo("RESOLVED");
            assertThat(controlConnection.get()).isEqualTo(callerConnection);
            assertControlTransactionResumed();
            assertThat(count("mc_repair_event")).isEqualTo(1);
            throw new IllegalStateException("fixture caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture caller rollback");
        assertUnresolved();
        assertBillingCommitted("SENT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnedFailureOrProviderExceptionCannotResolveTheCardAndResumesCaller(boolean exception) {
        throwProvider.set(exception);
        providerError.set(exception ? "" : "fixture send rejected");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO mc_repair_probe VALUES('caller')");
            try {
                workflow.repairConcreteItem(1L, PRINCIPAL, AUTH);
            } finally {
                assertControlTransactionResumed();
            }
        })).isInstanceOf(exception ? IllegalStateException.class : ResponseStatusException.class);
        verify(cards, never()).save(any());
        assertUnresolved();
        assertBillingCommitted("ATTEMPT_FAILED");
    }

    @Test
    void auditFailureAfterOutcomeWritesRollsBackResolutionWithoutUndoingBilling() {
        failAudit.set(true);
        assertThatThrownBy(() -> workflow.repairConcreteItem(1L, PRINCIPAL, AUTH))
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture late audit failure");
        verify(cards).save(any());
        assertUnresolved();
        assertBillingCommitted("SENT");
    }

    private void assertUnresolved() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(state()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT comment FROM mc_repair_card", String.class)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT episodes FROM mc_repair_card", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM mc_repair_parent", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM mc_repair_control", String.class)).isEqualTo("RED");
        assertThat(jdbc.queryForObject("SELECT started IS NULL AND active_at IS NULL FROM mc_repair_control", Boolean.class)).isTrue();
        assertThat(count("mc_repair_event")).isZero();
        assertThat(count("mc_repair_probe")).isZero();
    }

    private void assertBillingCommitted(String expected) {
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(count("mc_repair_billing")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM mc_repair_billing", String.class)).isEqualTo(expected);
    }

    private void assertControlTransactionResumed() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(connectionId()).isEqualTo(controlConnection.get());
    }

    private ManagerDailyControlConcreteItem readCard() {
        return jdbc.queryForObject("SELECT * FROM mc_repair_card WHERE id=1", (rs, row) -> {
            var card = new ManagerDailyControlConcreteItem();
            card.setId(1L); card.setEntityId(88L); card.setEntityType("COMMON_INVOICE"); card.setTitle("fixture invoice");
            card.setControl(control()); card.setParentItem(readParent());
            card.setStatus(ManagerDailyControlItemStatus.valueOf(rs.getString("status")));
            card.setComment(rs.getString("comment")); card.setResolvedEpisodeCount(rs.getLong("episodes"));
            return card;
        });
    }

    private ManagerDailyControlItem readParent() {
        return jdbc.queryForObject("SELECT * FROM mc_repair_parent WHERE id=2", (rs, row) -> {
            var item = new ManagerDailyControlItem(); item.setId(2L); item.setControl(control());
            item.setCount(1); item.setGroup(ManagerDailyControlGroup.ACTION); item.setSeverity(ManagerDailyControlSeverity.CRITICAL);
            item.setStatus(ManagerDailyControlItemStatus.valueOf(rs.getString("status")));
            return item;
        });
    }

    private ManagerDailyControl control() {
        var manager = new Manager(); manager.setId(4L); manager.setClientId("fixture-client");
        var control = new ManagerDailyControl(); control.setId(3L); control.setManager(manager); return control;
    }

    private Long connectionId() { return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); }
    private String state() { return jdbc.queryForObject("SELECT status FROM mc_repair_card", String.class); }
    private int count(String fixtureTable) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + fixtureTable, Integer.class); }

    private CommonInvoiceDetailsResponse details(CommonInvoiceStatus status, String error) {
        var summary = mock(CommonInvoiceSummaryResponse.class);
        when(summary.status()).thenReturn(status.name()); when(summary.lastError()).thenReturn(error);
        var details = mock(CommonInvoiceDetailsResponse.class); when(details.summary()).thenReturn(summary); return details;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
