package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.model.*;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.*;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.*;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.IllegalTransactionStateException;
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
 * Actual V301 ledger, InnoDB constraints/locks and Spring transaction proxies. JDBC adapters for
 * existing JPA cards/source isolate the delivery state machine; this is not a Hibernate mapping test.
 */
@Testcontainers
class ManagerControlClientReplyMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("manager_client_reply").withUsername("root").withPassword(UUID.randomUUID().toString());
    private static final Principal PRINCIPAL = () -> "fixture-manager";
    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken("fixture-manager", "n/a", List.of());
    private static final String MESSAGE = "Проверили результат\nСообщаем клиенту: готово ✅";
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<ClientMessageSendResult> sendResult = new AtomicReference<>();
    private final AtomicReference<Runnable> sendHook = new AtomicReference<>();
    private final AtomicReference<Runnable> proofHook = new AtomicReference<>();
    private final AtomicReference<WhatsAppOperationStatus> proof = new AtomicReference<>();
    private final AtomicBoolean failAudit = new AtomicBoolean();
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private ManagerClientReplyOperationRepository operations;
    private ManagerDailyControlConcreteItemRepository cards;
    private ClientChatUnansweredItemRepository sources;
    private ManagerDailyControlRepository controls;
    private ClientChatMessageSender sender;
    private WhatsAppService whatsapp;
    private ClientChatMessageTrackerService tracker;
    private ManagerControlAccessPolicy access;
    private ManagerControlCardLifecycle lifecycle;
    private ManagerControlConcretePresenter presenter;
    private ManagerPermissionService permissions;
    private ManagerRepository managers;
    private ManagerAccessService companyAccess;
    private ManagerControlClientReplyWorkflow workflow;

    @BeforeEach void setUp() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource); transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS manager_client_reply_operations,mc_reply_card,mc_reply_source,mc_reply_events,mc_reply_probe");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_301__manager_client_reply_operations.sql")).execute(dataSource);
        jdbc.execute("CREATE TABLE mc_reply_card(id BIGINT PRIMARY KEY,entity_type VARCHAR(40),status VARCHAR(30),action_type VARCHAR(30),comment TEXT,touched DATETIME(6),resolved DATETIME(6),follow_up DATETIME(6),automatic BOOLEAN,episodes BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_reply_source(id BIGINT PRIMARY KEY,company_id BIGINT,manager_id BIGINT,message_id BIGINT,message_at DATETIME(6),status VARCHAR(30),audit BOOLEAN,platform VARCHAR(30),chat_id VARCHAR(160)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_reply_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor BIGINT,comment TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE mc_reply_probe(name VARCHAR(40)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO mc_reply_card VALUES(1,'CLIENT_CHAT_UNANSWERED','OPEN',NULL,'original',NULL,NULL,NULL,FALSE,0),(2,'CLIENT_CHAT_UNANSWERED','OPEN',NULL,'next day',NULL,NULL,NULL,FALSE,0)");
        jdbc.update("INSERT INTO mc_reply_source VALUES(77,8,4,700,?,'OPEN',FALSE,'WHATSAPP','120000000000@g.us')", LocalDateTime.now().minusHours(1));
        sends.set(0); token.set(null); sendResult.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message"));
        sendHook.set(() -> {}); proofHook.set(() -> {}); proof.set(null); failAudit.set(false);
        operations = proxy(new ManagerClientReplyOperationRepository(jdbc));
        cards = mock(ManagerDailyControlConcreteItemRepository.class); sources = mock(ClientChatUnansweredItemRepository.class);
        controls = mock(ManagerDailyControlRepository.class); sender = mock(ClientChatMessageSender.class);
        whatsapp = mock(WhatsAppService.class); tracker = mock(ClientChatMessageTrackerService.class);
        permissions = mock(ManagerPermissionService.class); companyAccess = mock(ManagerAccessService.class);
        var users = mock(UserService.class); managers = mock(ManagerRepository.class);
        var items = mock(ManagerDailyControlItemRepository.class); var events = mock(ManagerDailyControlEventRepository.class);
        when(cards.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.of(readCard(call.getArgument(0), true)));
        when(sources.findByIdForUpdate(77L)).thenAnswer(call -> Optional.of(readSource(true)));
        when(cards.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ManagerDailyControlConcreteItem card = call.getArgument(0);
            jdbc.update("UPDATE mc_reply_card SET status=?,action_type=?,comment=?,touched=?,resolved=?,follow_up=?,automatic=?,episodes=? WHERE id=?",
                    card.getStatus().name(), card.getActionType() == null ? null : card.getActionType().name(), card.getComment(),
                    card.getLastManualTouchAt(), card.getResolvedAt(), card.getFollowUpAt(), card.isAutomaticResolution(),
                    card.getActionTakenEpisodeCount() + card.getResolvedEpisodeCount(), card.getId());
            return card;
        });
        when(controls.save(any())).thenAnswer(call -> call.getArgument(0));
        when(items.findByControl(any())).thenReturn(List.of());
        when(events.save(any())).thenAnswer(call -> {
            ManagerDailyControlEvent event = call.getArgument(0);
            jdbc.update("INSERT INTO mc_reply_events(actor,comment) VALUES(?,?)", event.getActorUserId(), event.getComment());
            if (failAudit.get()) throw new IllegalStateException("fixture audit failure");
            return event;
        });
        doAnswer(call -> { jdbc.update("UPDATE mc_reply_source SET status='ANSWERED' WHERE id=?", call.getArgument(0, Long.class)); return null; })
                .when(tracker).markConfirmedReply(anyLong(), anyString(), any(), anyString());
        doAnswer(call -> { jdbc.update("UPDATE mc_reply_source SET audit=FALSE WHERE id=?", call.getArgument(0, Long.class)); return null; })
                .when(tracker).markAuditReplySent(anyLong(), any(), anyString(), anyString());
        when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(true);
        User actor = new User(); actor.setId(5L); when(users.findByUserName(PRINCIPAL.getName())).thenReturn(Optional.of(actor));
        access = new ManagerControlAccessPolicy(managers, users, companyAccess, permissions);
        lifecycle = new ManagerControlCardLifecycle(cards, items, events, mock(ManagerPerformanceService.class));
        var lookup = mock(ManagerControlWorkerTaskLookup.class); when(lookup.userDisplayName(any())).thenReturn("");
        presenter = new ManagerControlConcretePresenter(mock(WorkerRiskIncidentRepository.class), lookup,
                mock(ManagerControlInvoiceDiagnostics.class), mock(ScheduledClientMessageStateRepository.class),
                mock(CompanyRepository.class), new ManagerControlSlaPolicy(mock(AppSettingService.class)));
        when(sender.sendToPlatformWithOperationId(any(), any(), any(), any(), any(), any(), anyString())).thenAnswer(call -> {
            sends.incrementAndGet(); token.set(call.getArgument(6));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT state FROM manager_client_reply_operations WHERE operation_token=?", String.class, token.get())).isEqualTo("PREPARED");
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT comment FROM mc_reply_card WHERE id=1", String.class)).endsWith(token.get());
            sendHook.get().run(); return sendResult.get();
        });
        when(whatsapp.getOperationStatus(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.getArgument(0, String.class)).isEqualTo("fixture-client");
            assertThat(call.getArgument(1, String.class)).isEqualTo(token.get());
            proofHook.get().run(); return proof.get();
        });
        restartWorkflow();
    }

    @Test void commitBeforeProviderAndIndependentFinalizationSurviveOuterRollback() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            jdbc.update("INSERT INTO mc_reply_probe VALUES('outer')");
            assertThat(reply(1L).contactText()).isEqualTo(MESSAGE);
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
            throw new IllegalStateException("outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("SUCCEEDED"); assertThat(sourceStatus()).isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("SELECT provider_message_id FROM manager_client_reply_operations", String.class)).isEqualTo("fixture-provider-message");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_reply_probe", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT episodes FROM mc_reply_card WHERE id=1", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT actor FROM mc_reply_events", Long.class)).containsExactly(5L);
        assertThatThrownBy(() -> reply(1L)).isInstanceOf(ResponseStatusException.class); assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"operation_unknown", "operation_pending", "running", "result_not_durable"})
    void unknownBlocksSameTextOtherTextAndNextDailyCardAcrossRestart(String code) {
        sendResult.set(ClientMessageSendResult.failed(code, "fixture")); conflictReply(); String originalToken = token.get();
        assertThat(state()).isEqualTo("UNKNOWN"); restartWorkflow();
        assertThatThrownBy(() -> reply(1L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> workflow.reply(2L, new ManagerControlClientReplyRequest("Другой ответ"), PRINCIPAL, AUTH)).isInstanceOf(ResponseStatusException.class);
        assertThat(sends.get()).isEqualTo(1); assertThat(token.get()).isEqualTo(originalToken); assertThat(sourceStatus()).isEqualTo("OPEN");
    }

    @Test void knownUnsentRetryIsExplicitAndKeepsTheSameOperationIdentity() {
        sendResult.set(ClientMessageSendResult.failed("gateway_not_ready", "fixture")); conflictReply();
        String original = token.get(); assertThat(state()).isEqualTo("FAILED_KNOWN"); assertThat(comment()).isEqualTo("original");
        restartWorkflow(); sendResult.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message")); reply(1L);
        assertThat(token.get()).isEqualTo(original); assertThat(sends.get()).isEqualTo(2); assertThat(state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_reply_operations", Integer.class)).isEqualTo(1);
    }

    @Test void stalePreparationBecomesUnknownAndDoesNotAuthorizeAnotherSend() {
        unknown(); jdbc.update("UPDATE manager_client_reply_operations SET state='PREPARED',prepared_at=?", LocalDateTime.now().minusMinutes(16));
        jdbc.update("UPDATE mc_reply_card SET comment=? WHERE id=1", "client_reply_delivery_prepared:" + token.get());
        restartWorkflow(); conflictReply(); assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sends.get()).isEqualTo(1);
    }

    @Test void concurrentDailyCardsShareOneUnresolvedSourceFence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        sendHook.set(() -> { entered.countDown(); try { assertThat(release.await(15, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reply(1L));
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> assertThatThrownBy(() -> reply(2L)).isInstanceOf(ResponseStatusException.class)).get(10, TimeUnit.SECONDS);
                assertThat(sends.get()).isEqualTo(1);
            } finally { release.countDown(); }
            first.get(15, TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("SUCCEEDED");
    }

    @Test void lateAuditFailureRollsBackSourceAndSuccessThenCommitsUnknownFence() {
        failAudit.set(true); conflictReply(); assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mc_reply_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT episodes FROM mc_reply_card WHERE id=1", Long.class)).isZero();
    }

    @Test void nominalSuccessWithoutProviderMessageIdRemainsUnknown() {
        sendResult.set(ClientMessageSendResult.sent("WhatsApp")); conflictReply();
        assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN");
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test void newInboundDuringProviderCannotBeClosedByOldReply() {
        sendHook.set(this::newInbound); conflictReply(); assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT message_id FROM mc_reply_source", Long.class)).isEqualTo(701L);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void replacedCardTokenCannotBeOverwrittenByLateSuccessOrKnownFailure(boolean success) {
        String replacement = "client_reply_delivery_prepared:" + UUID.randomUUID();
        sendHook.set(() -> jdbc.update("UPDATE mc_reply_card SET comment=? WHERE id=1", replacement));
        if (!success) sendResult.set(ClientMessageSendResult.failed("gateway_not_ready", "fixture"));
        conflictReply(); assertThat(comment()).isEqualTo(replacement); assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN");
    }

    @Test void persistedSnapshotRecoversConfirmedDeliveryAfterProcessRestartWithoutPost() {
        unknown(); restartWorkflow(); successProof(); var result = reconcile();
        assertThat(result.state()).isEqualTo("SUCCEEDED"); assertThat(result.sourceApplied()).isTrue();
        assertThat(result.messageId()).isEqualTo("fixture-provider-message"); assertThat(sourceStatus()).isEqualTo("ANSWERED");
        assertThat(sends.get()).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT resolution_reason FROM manager_client_reply_operations", String.class)).isEqualTo("Проверка результата по операции");
        assertThat(reconcile()).isEqualTo(result); verify(whatsapp, times(1)).getOperationStatus(any(), any());
    }

    @Test void recoveryOfStalePreparationCommitsUnknownEvenIfEvidenceIsAbsent() {
        unknown(); jdbc.update("UPDATE manager_client_reply_operations SET state='PREPARED',prepared_at=?", LocalDateTime.now().minusMinutes(16));
        jdbc.update("UPDATE mc_reply_card SET comment=? WHERE id=1", "client_reply_delivery_prepared:" + token.get());
        proof.set(new WhatsAppOperationStatus(token.get(), "NOT_FOUND", null, null));
        restartWorkflow(); assertThatThrownBy(this::reconcile).isInstanceOf(ResponseStatusException.class);
        assertThat(state()).isEqualTo("UNKNOWN"); assertThat(comment()).isEqualTo("client_reply_delivery_unknown:" + token.get());
        successProof(); assertThat(reconcile().sourceApplied()).isTrue(); assertThat(sends.get()).isEqualTo(1);
    }

    @Test void freshPreparationCannotBeResolvedWhileOriginalProviderMayStillBeRunning() {
        unknown(); jdbc.update("UPDATE manager_client_reply_operations SET state='PREPARED'"); successProof();
        assertThatThrownBy(this::reconcile).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(whatsapp); assertThat(state()).isEqualTo("PREPARED");
    }

    @Test void auditReplyRecoveryPreservesResolvedStatusAndCapturedActor() {
        jdbc.update("UPDATE mc_reply_card SET entity_type='CLIENT_CHAT_AUDIT' WHERE id=1");
        jdbc.update("UPDATE mc_reply_source SET status='ANSWERED',audit=TRUE");
        unknown(); restartWorkflow(); successProof(); assertThat(reconcile().sourceApplied()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM mc_reply_card WHERE id=1", String.class)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT audit FROM mc_reply_source", Boolean.class)).isFalse();
        verify(tracker).markAuditReplySent(77L, 5L, MESSAGE, "WhatsApp"); verify(tracker, never()).markConfirmedReply(anyLong(), anyString(), any(), anyString());
    }

    @Test void oldCardOwnershipDoesNotGrantAccessToReassignedMessage() {
        unknown(); when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(false);
        when(permissions.hasRole(AUTH, "MANAGER")).thenReturn(true);
        when(managers.findByUserId(5L)).thenReturn(Optional.of(manager(4L)));
        jdbc.update("UPDATE mc_reply_source SET manager_id=44"); successProof();
        assertThatThrownBy(this::reconcile).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reply(2L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(whatsapp); assertThat(sends.get()).isEqualTo(1); assertThat(state()).isEqualTo("UNKNOWN");
    }

    @Test void confirmedOldReplyReleasesOnlyItsFenceAndLeavesNewInboundOpen() {
        unknown(); newInbound(); restartWorkflow(); successProof(); var resolved = reconcile();
        assertThat(resolved.sourceApplied()).isFalse(); assertThat(sourceStatus()).isEqualTo("OPEN"); assertThat(comment()).isEqualTo("original");
        assertThat(state()).isEqualTo("SUCCEEDED"); assertThat(sends.get()).isEqualTo(1);
        sendResult.set(ClientMessageSendResult.sent("WhatsApp", "fixture-provider-message")); reply(1L); assertThat(sends.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_reply_operations", Integer.class)).isEqualTo(2);
    }

    @Test void sourceGenerationChangesWhileProofIsReadCannotBeClosed() {
        unknown(); successProof(); proofHook.set(this::newInbound); assertThat(reconcile().sourceApplied()).isFalse();
        assertThat(sourceStatus()).isEqualTo("OPEN"); assertThat(state()).isEqualTo("SUCCEEDED");
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "NOT_FOUND", "RUNNING", "wrong-token", "wrong-envelope", "missing-message", "missing-envelope"})
    void onlyMatchingCompleteProviderEvidenceCanReleaseQuarantine(String kind) {
        unknown(); successProof(); var ok = proof.get();
        proof.set(new WhatsAppOperationStatus(kind.equals("wrong-token") ? UUID.randomUUID().toString() : token.get(),
                List.of("UNKNOWN", "NOT_FOUND", "RUNNING").contains(kind) ? kind : "SUCCEEDED",
                kind.equals("missing-message") ? null : ok.messageId(),
                kind.equals("missing-envelope") ? null : kind.equals("wrong-envelope") ? "0".repeat(64) : ok.envelopeHash()));
        assertThatThrownBy(this::reconcile).isInstanceOf(ResponseStatusException.class);
        assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sends.get()).isEqualTo(1); assertThat(sourceStatus()).isEqualTo("OPEN");
    }

    @Test void recoveryFailureRollsBackProofTransitionAndSourceTogether() {
        unknown(); successProof(); failAudit.set(true); assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN"); assertThat(comment()).endsWith(token.get());
        failAudit.set(false); assertThat(reconcile().sourceApplied()).isTrue(); assertThat(sends.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-role", "foreign-company", "foreign-manager"})
    void currentPermissionChangesPreventPrepareAndRecoveryProviderLookup(String change) {
        unknown(); successProof();
        if (change.equals("foreign-company")) doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(companyAccess).requireCompanyAccess(anyLong(), eq(AUTH));
        else {
            when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(false);
            if (change.equals("foreign-manager")) when(permissions.hasRole(AUTH, "MANAGER")).thenReturn(true);
        }
        assertThatThrownBy(this::reconcile).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reply(2L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(whatsapp); assertThat(sends.get()).isEqualTo(1); assertThat(state()).isEqualTo("UNKNOWN");
    }

    @Test void permissionRevokedDuringProofReadPreventsFinalization() {
        unknown(); successProof(); proofHook.set(() -> doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(companyAccess).requireCompanyAccess(anyLong(), eq(AUTH)));
        assertThatThrownBy(this::reconcile).isInstanceOf(ResponseStatusException.class); assertThat(state()).isEqualTo("UNKNOWN");
    }

    @Test void sourceReassignmentDuringSendCannotFinalizeUnderOldCardPermission() {
        sendHook.set(() -> { jdbc.update("UPDATE mc_reply_source SET manager_id=44");
            when(permissions.hasRole(AUTH, "ADMIN")).thenReturn(false); });
        conflictReply(); assertThat(state()).isEqualTo("UNKNOWN"); assertThat(sourceStatus()).isEqualTo("OPEN");
    }

    @Test void operationRepositoryRequiresTransactionAndDatabaseRejectsSecondUnresolvedSource() {
        assertThatThrownBy(() -> operations.findBlockingForUpdate(77L)).isInstanceOf(IllegalTransactionStateException.class);
        unknown();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                operations.create(77L, 2L, "a".repeat(64), LocalDateTime.now(), "{}")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manager_client_reply_operations", Integer.class)).isEqualTo(1);
    }

    @Test void otherChannelsKeepExplicitManualQuarantineWithoutInventedProviderProof() {
        jdbc.update("UPDATE mc_reply_source SET platform='TELEGRAM',chat_id='123456'"); unknown();
        assertThatThrownBy(this::reconcile).isInstanceOf(ResponseStatusException.class); verifyNoInteractions(whatsapp);
        assertThat(state()).isEqualTo("UNKNOWN");
    }

    private void unknown() { sendResult.set(ClientMessageSendResult.failed("operation_unknown", "fixture")); conflictReply(); }
    private void conflictReply() { assertThatThrownBy(() -> reply(1L)).isInstanceOf(ResponseStatusException.class); }
    private ManagerControlConcreteItemResponse reply(Long id) { return workflow.reply(id, new ManagerControlClientReplyRequest(MESSAGE), PRINCIPAL, AUTH); }
    private ManagerClientReplyResolutionResponse reconcile() { return workflow.reconcile(1L, token.get(), new ManagerClientReplyResolutionRequest("Проверка результата по операции"), PRINCIPAL, AUTH); }
    private void successProof() { proof.set(new WhatsAppOperationStatus(token.get(), "SUCCEEDED", "fixture-provider-message",
            WhatsAppOperationEnvelope.groupHash("fixture-client", "120000000000@g.us", MESSAGE))); }
    private void newInbound() { jdbc.update("UPDATE mc_reply_source SET message_id=701,message_at=?,status='OPEN'", LocalDateTime.now()); }
    private void restartWorkflow() { workflow = proxy(new ManagerControlClientReplyWorkflow(proxy(new ManagerControlTransactionRunner()), cards,
            controls, sources, operations, sender, whatsapp, tracker, access, lifecycle, presenter)); }
    private String state() { return jdbc.queryForObject("SELECT state FROM manager_client_reply_operations WHERE operation_token=?", String.class, token.get()); }
    private String sourceStatus() { return jdbc.queryForObject("SELECT status FROM mc_reply_source", String.class); }
    private String comment() { return jdbc.queryForObject("SELECT comment FROM mc_reply_card WHERE id=1", String.class); }
    private ManagerDailyControlConcreteItem readCard(Long id, boolean locked) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return jdbc.queryForObject("SELECT * FROM mc_reply_card WHERE id=?" + (locked ? " FOR UPDATE" : ""), (rs, row) -> {
            var card = new ManagerDailyControlConcreteItem(); card.setId(id); card.setEntityId(77L); card.setEntityType(rs.getString("entity_type"));
            card.setTitle("fixture client question"); var control = new ManagerDailyControl(); control.setId(3L); control.setManager(manager(4L)); card.setControl(control);
            card.setStatus(ManagerDailyControlItemStatus.valueOf(rs.getString("status")));
            String action = rs.getString("action_type"); card.setActionType(action == null ? null : ManagerDailyControlActionType.valueOf(action));
            card.setComment(rs.getString("comment")); card.setLastManualTouchAt(time(rs.getTimestamp("touched")));
            card.setResolvedAt(time(rs.getTimestamp("resolved"))); card.setFollowUpAt(time(rs.getTimestamp("follow_up")));
            card.setAutomaticResolution(rs.getBoolean("automatic")); card.setActionTakenEpisodeCount(rs.getLong("episodes")); return card;
        }, id);
    }
    private ClientChatUnansweredItem readSource(boolean locked) {
        return jdbc.queryForObject("SELECT * FROM mc_reply_source WHERE id=77" + (locked ? " FOR UPDATE" : ""), (rs, row) -> {
            var item = new ClientChatUnansweredItem(); item.setId(77L); item.setPlatform(ClientChatPlatform.valueOf(rs.getString("platform")));
            item.setChatId(rs.getString("chat_id")); item.setManager(manager(rs.getLong("manager_id")));
            var company = new Company(); company.setId(rs.getLong("company_id")); company.setTitle("fixture company"); item.setCompany(company);
            var message = new ClientChatMessage(); message.setId(rs.getLong("message_id")); item.setLastClientMessage(message);
            item.setLastClientMessageAt(time(rs.getTimestamp("message_at"))); item.setStatus(ClientChatUnansweredStatus.valueOf(rs.getString("status")));
            item.setAuditRequired(rs.getBoolean("audit")); return item;
        });
    }
    private Manager manager(Long id) { var manager = new Manager(); manager.setId(id); manager.setClientId("fixture-client"); return manager; }
    private static LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
