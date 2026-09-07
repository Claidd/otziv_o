package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.model.*;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.bad_reviews.service.*;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.*;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import com.hunt.otziv.p_products.worker_access.service.*;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.*;
import com.hunt.otziv.review_recovery.repository.*;
import com.hunt.otziv.review_recovery.service.*;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Real domain and Spring transaction proxies; JDBC adapters replace repository persistence only. */
@Testcontainers
class WorkerTaskCommandsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(
        "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("worker_tasks").withUsername("root").withPassword("root");
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    DataSourceTransactionManager tm;
    WorkerAssignmentMutationGuardService guard;
    WorkerActivityService activity;
    BadReviewTaskRepository badRepository;
    ReviewRecoveryTaskRepository recoveryRepository;
    ReviewRecoveryBotExclusionRepository exclusionRepository;
    BadReviewTaskService bad;
    ReviewRecoveryTaskService recovery;
    WorkerTaskEditingCommands editing;
    WorkerTaskAccountCommands accounts;
    WorkerTaskCompletionCommands completion;
    PaymentLinkService paymentLinks;
    Runnable beforeOrderLock=()->{};
    boolean failExclusionWrite;
    AtomicInteger lockedChecks=new AtomicInteger();
    WorkerOrderActor worker=new WorkerOrderActor("worker-a",Set.of("WORKER"));

    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();
        ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds); tm=new DataSourceTransactionManager(ds);
        jdbc.execute("DROP TABLE IF EXISTS task_exclusions"); jdbc.execute("DROP TABLE IF EXISTS command_tasks");
        jdbc.execute("DROP TABLE IF EXISTS command_orders");
        jdbc.execute("CREATE TABLE command_orders(id BIGINT PRIMARY KEY,owner VARCHAR(40),manager_id BIGINT)");
        jdbc.execute("CREATE TABLE command_tasks(id BIGINT PRIMARY KEY,order_id BIGINT,owner VARCHAR(40),status VARCHAR(30),text VARCHAR(100),scheduled_date DATE)");
        jdbc.execute("CREATE TABLE task_exclusions(task_id BIGINT,bot_id BIGINT,PRIMARY KEY(task_id,bot_id))");
        jdbc.update("INSERT INTO command_orders VALUES(10,'worker-a',9),(20,'worker-a',9)");
        jdbc.update("INSERT INTO command_tasks VALUES(1,10,'worker-a','NEW','old','2026-09-07'),(2,20,'worker-a','PLANNED','old','2026-09-07')");
        var orderRepository=mock(OrderRepository.class);
        when(orderRepository.findByIdForCounterUpdate(anyLong())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Runnable contender=beforeOrderLock; beforeOrderLock=()->{}; contender.run();
            long id=inv.getArgument(0);
            jdbc.queryForObject("SELECT id FROM command_orders WHERE id=? FOR UPDATE",Long.class,id);
            return Optional.of(Order.builder().id(id).build());
        });
        var locks=proxy(new OrderAggregateMutationLockService(orderRepository,mock(OrderDetailsRepository.class),mock(ReviewRepository.class)));
        var ownership=mock(WorkerAssignmentMutationGuardRepository.class);
        when(ownership.findOrderIdByBadTaskId(anyLong())).thenAnswer(inv -> orderId(inv.getArgument(0)));
        when(ownership.findOrderIdByRecoveryTaskId(anyLong())).thenAnswer(inv -> orderId(inv.getArgument(0)));
        when(ownership.findCurrentOrderIdByBadTaskId(anyLong())).thenAnswer(inv -> currentOrderId(inv.getArgument(0)));
        when(ownership.findCurrentOrderIdByRecoveryTaskId(anyLong())).thenAnswer(inv -> currentOrderId(inv.getArgument(0)));
        when(ownership.countOwnedBadTask(anyLong(),anyString())).thenAnswer(inv -> owned(inv.getArgument(0),inv.getArgument(1),false).isPresent()?1L:0L);
        when(ownership.countOwnedRecoveryTask(anyLong(),anyString())).thenAnswer(inv -> owned(inv.getArgument(0),inv.getArgument(1),false).isPresent()?1L:0L);
        when(ownership.lockOwnedBadTask(anyLong(),anyString())).thenAnswer(inv -> owned(inv.getArgument(0),inv.getArgument(1),true));
        when(ownership.lockOwnedRecoveryTask(anyLong(),anyString())).thenAnswer(inv -> owned(inv.getArgument(0),inv.getArgument(1),true));
        when(ownership.findCurrentManagerIdByOrderId(anyLong())).thenAnswer(inv -> Optional.ofNullable(jdbc.queryForObject("SELECT manager_id FROM command_orders WHERE id=? FOR UPDATE",Long.class,inv.getArgument(0,Long.class))));
        var managers=mock(ManagerAccessService.class);
        when(managers.canAccessOrder(anyLong(),any())).thenAnswer(inv -> Objects.equals(9L,jdbc.queryForObject("SELECT manager_id FROM command_orders WHERE id=?",Long.class,inv.getArgument(0,Long.class))));
        when(managers.canAccessCurrentOrderManager(any(),any())).thenAnswer(inv -> Objects.equals(9L,inv.getArgument(0)));
        guard=new WorkerAssignmentMutationGuardService(ownership,managers,locks);
        badRepository=mock(BadReviewTaskRepository.class);
        when(badRepository.findByIdForMutation(anyLong())).thenAnswer(inv -> {
            long id=inv.getArgument(0); lockTask(id); return Optional.of(loadBad(id));
        });
        when(badRepository.findById(anyLong())).thenAnswer(inv -> Optional.of(loadBad(inv.getArgument(0))));
        when(badRepository.findStatusById(anyLong())).thenAnswer(inv -> Optional.of(BadReviewTaskStatus.valueOf(jdbc.queryForObject("SELECT status FROM command_tasks WHERE id=?",String.class,inv.getArgument(0,Long.class)))));
        when(badRepository.findOrderIdById(anyLong())).thenAnswer(inv -> orderId(inv.getArgument(0)));
        when(badRepository.save(any())).thenAnswer(inv -> {
            BadReviewTask task=inv.getArgument(0); jdbc.update("UPDATE command_tasks SET text=?,status=? WHERE id=?",task.getTaskText(),task.getStatus().name(),task.getId());return task;
        });
        recoveryRepository=mock(ReviewRecoveryTaskRepository.class);
        when(recoveryRepository.findByIdForMutation(anyLong())).thenAnswer(inv -> {
            long id=inv.getArgument(0); lockTask(id); return Optional.of(loadRecovery(id));
        });
        when(recoveryRepository.findById(anyLong())).thenAnswer(inv -> Optional.of(loadRecovery(inv.getArgument(0))));
        when(recoveryRepository.save(any())).thenAnswer(inv -> {
            ReviewRecoveryTask task=inv.getArgument(0); jdbc.update("UPDATE command_tasks SET text=?,status=? WHERE id=?",task.getRecoveryText(),task.getStatus().name(),task.getId());return task;
        });
        exclusionRepository=mock(ReviewRecoveryBotExclusionRepository.class);
        when(exclusionRepository.insertIgnore(anyLong(),anyLong(),anyString())).thenAnswer(inv -> {
            jdbc.update("INSERT IGNORE INTO task_exclusions VALUES(?,?)",inv.getArgument(0,Long.class),inv.getArgument(1,Long.class));
            if(failExclusionWrite) throw new IllegalStateException("late database failure"); return 1;
        });
        var exclusion=proxy(new ReviewRecoveryBotExclusionService(exclusionRepository));
        var recoveryTarget=construct(ReviewRecoveryTaskServiceImpl.class,Map.of(ReviewRecoveryTaskRepository.class,recoveryRepository,
            ReviewRecoveryBotExclusionService.class,exclusion,WorkerAssignmentMutationGuardService.class,guard));
        recovery=proxy(recoveryTarget);
        var badTarget=construct(BadReviewTaskServiceImpl.class,Map.of(BadReviewTaskRepository.class,badRepository,
            WorkerAssignmentMutationGuardService.class,guard,OrderRepository.class,orderRepository,
            BadReviewTaskTransactionRunner.class,proxy(new BadReviewTaskTransactionRunner())));
        paymentLinks=mock(PaymentLinkService.class); ObjectProvider<PaymentLinkService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(paymentLinks);
        ReflectionTestUtils.setField(badTarget,"paymentLinkServiceProvider",provider);
        bad=proxy(badTarget);
        activity=mock(WorkerActivityService.class); var cellular=mock(WorkerCellularAccessService.class);
        editing=new WorkerTaskEditingCommands(bad,recovery,activity,cellular,guard,new WorkerTaskSchedulePolicy(bad,recovery));
        accounts=new WorkerTaskAccountCommands(bad,recovery,activity,cellular,guard);
        completion=new WorkerTaskCompletionCommands(mock(UserService.class),bad,recovery,activity,cellular,guard);
    }

    @AfterEach void clearAmbient() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lockedMutationUsesExplicitActorWithEmptyOrDifferentAmbientContext(boolean ambientAdministrator) {
        if(ambientAdministrator) SecurityContextHolder.getContext().setAuthentication(new WorkerOrderActor("ambient-admin",Set.of("ADMIN")).authentication());
        editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("accepted",null,null),worker);
        assertThat(text(2)).isEqualTo("accepted");
        assertThatThrownBy(() -> editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("foreign",null,null),
            new WorkerOrderActor("worker-b",Set.of("WORKER")))).isInstanceOf(RuntimeException.class);
        assertThat(text(2)).isEqualTo("accepted");
    }

    @ParameterizedTest @ValueSource(longs={1,2})
    void reassignmentCommittedBetweenInitialResolutionAndCanonicalLockCannotUseOldSnapshot(long taskId) {
        SecurityContextHolder.getContext().setAuthentication(new WorkerOrderActor("ambient-admin",Set.of("ADMIN")).authentication());
        beforeOrderLock=()->transferInIndependentConnection(taskId,"worker-b");
        assertThatThrownBy(() -> {
            if(taskId==1) editing.updateBadReviewTask(1L,new WorkerTaskEditingCommands.BadTaskUpdateRequest("stale write",null),worker);
            else editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("stale write",null,null),worker);
        }).isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(409);
        assertThat(text(taskId)).isEqualTo("old"); verifyNoInteractions(activity);
    }


    @ParameterizedTest @ValueSource(strings={"MANAGER","OWNER"})
    void currentManagerScopeRejectsReassignmentAfterTheOldPermissionSnapshot(String role) {
        beforeOrderLock=()->{
            try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE command_orders SET manager_id=10 WHERE id=20");
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        };
        var manager=new WorkerOrderActor("manager-a",Set.of(role));
        assertThatThrownBy(() -> editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("stale manager",null,null),manager))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(404);
        assertThat(text(2L)).isEqualTo("old"); verifyNoInteractions(activity);
    }


    @ParameterizedTest @ValueSource(longs={1,2})
    void workerCannotOverwriteManagerRescheduleCommittedAfterInitialDateCheck(long taskId) {
        beforeOrderLock=()->{
            try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE command_tasks SET scheduled_date='2026-09-08' WHERE id="+taskId);
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        };
        assertThatThrownBy(() -> {
            if(taskId==1) editing.updateBadReviewTask(1L,new WorkerTaskEditingCommands.BadTaskUpdateRequest("old date",null),worker);
            else editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("old date",null,LocalDate.of(2026,9,7)),worker);
        }).isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(403);
        assertThat(currentTaskDate(taskId)).isEqualTo(LocalDate.of(2026,9,8));
        assertThat(text(taskId)).isEqualTo("old"); verifyNoInteractions(activity);
    }

    @Test void recoveryNoReplacementCommitsExclusionBeforeApplicationTranslatesConflict() {
        assertThatThrownBy(() -> accounts.changeRecoveryTaskBot(2L,worker))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(409);
        assertThat(exclusions()).isEqualTo(1); verifyNoInteractions(activity);
    }

    @Test void ordinaryLateExclusionFailureRollsBackDespiteNoRollbackForStatusException() {
        failExclusionWrite=true;
        assertThatThrownBy(() -> accounts.changeRecoveryTaskBot(2L,worker))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(400);
        assertThat(exclusions()).isZero(); verifyNoInteractions(activity);
    }

    @Test void badCompletionProviderRunsOutsideTransactionAndChangedAssignmentStopsTheLaterWrite() {
        when(paymentLinks.reconcileActiveLinkForOrder(10L)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            transferInIndependentConnection(1L,"worker-b"); return null;
        });
        assertThatThrownBy(() -> completion.completeBadReviewTask(1L,worker))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM command_tasks WHERE id=1",String.class)).isEqualTo("NEW");
        verify(paymentLinks).reconcileActiveLinkForOrder(10L); verifyNoInteractions(activity);
    }


    @ParameterizedTest @ValueSource(strings={"lockOwnedOrder","lockOwnedReview","lockOwnedBadTask","lockOwnedRecoveryTask"})
    void actualRepositorySqlUsesCurrentRowsWithoutLockingUserOrWorkerTables(String queryMethod) throws Exception {
        createNativeQueryFixture();
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(status -> {
            // Establish an old RR snapshot, then commit reassignment through a second physical connection.
            assertThat(jdbc.queryForObject("SELECT order_worker FROM orders WHERE order_id=10",Long.class)).isEqualTo(81L);
            nativeTransfer();
            jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=10 FOR UPDATE",Long.class);
            try {
                String sql=WorkerAssignmentMutationGuardRepository.class.getMethod(queryMethod,long.class,String.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
                var parameters=Map.of("orderId",10L,"reviewId",10L,"taskId",10L,"username","worker-a");
                assertThat(named.queryForList(sql,parameters,Long.class)).isEmpty();
                assertThat(named.queryForList(sql,Map.of("orderId",10L,"reviewId",10L,"taskId",10L,"username","worker-b"),Long.class)).containsExactly(10L);
                // Only aggregate rows are locked; an unrelated user profile change must not wait for this transaction.
                try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                    assertThat(statement.executeUpdate("UPDATE users SET username='worker-b-updated' WHERE id=2")).isEqualTo(1);
                    assertThat(statement.executeUpdate("UPDATE workers SET user_id=2 WHERE worker_id=82")).isEqualTo(1);
                }
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    @ParameterizedTest @ValueSource(strings={"findCurrentOrderIdByReviewId","findCurrentOrderIdByBadTaskId","findCurrentOrderIdByRecoveryTaskId"})
    void actualCurrentBindingSqlSeesRebindingAfterEarlierSnapshot(String queryMethod) throws Exception {
        createNativeQueryFixture();
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT order_detail_order FROM order_details WHERE order_detail_id=10",Long.class)).isEqualTo(10L);
            try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE order_details SET order_detail_order=20 WHERE order_detail_id=10");
                statement.executeUpdate("UPDATE bad_review_tasks SET bad_review_task_order=20 WHERE bad_review_task_id=10");
                statement.executeUpdate("UPDATE review_recovery_tasks SET review_recovery_task_order=20 WHERE review_recovery_task_id=10");
                jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=10 FOR UPDATE",Long.class);
                String sql=WorkerAssignmentMutationGuardRepository.class.getMethod(queryMethod,long.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
                assertThat(named.queryForObject(sql,Map.of("reviewId",10L,"taskId",10L),Long.class)).isEqualTo(20L);
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        });
    }


    @Test void actualCurrentManagerSqlDoesNotReuseEarlierManagerSnapshot() throws Exception {
        createNativeQueryFixture();
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT order_manager FROM orders WHERE order_id=10",Long.class)).isEqualTo(9L);
            try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE orders SET order_manager=12 WHERE order_id=10");
                jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=10 FOR UPDATE",Long.class);
                String sql=WorkerAssignmentMutationGuardRepository.class.getMethod("findCurrentManagerIdByOrderId",long.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
                assertThat(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                    .queryForObject(sql,Map.of("orderId",10L),Long.class)).isEqualTo(12L);
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    @Test void rebindingAfterPreflightIsRejectedEvenIfWorkerOwnsBothOrders() {
        beforeOrderLock=()->{
            try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE command_tasks SET order_id=20 WHERE id=1");
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        };
        assertThatThrownBy(() -> editing.updateBadReviewTask(1L,new WorkerTaskEditingCommands.BadTaskUpdateRequest("wrong binding",null),worker))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(409);
        assertThat(text(1L)).isEqualTo("old");
    }

    void createNativeQueryFixture() {
        for(String table:List.of("review_recovery_tasks","review_recovery_batches","bad_review_tasks","reviews","order_details","orders","workers","users")) jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(60))");
        jdbc.execute("CREATE TABLE workers(worker_id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_worker BIGINT,order_complete BOOLEAN,order_manager BIGINT)");
        jdbc.execute("CREATE TABLE order_details(order_detail_id BIGINT PRIMARY KEY,order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE reviews(review_id BIGINT PRIMARY KEY,review_worker BIGINT,review_order_details BIGINT,review_publish BOOLEAN)");
        jdbc.execute("CREATE TABLE bad_review_tasks(bad_review_task_id BIGINT PRIMARY KEY,bad_review_task_order BIGINT,bad_review_task_worker BIGINT,bad_review_task_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE review_recovery_batches(review_recovery_batch_id BIGINT PRIMARY KEY,review_recovery_batch_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE review_recovery_tasks(review_recovery_task_id BIGINT PRIMARY KEY,review_recovery_task_order BIGINT,review_recovery_task_worker BIGINT,review_recovery_task_status VARCHAR(30),review_recovery_task_batch BIGINT)");
        jdbc.update("INSERT INTO users VALUES(1,'worker-a'),(2,'worker-b')");
        jdbc.update("INSERT INTO workers VALUES(81,1),(82,2)");
        jdbc.update("INSERT INTO orders VALUES(10,81,false,9),(20,81,false,9)");
        jdbc.update("INSERT INTO order_details VALUES(10,10)");
        jdbc.update("INSERT INTO reviews VALUES(10,81,10,false)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES(10,10,81,'NEW')");
        jdbc.update("INSERT INTO review_recovery_batches VALUES(10,'OPEN')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES(10,10,81,'PLANNED',10)");
    }
    void nativeTransfer() {
        try(Connection connection=ds.getConnection();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE orders SET order_worker=82 WHERE order_id=10");
            statement.executeUpdate("UPDATE reviews SET review_worker=82 WHERE review_id=10");
            statement.executeUpdate("UPDATE bad_review_tasks SET bad_review_task_worker=82 WHERE bad_review_task_id=10");
            statement.executeUpdate("UPDATE review_recovery_tasks SET review_recovery_task_worker=82 WHERE review_recovery_task_id=10");
            connection.commit();
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }

    Optional<Long> currentOrderId(long taskId) { return Optional.ofNullable(jdbc.queryForObject("SELECT order_id FROM command_tasks WHERE id=? FOR UPDATE",Long.class,taskId)); }
    Optional<Long> orderId(long taskId) { return Optional.ofNullable(jdbc.queryForObject("SELECT order_id FROM command_tasks WHERE id=?",Long.class,taskId)); }
    Optional<Long> owned(long taskId,String actor,boolean lock) {
        if(lock) { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();lockedChecks.incrementAndGet(); }
        return jdbc.query("SELECT t.id FROM command_tasks t JOIN command_orders o ON o.id=t.order_id WHERE t.id=? AND t.owner=? AND o.owner=t.owner"+(lock?" FOR UPDATE":""),
            (rs,row)->rs.getLong(1),taskId,actor).stream().findFirst();
    }
    void lockTask(long id) { jdbc.queryForObject("SELECT id FROM command_tasks WHERE id=? FOR UPDATE",Long.class,id); }
    BadReviewTask loadBad(long id) { return BadReviewTask.builder().id(id).order(Order.builder().id(10L).build()).status(BadReviewTaskStatus.NEW).taskText(text(id)).scheduledDate(currentTaskDate(id)).build(); }
    ReviewRecoveryTask loadRecovery(long id) { return ReviewRecoveryTask.builder().id(id).order(Order.builder().id(20L).build()).status(ReviewRecoveryTaskStatus.PLANNED)
        .recoveryText(text(id)).scheduledDate(currentTaskDate(id)).bot(Bot.builder().id(81L).build()).build(); }
    LocalDate currentTaskDate(long id) { return jdbc.queryForObject("SELECT scheduled_date FROM command_tasks WHERE id=?" + (TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "" : " FOR UPDATE"),java.sql.Date.class,id).toLocalDate(); }
    String text(long id) { return jdbc.queryForObject("SELECT text FROM command_tasks WHERE id=?",String.class,id); }
    int exclusions() { return jdbc.queryForObject("SELECT COUNT(*) FROM task_exclusions",Integer.class); }
    void transferInIndependentConnection(long taskId,String actor) {
        try(Connection connection=ds.getConnection()) {
            connection.setAutoCommit(false);
            try(var order=connection.prepareStatement("UPDATE command_orders SET owner=? WHERE id=(SELECT order_id FROM command_tasks WHERE id=?)");
                var task=connection.prepareStatement("UPDATE command_tasks SET owner=? WHERE id=?")) {
                order.setString(1,actor);order.setLong(2,taskId);order.executeUpdate();
                task.setString(1,actor);task.setLong(2,taskId);task.executeUpdate();connection.commit();
            }
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    @SuppressWarnings("unchecked") <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();
    }
    @SuppressWarnings("unchecked") <T> T construct(Class<T> type,Map<Class<?>,Object> dependencies) throws Exception {
        Constructor<?> constructor=Arrays.stream(type.getConstructors()).max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] arguments=Arrays.stream(constructor.getParameterTypes()).map(parameter -> dependencies.containsKey(parameter)?dependencies.get(parameter):mock(parameter)).toArray();
        return (T)constructor.newInstance(arguments);
    }
}
