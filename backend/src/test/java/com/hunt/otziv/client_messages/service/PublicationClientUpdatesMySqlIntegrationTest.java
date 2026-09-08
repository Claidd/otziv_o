package com.hunt.otziv.client_messages.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.common_billing.api.PublicationInvoiceCompletion;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.*;
import com.hunt.otziv.p_products.status.service.*;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService.PreparedPublicationProgress;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual migrations, publication/checker/invoice scheduler, outbox and transport fence.
 * JPA repositories use JDBC fixture adapters; provider and unrelated business owners are mocks. */
@Testcontainers
class PublicationClientUpdatesMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
        "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("publication_handoff").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc; DataSourceTransactionManager manager; TransactionTemplate tx;
    OrderPublicationOutbox outbox; OrderPublicationOutboxWorker worker;
    OrderNotificationOccurrences occurrences; OrderStatusNotificationService notifications;
    ClientMessageOperationFence fence; ClientChatMessageSender sender;
    OrderRepository orders; ReviewRepository reviews; CompanyRepository companies;
    OrderStatusCheckerService checker; OrderService publication;
    com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService recovery;
    TelegramService telegram; AppSettingService settings; PublicationInvoiceCompletion billing;
    AtomicBoolean enabled = new AtomicBoolean(true), companyEnabled = new AtomicBoolean(true), failCommit = new AtomicBoolean();
    volatile Runnable preferenceRead = () -> {};

    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        for (String table : List.of("client_message_operation_resolutions","client_message_operations","order_publication_client_updates",
                "order_client_message_occurrences","payment_route_change_notification_outbox","fixture_reviews","fixture_invoices","orders"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,counter INT NOT NULL DEFAULT 0,amount INT NOT NULL DEFAULT 3,status VARCHAR(80) DEFAULT 'Публикация',status_changed_at DATETIME(6) NOT NULL DEFAULT '2026-09-08 10:00:00') ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE fixture_reviews(id BIGINT PRIMARY KEY,order_id BIGINT,published BOOLEAN DEFAULT FALSE,published_at DATETIME(6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE fixture_invoices(id BIGINT AUTO_INCREMENT PRIMARY KEY,target_key VARCHAR(180) UNIQUE,status VARCHAR(16),delivery_token VARCHAR(180),delivery_status VARCHAR(32)) ENGINE=InnoDB");
        for (String migration : List.of("279__payment_route_change_notification_outbox","304__client_message_operation_fence","305__order_notification_occurrences","310__publication_client_update_outbox"))
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_" + migration + ".sql")).execute(data);
        jdbc.update("INSERT INTO orders(order_id) VALUES(7)");
        jdbc.update("INSERT INTO fixture_reviews(id,order_id) VALUES(71,7),(72,7),(73,7)");
        manager = new DataSourceTransactionManager(data) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                if (failCommit.getAndSet(false)) throw new TransactionSystemException("fixture commit refused");
                super.doCommit(status);
            }
        };
        manager.setRollbackOnCommitFailure(true); tx = new TransactionTemplate(manager);
        occurrences = proxy(new OrderNotificationOccurrences(jdbc));
        outbox = proxy(new OrderPublicationOutbox(jdbc,new ObjectMapper(),occurrences));
        fence = proxy(new ClientMessageOperationFence(proxy(new ClientMessageOperationStore(jdbc))));
        orders = mock(OrderRepository.class);
        when(orders.existsById(anyLong())).thenAnswer(call -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE order_id=?",Integer.class,call.getArgument(0,Long.class)) > 0);
        when(orders.findByIdForCounterUpdate(anyLong())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return jdbc.query("SELECT order_id,counter,amount,status,status_changed_at FROM orders WHERE order_id=? FOR UPDATE",
                (rs,n) -> {var order=order(rs.getLong(1),rs.getInt(2),rs.getInt(3),rs.getString(4));
                    order.setStatusChangedAt(rs.getTimestamp(5).toLocalDateTime());return order;},call.getArgument(0,Long.class)).stream().findFirst();
        });
        when(orders.save(any(Order.class))).thenAnswer(call -> { Order order=call.getArgument(0);
            jdbc.update("UPDATE orders SET counter=?,status=?,status_changed_at=? WHERE order_id=?",order.getCounter(),order.getStatus().getTitle(),order.getStatusChangedAt(),order.getId());return order; });
        settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenAnswer(call -> enabled.get());
        when(settings.getBoolean(anyString(),anyBoolean())).thenAnswer(call -> enabled.get());
        when(settings.getString(anyString(),anyString())).thenAnswer(call -> call.getArgument(1));
        when(settings.getInt(anyString(),anyInt())).thenAnswer(call -> call.getArgument(1));
        companies = mock(CompanyRepository.class);
        when(companies.findById(17L)).thenAnswer(call -> {preferenceRead.run();return Optional.of(company());});
        telegram = mock(TelegramService.class);
        when(telegram.sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList())).thenReturn(Optional.of(77));
        sender = construct(ClientChatMessageSender.class,Map.of(TelegramService.class,telegram,ClientMessageOperationFence.class,fence,
            PublicationProgressPreferenceService.class,proxy(new PublicationProgressPreferenceService(companies))));
        notifications = construct(OrderStatusNotificationService.class,Map.of(OrderRepository.class,orders,
            OrderNotificationOccurrences.class,occurrences,ClientMessageDelivery.class,sender));
        var stateRepository = invoiceRepository();
        var slots = mock(ClientMessageSlotPlanner.class);
        when(slots.nextAllowedAt(any(LocalDateTime.class),any())).thenAnswer(call -> call.getArgument(0));
        var retry = proxy(new PaymentInvoiceRetryScheduler(stateRepository,settings,slots));
        var statuses = mock(OrderStatusService.class);
        when(statuses.getOrderStatusByTitle(anyString())).thenAnswer(call -> status(call.getArgument(0)));
        recovery = mock(com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService.class);
        checker = proxy(construct(OrderStatusCheckerServiceImpl.class,Map.of(OrderRepository.class,orders,
            PaymentInvoiceRetryScheduler.class,retry,AppSettingService.class,settings,OrderStatusService.class,statuses,
            com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService.class,recovery)));
        billing = mock(PublicationInvoiceCompletion.class);
        when(billing.finalizePublishedInvoiceForOrder(anyLong())).thenReturn(true);
        worker = worker(outbox,sender,notifications);
        reviews = reviewRepository();
        publication = proxy(construct(OrderServiceImpl.class,Map.of(OrderRepository.class,orders,ReviewRepository.class,reviews,
            OrderStatusCheckerService.class,checker,OrderStatusNotificationService.class,notifications,
            OrderPublicationOutbox.class,outbox,AppSettingService.class,settings)));
    }

    @Test void realPublicationCommitsIntentAndCounterTogetherWithoutProviderAndOuterRollbackLeavesNothing() throws Exception {
        publication.changeStatusAndOrderCounter(71L);
        assertThat(number("SELECT counter FROM orders")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_client_message_occurrences")).isEqualTo(1);
        assertThat(text("SELECT delivery_state FROM order_publication_client_updates")).isEqualTo("READY");
        verifyNoInteractions(telegram);
        tx.executeWithoutResult(status -> {publish(72);status.setRollbackOnly();});
        assertThat(number("SELECT counter FROM orders")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_client_message_occurrences")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM fixture_reviews WHERE published=TRUE")).isEqualTo(1);
        publication.changeStatusAndOrderCounter(71L);
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates")).isEqualTo(1);
    }

    @Test void failedDurableEnqueueRollsBackTheActualPublicationAndOccurrence() {
        jdbc.execute("CREATE TRIGGER reject_intent BEFORE INSERT ON order_publication_client_updates FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture refused intent'");
        assertThatThrownBy(() -> publication.changeStatusAndOrderCounter(71L)).isInstanceOf(RuntimeException.class);
        assertThat(number("SELECT counter FROM orders")).isZero();
        assertThat(number("SELECT COUNT(*) FROM fixture_reviews WHERE published=TRUE")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_client_message_occurrences")).isZero();
    }

    @Test void restartedWorkerUsesFrozenFirstMessageRouteAndKeyboardAndReleasesBusinessLocks() throws Exception {
        publish(71); long id = first(); String operation = operation(id);
        when(companies.findById(17L)).thenAnswer(call -> {var changed=company();changed.setTelegramGroupChatId(-999L);changed.setTitle("Changed");return Optional.of(changed);});
        when(settings.getString(anyString(),anyString())).thenReturn("Changed template");
        when(telegram.sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.getArgument(0,Long.class)).isEqualTo(-10017L);
            assertThat(call.getArgument(1,String.class)).isEqualTo("Fixture. Опубликован новый отзыв 1 / 3.\n\nНе хотите получать сообщение о каждом опубликованном отзыве?\nНажмите кнопку ниже.");
            List<List<InlineKeyboardButton>> keys=call.getArgument(3);
            assertThat(keys).hasSize(1); assertThat(keys.getFirst().getFirst().getCallbackData()).isEqualTo("publication_progress:disable:17");
            assertThat(keys.getFirst().getFirst().getText()).isEqualTo("Отключить уведомления");
            tx.executeWithoutResult(status -> {jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=7 FOR UPDATE",Long.class);
                jdbc.queryForObject("SELECT id FROM order_publication_client_updates WHERE id=? FOR UPDATE",Long.class,id);});
            return Optional.of(77);
        });
        worker(outbox,sender,notifications).deliver(id);
        assertThat(state(id)).isEqualTo("SENT"); assertThat(operation(id)).isEqualTo(operation);
        assertThat(jdbc.queryForObject("SELECT confirmed FROM order_client_message_occurrences",Boolean.class)).isTrue();
        worker.deliver(id); verify(telegram,times(1)).sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList());
    }

    @Test void crashAfterClaimCannotSendAfterExpiryAndPositiveReceiptRecoversWithFlagsOff() {
        publish(71); long id=first(); String operation=operation(id);
        outbox.claim(id,true).orElseThrow(); // process stops before the provider call
        due(id); worker.deliver(id);
        assertThat(state(id)).isEqualTo("UNKNOWN"); verifyNoInteractions(telegram);
        // External proof is represented by the existing durable sender fence receipt.
        fence.execute(operation,"TELEGRAM","-10017","frozen fixture receipt",() -> ClientMessageSendResult.sent("Telegram","88"));
        enabled.set(false); due(id); worker.deliver(id);
        assertThat(state(id)).isEqualTo("SENT"); verifyNoInteractions(telegram);
    }

    @Test void providerSuccessBeforeOutboxCommitCrashRecoversOriginalFenceWithoutSecondSend() {
        publish(71); long id=first();
        var failFinalizeJdbc=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                int changed=super.update(sql,args);
                if(sql.contains("SET delivery_state=?") && "SENT".equals(args[0])) failCommit.set(true);
                return changed;
            }
        };
        var unstable=proxy(new OrderPublicationOutbox(failFinalizeJdbc,new ObjectMapper(),occurrences));
        worker(unstable,sender,notifications).deliver(id);
        assertThat(state(id)).isEqualTo("DISPATCHING");
        assertThat(fence.lookup(operation(id)).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT confirmed FROM order_client_message_occurrences",Boolean.class)).isFalse();
        due(id); worker.deliver(id); assertThat(state(id)).isEqualTo("SENT");
        verify(telegram,times(1)).sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList());
    }

    @Test void concurrentWorkersCannotReorderProgressAndUnknownNeverBlocksCompletion() throws Exception {
        publish(71); publish(72); publish(73); List<Long> ids=allIds();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(telegram.sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList())).thenAnswer(call -> {
            entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return Optional.empty();});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var running=pool.submit(() -> worker.deliver(ids.getFirst()));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            worker(outbox,sender,notifications).deliver(ids.get(1)); worker.deliver(ids.getFirst());
            assertThat(outbox.dueDeliveries(20)).isEmpty();
            worker.completeOrder(ids.get(2));
            assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
            assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates WHERE completion_done=TRUE")).isEqualTo(3);
            release.countDown();running.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(state(ids.getFirst())).isEqualTo("UNKNOWN");
        assertThat(state(ids.get(1))).isEqualTo("READY");assertThat(state(ids.get(2))).isEqualTo("SKIPPED");
        verify(telegram,times(1)).sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),isNull(),anyList());
    }

    @Test void accumulatedCompletionEventsCannotRearmAnAlreadyDeliveredInvoice() {
        publish(71);publish(72);publish(73);List<Long> backlog=allIds();
        worker.completeOrder(backlog.getFirst());
        jdbc.update("UPDATE fixture_invoices SET status='DONE',delivery_status='SUCCEEDED',delivery_token='original-receipt-identity'");
        for(long id:backlog) worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
        assertThat(text("SELECT status FROM fixture_invoices")).isEqualTo("DONE");
        assertThat(text("SELECT delivery_token FROM fixture_invoices")).isEqualTo("original-receipt-identity");
        assertThat(text("SELECT delivery_status FROM fixture_invoices")).isEqualTo("SUCCEEDED");
    }

    @Test void delayedCompletionPreservesLaterManualTerminalStatuses() {
        publish(71);publish(72);publish(73);long id=first();
        for(String terminal:List.of("Не оплачено","Бан","Архив","Оплачено","Выставлен счет","Напоминание","Коррекция")) {
            jdbc.update("UPDATE orders SET status=?",terminal);
            jdbc.update("UPDATE order_publication_client_updates SET completion_done=FALSE,completion_next_attempt_at=CURRENT_TIMESTAMP(6)");
            worker.completeOrder(id);
            assertThat(text("SELECT status FROM orders")).isEqualTo(terminal);
            assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isZero();
        }
    }

    @Test void invoiceSentByAnotherCompletionPathBeforeFirstDrainCannotBeRearmed() {
        publish(71);publish(72);publish(73);long id=first();
        tx.executeWithoutResult(status -> {
            try {checker.checkAndMarkOrderCompleted(orders.findByIdForCounterUpdate(7L).orElseThrow());}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
        jdbc.update("UPDATE fixture_invoices SET status='DONE',delivery_token='already-sent-identity',delivery_status='SUCCEEDED'");
        String originalTarget=text("SELECT target_key FROM fixture_invoices");
        worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
        assertThat(text("SELECT target_key FROM fixture_invoices")).isEqualTo(originalTarget);
        assertThat(text("SELECT status FROM fixture_invoices")).isEqualTo("DONE");
        assertThat(text("SELECT delivery_token FROM fixture_invoices")).isEqualTo("already-sent-identity");
    }

    @Test void failedCompletionMarkerRollsBackInvoiceSchedulingAndRetryCoversCurrentPublications() {
        publish(71);publish(72);publish(73);long id=first();
        jdbc.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON order_publication_client_updates FOR EACH ROW BEGIN IF NEW.completion_done=TRUE AND OLD.completion_done=FALSE THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture completion marker'; END IF; END");
        worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates WHERE completion_done=TRUE")).isZero();
        jdbc.execute("DROP TRIGGER reject_completion");jdbc.update("UPDATE order_publication_client_updates SET completion_next_attempt_at=CURRENT_TIMESTAMP(6)");
        worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates WHERE completion_done=TRUE")).isEqualTo(3);
    }

    @Test void activeRecoveryRetainsCompletionUntilItCanMarkReadinessWithoutAnyUiRefresh() {
        publish(71);publish(72);publish(73);long id=first();
        when(recovery.hasActiveRecoveryTasks(7L)).thenReturn(true);
        worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates WHERE completion_done=TRUE")).isZero();
        assertThat(outbox.dueBilling(20)).isEmpty();
        when(recovery.hasActiveRecoveryTasks(7L)).thenReturn(false);
        jdbc.update("UPDATE order_publication_client_updates SET completion_next_attempt_at=CURRENT_TIMESTAMP(6)");
        worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates WHERE completion_done=TRUE")).isEqualTo(3);
    }

    @Test void flagChangedAfterClaimAndReadFailurePauseProvablyUnsentIdentityThenResumeFrozenEnvelope() {
        publish(71);long id=first();String before=text("SELECT delivery_envelope FROM order_publication_client_updates");String operation=operation(id);
        preferenceRead=() -> enabled.set(false);worker.deliver(id);
        assertThat(state(id)).isEqualTo("READY");verifyNoInteractions(telegram);
        preferenceRead=() -> {throw new IllegalStateException("preference DB unavailable");};enabled.set(true);due(id);worker.deliver(id);
        assertThat(state(id)).isEqualTo("READY");verifyNoInteractions(telegram);
        preferenceRead=() -> {};when(settings.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenThrow(new IllegalStateException("settings DB unavailable"));
        due(id);worker.deliver(id);assertThat(state(id)).isEqualTo("READY");verifyNoInteractions(telegram);
        doAnswer(call -> enabled.get()).when(settings).getBooleanFreshFailClosed(anyString(),anyBoolean());
        due(id);worker.deliver(id);assertThat(state(id)).isEqualTo("SENT");
        assertThat(operation(id)).isEqualTo(operation);assertThat(text("SELECT delivery_envelope FROM order_publication_client_updates")).isEqualTo(before);
    }

    @Test void companyOptOutPausesExistingProgressButDoesNotPreventFreshCompletionAndCanResume() {
        publish(71);long id=first();companyEnabled.set(false);worker.deliver(id);
        assertThat(state(id)).isEqualTo("READY");verifyNoInteractions(telegram);
        publish(72);publish(73);worker.completeOrder(id);
        assertThat(number("SELECT COUNT(*) FROM fixture_invoices")).isEqualTo(1);
        companyEnabled.set(true);due(id);worker.deliver(id);assertThat(state(id)).isEqualTo("SENT");
    }

    @Test void staleSuccessfulFinalizeRollsBackOccurrenceConfirmationAndCannotReplaceNewClaim() {
        publish(71);long id=first();var old=outbox.claim(id,true).orElseThrow();due(id);var next=outbox.claim(id,true).orElseThrow();
        assertThatThrownBy(() -> outbox.finish(old,ClientMessageSendResult.sent("Telegram","77"))).hasMessageContaining("Obsolete");
        assertThat(text("SELECT claim_token FROM order_publication_client_updates")).isEqualTo(next.token());
        assertThat(jdbc.queryForObject("SELECT confirmed FROM order_client_message_occurrences",Boolean.class)).isFalse();
    }

    @Test void conflictingEnvelopeCannotReplaceTheCommittedOccurrenceAndExactDuplicateIsIdempotent() {
        publish(71);long id=first();var row=outbox.claim(id,true).orElseThrow();var p=outbox.decode(row);
        tx.executeWithoutResult(status -> outbox.enqueue(7,row.occurrence(),p));
        var changed=new PreparedPublicationProgress(p.orderId(),p.kind(),p.operationId(),p.target(),p.clientId(),p.groupId(),"changed",p.includePreferenceControls(),p.recipients());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> outbox.enqueue(7,row.occurrence(),changed))).hasMessageContaining("immutable envelope");
        assertThat(number("SELECT COUNT(*) FROM order_publication_client_updates")).isEqualTo(1);
        assertThat(outbox.decode(row).message()).isEqualTo(p.message());
    }

    @Test void knownUnsentAdmissionFailureRetriesOnlyTheSameImmutableOperationAndLaterProgressWaits() {
        publish(71);publish(72);long id=first();String operation=operation(id);
        var firstClaim=outbox.claim(id,true).orElseThrow();
        assertThat(outbox.finish(firstClaim,ClientMessageSendResult.failed("gateway_busy","request was not admitted"))).isTrue();
        assertThat(state(id)).isEqualTo("READY");
        assertThat(outbox.claim(allIds().get(1),true)).isEmpty();
        due(id);var retry=outbox.claim(id,true).orElseThrow();
        assertThat(retry.receiptOnly()).isFalse();assertThat(retry.operationId()).isEqualTo(operation);
        assertThat(retry.envelope()).isEqualTo(firstClaim.envelope());
        assertThat(outbox.finish(retry,ClientMessageSendResult.sent("Telegram","77"))).isTrue();
        assertThat(outbox.claim(allIds().get(1),true)).isPresent();
    }

    @Test void deletionBeforeFreshDispatchSkipsProgressWithoutProviderOrClaimReplay() {
        publish(71);long id=first();String operation=operation(id);
        jdbc.update("DELETE FROM orders WHERE order_id=7");
        worker.deliver(id);
        assertThat(state(id)).isEqualTo("SKIPPED");
        assertThat(text("SELECT last_error_code FROM order_publication_client_updates")).isEqualTo("order_deleted");
        assertThat(operation(id)).isEqualTo(operation);
        due(id);worker.deliver(id);verifyNoInteractions(telegram);
        assertThat(outbox.dueDeliveries(20)).isEmpty();
    }

    @Test void deletionDoesNotPreventReceiptOnlyReconciliationOfPreviouslyClaimedOperation() {
        publish(71);long id=first();String operation=operation(id);outbox.claim(id,true).orElseThrow();
        jdbc.update("DELETE FROM orders WHERE order_id=7");enabled.set(false);
        fence.execute(operation,"TELEGRAM","-10017","frozen receipt",() -> ClientMessageSendResult.sent("Telegram","89"));
        due(id);worker.deliver(id);
        assertThat(state(id)).isEqualTo("SENT");verifyNoInteractions(telegram);
        verify(orders,never()).existsById(anyLong());
    }

    @Test void unavailableOrderExistenceReadFailsClosedAndResumesSameSnapshotWhenReadRecovers() {
        publish(71);long id=first();String envelope=text("SELECT delivery_envelope FROM order_publication_client_updates");String operation=operation(id);
        doThrow(new IllegalStateException("order read unavailable")).when(orders).existsById(7L);
        worker.deliver(id);assertThat(state(id)).isEqualTo("READY");verifyNoInteractions(telegram);
        doAnswer(call -> jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_id=7",Integer.class)>0).when(orders).existsById(7L);
        due(id);worker.deliver(id);
        assertThat(state(id)).isEqualTo("SENT");assertThat(operation(id)).isEqualTo(operation);
        assertThat(text("SELECT delivery_envelope FROM order_publication_client_updates")).isEqualTo(envelope);
    }

    @Test void commonInvoiceFinalizationRemainsDurableAcrossCompletionAndAckFailureOutsideOrderTransaction() {
        publish(71);long id=first();worker.completeOrder(id);
        assertThat(outbox.dueBilling(20)).containsExactly(id);
        var committed=new AtomicBoolean();var finalizations=new AtomicInteger();
        when(billing.finalizePublishedInvoiceForOrder(7)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            tx.executeWithoutResult(status -> jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=7 FOR UPDATE",Long.class));
            if(committed.compareAndSet(false,true)){finalizations.incrementAndGet();failCommit.set(true);}return true;
        });
        worker.completeBilling(id);assertThat(jdbc.queryForObject("SELECT billing_done FROM order_publication_client_updates",Boolean.class)).isFalse();
        jdbc.update("UPDATE order_publication_client_updates SET billing_next_attempt_at=CURRENT_TIMESTAMP(6)");
        worker.completeBilling(id);assertThat(jdbc.queryForObject("SELECT billing_done FROM order_publication_client_updates",Boolean.class)).isTrue();
        assertThat(finalizations).hasValue(1);
    }

    private ScheduledClientMessageStateRepository invoiceRepository() {
        var repo=mock(ScheduledClientMessageStateRepository.class);
        when(repo.findByScenarioAndTargetKeyForUpdate(any(),anyString())).thenAnswer(call -> jdbc.query(
            "SELECT * FROM fixture_invoices WHERE target_key=? FOR UPDATE",(rs,n) -> {
                var value=ScheduledClientMessageState.builder().id(rs.getLong("id")).scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                    .targetKey(rs.getString("target_key")).status(ScheduledMessageStateStatus.valueOf(rs.getString("status"))).build();
                value.setDeliveryToken(rs.getString("delivery_token"));value.setDeliveryStatus(rs.getString("delivery_status"));return value;
            },call.getArgument(1,String.class)).stream().findFirst());
        when(repo.save(any(ScheduledClientMessageState.class))).thenAnswer(call -> {ScheduledClientMessageState value=call.getArgument(0);
            jdbc.update("INSERT INTO fixture_invoices(target_key,status,delivery_token,delivery_status) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),delivery_token=VALUES(delivery_token),delivery_status=VALUES(delivery_status)",
                value.getTargetKey(),value.getStatus().name(),value.getDeliveryToken(),value.getDeliveryStatus());return value;});
        return repo;
    }
    private ReviewRepository reviewRepository() {
        var repo=mock(ReviewRepository.class);
        when(repo.findByIdForPublication(anyLong())).thenAnswer(call -> jdbc.query("SELECT * FROM fixture_reviews WHERE id=? FOR UPDATE",(rs,n) -> {
            var review=new Review();review.setId(rs.getLong("id"));review.setPublish(rs.getBoolean("published"));review.setText("Готовый отзыв клиента " + review.getId());
            var details=new OrderDetails();details.setOrder(order(7,0,3,"Публикация"));details.setReviews(List.of(review));review.setOrderDetails(details);
            var bot=new Bot();bot.setId(700L);bot.setFio("Test Account");bot.setLogin("79000000000");bot.setPassword("fixture");review.setBot(bot);return review;
        },call.getArgument(0,Long.class)).stream().findFirst());
        when(repo.save(any(Review.class))).thenAnswer(call -> {Review review=call.getArgument(0);
            jdbc.update("UPDATE fixture_reviews SET published=?,published_at=? WHERE id=?",review.isPublish(),review.getPublishedMarkedAt(),review.getId());return review;});
        when(repo.countPublishedByOrderId(anyLong())).thenAnswer(call -> jdbc.queryForObject("SELECT COUNT(*) FROM fixture_reviews WHERE order_id=? AND published=TRUE",Integer.class,call.getArgument(0,Long.class)));
        return repo;
    }
    private Company company(){var c=new Company();c.setId(17L);c.setTitle("Fixture");c.setUrlChat("https://t.me/fixture");c.setTelegramGroupChatId(-10017L);c.setGroupId("group-original");c.setPublicationProgressReportsEnabled(companyEnabled.get());return c;}
    private Order order(long id,int count,int amount,String title){var o=new Order();o.setId(id);o.setCounter(count);o.setAmount(amount);o.setCompany(company());o.setStatus(status(title));o.setStatusChangedAt(LocalDateTime.of(2026,9,8,10,0));var m=new Manager();m.setId(1L);m.setClientId("client-original");o.setManager(m);return o;}
    private OrderStatus status(String title){var s=new OrderStatus();s.setTitle(title);return s;}
    private void publish(long id){try{publication.changeStatusAndOrderCounter(id);}catch(Exception e){throw new IllegalStateException(e);}}
    private long first(){return allIds().getFirst();}
    private List<Long> allIds(){return jdbc.queryForList("SELECT id FROM order_publication_client_updates ORDER BY id",Long.class);}
    private String state(long id){return jdbc.queryForObject("SELECT delivery_state FROM order_publication_client_updates WHERE id=?",String.class,id);}
    private String operation(long id){return jdbc.queryForObject("SELECT operation_id FROM order_publication_client_updates WHERE id=?",String.class,id);}
    private void due(long id){jdbc.update("UPDATE order_publication_client_updates SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);}
    private int number(String sql){return jdbc.queryForObject(sql,Integer.class);}
    private String text(String sql){return jdbc.queryForObject(sql,String.class);}
    private OrderPublicationOutboxWorker worker(OrderPublicationOutbox store,ClientMessageDelivery delivery,OrderStatusNotificationService service){return new OrderPublicationOutboxWorker(store,orders,checker,service,delivery,settings,billing,manager);}
    @SuppressWarnings("unchecked") private <T>T proxy(T actual){var factory=new ProxyFactory(actual);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    @SuppressWarnings("unchecked") private <T>T construct(Class<T> type,Map<Class<?>,Object> supplied) throws Exception {var constructor=Arrays.stream(type.getConstructors()).max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        var args=Arrays.stream(constructor.getParameterTypes()).map(dependency -> supplied.containsKey(dependency)?supplied.get(dependency):mock(dependency)).toArray();return (T)constructor.newInstance(args);}
}
