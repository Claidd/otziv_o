package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.manager.api.PaymentReviewOrderAccess;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Actual payment/review owners and Spring transactions with JDBC repository/ledger adapters.
 * Proves commit/rollback and competing review locks; complete attribution rules remain covered by their module tests. */
@Testcontainers
class ManagerManualPaymentCreditMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383");
    Fixture f;
    @BeforeEach void setup() throws Exception {
        DataSource ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var j=new JdbcTemplate(ds);
        j.execute("CREATE TABLE IF NOT EXISTS credit_orders(id BIGINT PRIMARY KEY, paid BOOLEAN NOT NULL) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS credit_links(id BIGINT PRIMARY KEY,status VARCHAR(32),paid_at DATETIME(6),confirmed BIGINT,manual_at DATETIME(6)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS credit_ledger(source_id BIGINT PRIMARY KEY,amount BIGINT NOT NULL) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS credit_reviews(id BIGINT PRIMARY KEY,status VARCHAR(32),credited_at DATETIME(6),deadline_at DATETIME(6),disputed_at DATETIME(6),resolution_kind VARCHAR(32),resolved_at DATETIME(6)) ENGINE=InnoDB");
        for(String table:List.of("credit_reviews","credit_ledger","credit_links","credit_orders"))j.update("DELETE FROM "+table);
        j.update("INSERT INTO credit_orders VALUES(7,false)");
        j.update("INSERT INTO credit_links(id,status) VALUES(71,'WAITING_MANUAL_PAYMENT')");
        j.update("INSERT INTO credit_reviews(id,status,deadline_at) VALUES(101,'SETTLING',?)",LocalDateTime.now().plusHours(24));
        f=new Fixture(j,new DataSourceTransactionManager(ds));
    }

    @Test void financialReceiptLedgerAndCreditMarkerCommitTogetherAndReplayCannotAccrueTwice() {
        f.pay();
        assertThat(f.scalar("SELECT paid FROM credit_orders WHERE id=7",Boolean.class)).isTrue();
        assertThat(f.scalar("SELECT status FROM credit_links WHERE id=71",String.class)).isEqualTo("CONFIRMED");
        assertThat(f.scalar("SELECT confirmed FROM credit_links WHERE id=71",Long.class)).isEqualTo(140000L);
        assertThat(f.scalar("SELECT credited_at FROM credit_reviews WHERE id=101",java.sql.Timestamp.class)).isNotNull();
        f.pay();
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
        assertThat(f.scalar("SELECT COUNT(*) FROM credit_ledger",Integer.class)).isEqualTo(1);
        verify(f.bean(ManualPaymentRecipientTelegramNotificationService.class),never()).notifyAfterCommit(any(PaymentLink.class));
        verify(f.bean(PaymentLinkSettlementService.class)).prepareSuccessNotificationRetry(any());
    }

    @Test void creditMarkerFailureRollsBackOrderReceiptAndLedgerAsOneTransaction() {
        f.failMarker.set(true);
        assertThatThrownBy(f::pay).isInstanceOf(IllegalStateException.class).hasMessageContaining("marker persistence failure");
        assertThat(f.scalar("SELECT paid FROM credit_orders WHERE id=7",Boolean.class)).isFalse();
        assertThat(f.scalar("SELECT status FROM credit_links WHERE id=71",String.class)).isEqualTo("WAITING_MANUAL_PAYMENT");
        assertThat(f.scalar("SELECT confirmed FROM credit_links WHERE id=71",Long.class)).isNull();
        assertThat(f.scalar("SELECT COUNT(*) FROM credit_ledger",Integer.class)).isZero();
        assertThat(f.scalar("SELECT credited_at FROM credit_reviews WHERE id=101",java.sql.Timestamp.class)).isNull();
    }

    @Test void markerCannotBeWrittenOutsideFinancialTransaction() {
        assertThatThrownBy(()->f.guard.recordCredit(101L,71L,LocalDateTime.now())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(f.scalar("SELECT credited_at FROM credit_reviews WHERE id=101",java.sql.Timestamp.class)).isNull();
    }

    @Test void committedDisputeWinsAgainstFinalisationWaitingOnSameDatabaseRow() throws Exception {
        f.pay();
        f.jdbc.update("UPDATE credit_reviews SET status='WAITING',deadline_at=? WHERE id=101",LocalDateTime.now().plusSeconds(2));
        var disputeSaved=new CountDownLatch(1);var releaseDispute=new CountDownLatch(1);var finalLock=new CountDownLatch(1);
        f.disputeSaved=()->{disputeSaved.countDown();await(releaseDispute);};
        f.beforeReviewLock=()->{if(Thread.currentThread().getName().equals("finalizer"))finalLock.countDown();};
        try(var executor=Executors.newFixedThreadPool(2)){
            var dispute=executor.submit(()->f.reviewService.disputeForOrder(7L,101L,true,"Получатель сообщил об отсутствии",f.auth));
            assertThat(disputeSaved.await(5,TimeUnit.SECONDS)).isTrue();
            // Finaliser is made due while the earlier valid dispute owns the review lock.
            while(LocalDateTime.now().isBefore(f.originalDeadline))Thread.sleep(10);
            var finalize=executor.submit(()->{Thread.currentThread().setName("finalizer");f.settlement.settle(101L);});
            assertThat(finalLock.await(5,TimeUnit.SECONDS)).isTrue();assertThat(finalize.isDone()).isFalse();
            releaseDispute.countDown();assertThat(dispute.get(5,TimeUnit.SECONDS).status()).isEqualTo("DISPUTED");finalize.get(5,TimeUnit.SECONDS);
        } finally {releaseDispute.countDown();}
        assertThat(f.scalar("SELECT status FROM credit_reviews WHERE id=101",String.class)).isEqualTo("DISPUTED");
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
    }

    @Test void finalisedDeadlineRejectsLaterManagerDisputeWithoutTouchingMoney() {
        f.pay();f.jdbc.update("UPDATE credit_reviews SET status='WAITING',deadline_at=? WHERE id=101",LocalDateTime.now().minusSeconds(1));
        f.settlement.settle(101L);
        assertThatThrownBy(()->f.reviewService.disputeForOrder(7L,101L,true,"Получатель сообщил об отсутствии",f.auth)).isInstanceOf(ResponseStatusException.class);
        assertThat(f.scalar("SELECT resolution_kind FROM credit_reviews WHERE id=101",String.class)).isEqualTo("NO_OBJECTION_24H");
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
    }

    @Test void repeatedManagerSubmitRequiresCurrentPaidSourceWithoutWritingAnotherReceipt() {
        f.pay();
        var result=f.reviewService.replayCredited(7L,"SPECIALIST",27L,"PROFILE:27","Получено","",f.auth);
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.manualPaymentReview().id()).isEqualTo(101L);
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
        verify(f.bean(PaymentLinkSettlementService.class)).prepareSuccessNotificationRetry(any());
    }

    @ParameterizedTest @ValueSource(strings={"order-unpaid","changed-cycle","source-returned","new-amount","new-recipient","return-marker","competing-route"})
    void historicalCreditDoesNotCompleteChangedCurrentPayment(String change) {
        f.pay();
        if(change.equals("order-unpaid"))f.jdbc.update("UPDATE credit_orders SET paid=false WHERE id=7");
        if(change.equals("changed-cycle"))when(f.bean(OrderPaymentIntegrityService.class).isDuplicatePaymentCycle(any())).thenReturn(true);
        if(change.equals("source-returned"))f.jdbc.update("UPDATE credit_links SET status='CANCELED' WHERE id=71");
        if(change.equals("new-amount"))when(f.bean(PaymentLinkAmountPolicy.class).amountKopecks(any())).thenReturn(150000L);
        if(Set.of("new-recipient","return-marker","competing-route").contains(change)){
            var source=f.link(false);var all=new ArrayList<PaymentLink>();all.add(source);
            if(change.equals("new-recipient"))source.setManualActualRecipientUserId(38L);
            if(change.equals("return-marker"))source.setReturnRecoveryProcessedAt(LocalDateTime.now());
            if(change.equals("competing-route")){
                var next=f.link(false);next.setId(72L);next.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);all.add(next);
                when(f.bean(ManualCardRoutePolicy.class).isCompetingManualCardPaymentRoute(next)).thenReturn(true);
            }
            doReturn(all).when(f.bean(PaymentLinkRepository.class)).findByOrderIdForUpdate(7L);
        }
        assertThatThrownBy(()->f.reviewService.replayCredited(7L,"SPECIALIST",27L,"PROFILE:27","Получено","",f.auth))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
        verify(f.bean(PaymentLinkSettlementService.class)).prepareSuccessNotificationRetry(any());
    }

    @Test void completedBankHandoffRequiresItsUniqueConfirmedManualEvidence() {
        f.pay();var source=f.link(false);source.setPaymentMethod(PaymentMethod.BANK_FORM);
        source.setStatus(PaymentLinkStatus.CANCELED);source.setManualConfirmedAt(null);source.setConfirmedAmountKopecks(null);
        var evidence=f.link(false);evidence.setId(72L);evidence.setContractorEvidenceOriginalLinkId(71L);
        when(f.bean(ManualCardRoutePolicy.class).isCompletedManualCardPayment(source)).thenReturn(true);
        doReturn(List.of(source,evidence)).when(f.bean(PaymentLinkRepository.class)).findByOrderIdForUpdate(7L);
        assertThatCode(()->f.workflow.requireCreditedReviewReplay(f.loadReview(false),f.auth)).doesNotThrowAnyException();
        evidence.setConfirmedAmountKopecks(130000L);
        assertThatThrownBy(()->f.workflow.requireCreditedReviewReplay(f.loadReview(false),f.auth)).isInstanceOf(ResponseStatusException.class);
        evidence.setConfirmedAmountKopecks(140000L);
        doReturn(List.of(source)).when(f.bean(PaymentLinkRepository.class)).findByOrderIdForUpdate(7L);
        assertThatThrownBy(()->f.workflow.requireCreditedReviewReplay(f.loadReview(false),f.auth)).isInstanceOf(ResponseStatusException.class);
        assertThat(f.scalar("SELECT SUM(amount) FROM credit_ledger",Long.class)).isEqualTo(140000L);
    }

    static void await(CountDownLatch latch){try{if(!latch.await(6,TimeUnit.SECONDS))throw new AssertionError("fixture wait timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static <T>T proxy(T target,PlatformTransactionManager manager){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    static class Fixture {
        final JdbcTemplate jdbc;final Map<Class<?>,Object> beans=new HashMap<>();final AtomicBoolean failMarker=new AtomicBoolean();
        final TestingAuthenticationToken auth=new TestingAuthenticationToken("manager","unused","ROLE_MANAGER");
        final SpecialistManualPaymentReviewGuard guard;final ManualCardPaymentWorkflow workflow;
        final SpecialistManualPaymentReviewService reviewService;final SpecialistManualPaymentReviewSettlementService settlement;
        Runnable disputeSaved=()->{};Runnable beforeReviewLock=()->{};LocalDateTime originalDeadline;
        Fixture(JdbcTemplate jdbc,PlatformTransactionManager manager) throws Exception {
            this.jdbc=jdbc;
            var reviews=bean(SpecialistManualPaymentReviewRepository.class);
            doAnswer(c->{transaction();beforeReviewLock.run();return Optional.of(loadReview(true));}).when(reviews).lockById(101L);
            when(reviews.findFirstByOrderIdOrderByIdDesc(7L)).thenAnswer(c->Optional.of(loadReview(false)));
            when(reviews.findLatestIdByOrderId(7L)).thenReturn(Optional.of(101L));
            when(reviews.lockUnresolvedByOrderId(eq(7L),anyCollection())).thenAnswer(c->{transaction();return List.of(loadReview(true));});
            doAnswer(c->saveReview(c.getArgument(0,SpecialistManualPaymentReview.class))).when(reviews).save(any());
            doAnswer(c->saveReview(c.getArgument(0,SpecialistManualPaymentReview.class))).when(reviews).saveAndFlush(any());
            guard=proxy(new SpecialistManualPaymentReviewGuard(reviews),manager);beans.put(SpecialistManualPaymentReviewGuard.class,guard);
            beans.put(PaymentLinkTransactionExecutor.class,proxy(new PaymentLinkTransactionExecutor(),manager));
            var orders=bean(OrderRepository.class);when(orders.findByIdForCounterUpdate(7L)).thenAnswer(c->{transaction();jdbc.queryForObject("SELECT id FROM credit_orders WHERE id=7 FOR UPDATE",Long.class);return Optional.of(order());});
            var links=bean(PaymentLinkRepository.class);
            when(links.findByIdWithOrder(71L)).thenAnswer(c->Optional.of(link(false)));
            when(links.findById(71L)).thenAnswer(c->Optional.of(link(false)));
            when(links.findByIdForUpdate(71L)).thenAnswer(c->{transaction();return Optional.of(link(true));});
            when(links.findByOrderIdForUpdate(7L)).thenAnswer(c->{transaction();return List.of(link(true));});
            doAnswer(c->saveLink(c.getArgument(0,PaymentLink.class))).when(links).saveAndFlush(any());
            doAnswer(c->saveLink(c.getArgument(0,PaymentLink.class))).when(links).save(any());
            when(bean(PaymentLinkLifecycleService.class).hasOrderBinding(any(),eq(7L))).thenReturn(true);
            when(bean(ManualCardRoutePolicy.class).isDirectManualAttributionRoute(any())).thenReturn(true);
            when(bean(PaymentLinkPresenter.class).normalize(nullable(String.class))).thenAnswer(c->{String v=c.getArgument(0,String.class);return v==null?"":v.trim();});
            when(bean(CommonInvoiceRouteSelector.class).limit(nullable(String.class),anyInt())).thenAnswer(c->{String v=c.getArgument(0,String.class);return v==null?null:v.substring(0,Math.min(v.length(),c.getArgument(1,Integer.class)));});
            var financial=bean(PaymentLinkSettlementService.class);when(financial.canApplyOrderPaymentNow(any())).thenReturn(true);
            when(financial.handlePaymentStatusWithoutPrematureRepeat(any(),eq(71L))).thenAnswer(c->{transaction();jdbc.update("UPDATE credit_orders SET paid=true WHERE id=7");return true;});
            doAnswer(c->{transaction();jdbc.update("INSERT INTO credit_ledger(source_id,amount) VALUES(71,140000)");return null;})
                    .when(bean(ContractorActualPaymentAttributionService.class)).recordPaymentLinkFinalAttribution(any(),any(),any());
            when(bean(PaymentLinkAmountPolicy.class).payableSum(any())).thenReturn(java.math.BigDecimal.valueOf(1400));
            when(bean(PaymentLinkAmountPolicy.class).amountKopecks(any())).thenReturn(140000L);
            var integrity=bean(OrderPaymentIntegrityService.class);
            when(integrity.hasSettledPaymentEvidence(any())).thenAnswer(c->jdbc.queryForObject("SELECT paid FROM credit_orders WHERE id=7",Boolean.class));
            when(integrity.lockSpecialistReviewOrderEvidence(7L)).thenAnswer(c->{transaction();return Optional.of(jdbc.queryForObject("SELECT paid FROM credit_orders WHERE id=7 FOR UPDATE",Boolean.class));});
            workflow=construct(ManualCardPaymentWorkflow.class);beans.put(ManualCardPaymentWorkflow.class,workflow);
            reviewService=proxy(construct(SpecialistManualPaymentReviewService.class),manager);
            beans.put(SpecialistManualPaymentReviewService.class,reviewService);
            settlement=construct(SpecialistManualPaymentReviewSettlementService.class);
        }
        void pay(){workflow.confirmRecipientReviewPayment(101L,71L,140000L,"Причина менеджера: Получено","","manager",auth,new ManualCardPaymentWorkflow.ManualCardPaymentContext(ManualCardPaymentWorkflow.ManualCardPaymentMode.MANAGER_REPORTED,"Получено"),"SPECIALIST",27L,"PROFILE:27");}
        <T>T bean(Class<T> type){return (T)beans.computeIfAbsent(type,k->mock(k));}
        <T>T construct(Class<T> type)throws Exception {var ctor=type.getConstructors()[0];Object[] args=Arrays.stream(ctor.getParameterTypes()).map(this::bean).toArray();return (T)ctor.newInstance(args);}
        <T>T scalar(String sql,Class<T> type){return jdbc.queryForObject(sql,type);}
        void transaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();}
        Order order(){var o=new Order();o.setId(7L);return o;}
        PaymentLink link(boolean lock){return jdbc.queryForObject("SELECT * FROM credit_links WHERE id=71"+(lock?" FOR UPDATE":""),(rs,row)->{var p=new PaymentLink();p.setId(71L);p.setOrder(order());p.setAmountKopecks(140000);p.setStatus(PaymentLinkStatus.valueOf(rs.getString("status")));p.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);p.setPaidAt(date(rs.getTimestamp("paid_at")));p.setManualConfirmedAt(date(rs.getTimestamp("manual_at")));p.setConfirmedAmountKopecks((Long)rs.getObject("confirmed"));p.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);p.setManualActualRecipientProfileId(27L);p.setManualActualRecipientUserId(37L);p.setManualActualRecipientFrozenAt(LocalDateTime.now().minusDays(1));p.setManualActualReason("Получено");p.setManualActualReceiptUrl("");return p;});}
        PaymentLink saveLink(PaymentLink p){transaction();jdbc.update("UPDATE credit_links SET status=?,paid_at=?,confirmed=?,manual_at=? WHERE id=71",p.getStatus().name(),p.getPaidAt(),p.getConfirmedAmountKopecks(),p.getManualConfirmedAt());return p;}
        SpecialistManualPaymentReview loadReview(boolean lock){return jdbc.queryForObject("SELECT * FROM credit_reviews WHERE id=101"+(lock?" FOR UPDATE":""),(rs,row)->{var r=new SpecialistManualPaymentReview();r.setId(101L);r.setOrderId(7L);r.setPaymentLinkId(71L);r.setAmountKopecks(140000);r.setRecipientProfileId(27L);r.setRecipientUserId(37L);r.setRecipientKey("PROFILE:27");r.setRequestedByUserId(17L);r.setRequestedBy("manager");r.setReason("Получено");r.setReceiptUrl("");r.setRequestedAt(LocalDateTime.now().minusHours(1));r.setStatus(SpecialistManualPaymentReviewStatus.valueOf(rs.getString("status")));r.setCreditedAt(date(rs.getTimestamp("credited_at")));r.setDeadlineAt(date(rs.getTimestamp("deadline_at")));originalDeadline=r.getDeadlineAt();r.setDisputedAt(date(rs.getTimestamp("disputed_at")));r.setResolutionKind(rs.getString("resolution_kind"));r.setResolvedAt(date(rs.getTimestamp("resolved_at")));return r;});}
        SpecialistManualPaymentReview saveReview(SpecialistManualPaymentReview r){transaction();jdbc.update("UPDATE credit_reviews SET status=?,credited_at=?,deadline_at=?,disputed_at=?,resolution_kind=?,resolved_at=? WHERE id=101",r.getStatus().name(),r.getCreditedAt(),r.getDeadlineAt(),r.getDisputedAt(),r.getResolutionKind(),r.getResolvedAt());if(r.getCreditedAt()!=null&&failMarker.get())throw new IllegalStateException("marker persistence failure");if(r.getStatus()==SpecialistManualPaymentReviewStatus.DISPUTED)disputeSaved.run();return r;}
        static LocalDateTime date(java.sql.Timestamp t){return t==null?null:t.toLocalDateTime();}
    }
}
