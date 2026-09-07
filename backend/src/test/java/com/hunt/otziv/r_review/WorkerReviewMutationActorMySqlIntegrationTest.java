package com.hunt.otziv.r_review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.service.OrderServiceImpl;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.edit.service.ReviewEditService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.nagul.service.ReviewNagulPolicy;
import com.hunt.otziv.r_review.nagul.service.ReviewNagulService;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Real edit/nagul/publication owners and ownership guard through Spring TX advice.
 * JDBC repository adapters exercise independent InnoDB REPEATABLE READ connections;
 * no MVC or ambient actor is required. Provider and unrelated delivery adapters are mocks.
 */
@Testcontainers
class WorkerReviewMutationActorMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383");

    enum Action { TEXT, ANSWER, NOTE, NAGUL, PUBLISH }
    JdbcTemplate jdbc;
    PlatformTransactionManager tm;
    TransactionTemplate tx;
    ReviewEditService edits;
    ReviewNagulService nagul;
    OrderServiceImpl publication;
    ReviewRepository reviews;
    WorkerAssignmentMutationGuardRepository ownership;
    BusinessAuditService audit;
    volatile CountDownLatch lockAttempt;

    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        tm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(tm);
        jdbc.execute("CREATE TABLE IF NOT EXISTS review_actor_orders(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS review_actor_rows(id BIGINT PRIMARY KEY,order_id BIGINT,owner VARCHAR(40),body VARCHAR(255),answer VARCHAR(255),note VARCHAR(255),vigul BOOLEAN,published BOOLEAN) ENGINE=InnoDB");
        jdbc.update("DELETE FROM review_actor_rows");
        jdbc.update("DELETE FROM review_actor_orders");
        jdbc.update("INSERT INTO review_actor_orders VALUES (1),(2)");
        jdbc.update("INSERT INTO review_actor_rows VALUES (10,1,'worker','Original review text','old answer','old note',false,false)");
        reviews = mock(ReviewRepository.class);
        when(reviews.findOrderIdByReviewId(anyLong())).thenAnswer(i -> binding(i.getArgument(0), false));
        when(reviews.findById(anyLong())).thenAnswer(i -> Optional.of(loadReview(i.getArgument(0), false)));
        when(reviews.findByIdForPublication(anyLong())).thenAnswer(i -> Optional.of(loadReview(i.getArgument(0), true)));
        when(reviews.countPublishedByOrderId(anyLong())).thenAnswer(i -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_actor_rows WHERE order_id=? AND published=true", Integer.class, i.getArgument(0, Long.class)));
        when(reviews.save(any(Review.class))).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Review row = i.getArgument(0);
            jdbc.update("UPDATE review_actor_rows SET body=?,answer=?,vigul=?,published=? WHERE id=?",
                    row.getText(), row.getAnswer(), row.isVigul(), row.isPublish(), row.getId());
            return row;
        });
        var orders = mock(OrderRepository.class);
        when(orders.findByIdForCounterUpdate(anyLong())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (lockAttempt != null) lockAttempt.countDown();
            Long id = jdbc.queryForObject("SELECT id FROM review_actor_orders WHERE id=? FOR UPDATE", Long.class, i.getArgument(0, Long.class));
            return Optional.of(Order.builder().id(id).amount(5).build());
        });
        var locks = proxy(new OrderAggregateMutationLockService(orders, mock(OrderDetailsRepository.class), reviews));
        ownership = mock(WorkerAssignmentMutationGuardRepository.class);
        when(ownership.findOrderIdByReviewId(anyLong())).thenAnswer(i -> binding(i.getArgument(0), false));
        when(ownership.findCurrentOrderIdByReviewId(anyLong())).thenAnswer(i -> binding(i.getArgument(0), true));
        when(ownership.countOwnedReview(anyLong(), anyString())).thenAnswer(i -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_actor_rows WHERE id=? AND owner=? AND published=false",
                Long.class, i.getArgument(0), i.getArgument(1)));
        when(ownership.lockOwnedReview(anyLong(), anyString())).thenAnswer(i -> jdbc.query(
                "SELECT id FROM review_actor_rows WHERE id=? AND owner=? AND published=false FOR UPDATE",
                (rs, n) -> rs.getLong(1), i.getArgument(0), i.getArgument(1)).stream().findFirst());
        var guard = new WorkerAssignmentMutationGuardService(ownership, mock(ManagerAccessService.class), locks);
        var details = mock(OrderDetailsService.class);
        doAnswer(i -> {
            OrderDetails detail = i.getArgument(0);
            jdbc.update("UPDATE review_actor_rows SET note=? WHERE id=10", detail.getComment());
            return null;
        }).when(details).save(any(OrderDetails.class));
        edits = proxy(new ReviewEditService(reviews, details, guard, locks));
        var users = mock(UserService.class);
        var workerService = mock(WorkerService.class);
        User user = new User(); user.setId(20L); user.setUsername("worker");
        Role role = new Role(); role.setName("ROLE_WORKER"); user.setRoles(List.of(role));
        when(users.findByUserName("worker")).thenReturn(Optional.of(user));
        Worker worker = new Worker(); worker.setId(30L);
        when(workerService.getWorkerByUserId(20L)).thenReturn(worker);
        var settings = mock(AppSettingService.class);
        nagul = proxy(new ReviewNagulService(reviews, users, workerService, new ReviewNagulPolicy(), settings, guard));
        audit = mock(BusinessAuditService.class);
        publication = proxy(construct(OrderServiceImpl.class, Map.of(
                ReviewRepository.class, reviews, OrderRepository.class, orders,
                WorkerAssignmentMutationGuardService.class, guard, AppSettingService.class, settings,
                BusinessAuditService.class, audit, PlatformTransactionManager.class, tm)));
    }

    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(Action.class)
    void explicitForeignActorCannotBorrowAmbientAdministrator(Action action) {
        SecurityContextHolder.getContext().setAuthentication(auth("admin", "ROLE_ADMIN"));
        assertThatThrownBy(() -> invoke(action, auth("other", "ROLE_WORKER")))
                .isInstanceOf(ResponseStatusException.class);
        assertUnchanged();
        verify(reviews, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test void editOverloadsUseExplicitActorWithoutSecurityContext() {
        assertThat(edits.updateReviewText(1L, 10L, "updated", auth("worker", "ROLE_WORKER"))).isTrue();
        assertThat(edits.updateReviewAnswer(1L, 10L, "answer", auth("worker", "ROLE_WORKER"))).isTrue();
        assertThat(edits.updateReviewNote(1L, 10L, "note", auth("worker", "ROLE_WORKER"))).isTrue();
        assertThat(jdbc.queryForMap("SELECT body,answer,note FROM review_actor_rows WHERE id=10"))
                .containsEntry("body", "updated").containsEntry("answer", "answer").containsEntry("note", "note");
    }

    @Test void explicitNagulKeepsUsernameBoundToAuthorizedActor() {
        assertThatThrownBy(() -> nagul.performNagulWithExceptions(10L, "admin", auth("worker", "ROLE_WORKER")))
                .isInstanceOf(ResponseStatusException.class);
        assertUnchanged();
        nagul.performNagulWithExceptions(10L, "worker", auth("worker", "ROLE_WORKER"));
        assertThat(jdbc.queryForObject("SELECT vigul FROM review_actor_rows WHERE id=10", Boolean.class)).isTrue();
    }

    @Test void explicitPublicationUsesSameBusinessOwnerWithoutAmbientActor() throws Exception {
        Authentication actor = auth("worker", "ROLE_WORKER");
        assertThat(publication.changeStatusAndOrderCounter(10L, actor)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published FROM review_actor_rows WHERE id=10", Boolean.class)).isTrue();
        verify(reviews).save(any(Review.class));
        verify(audit).recordSafely(same(actor), eq("review_published"), eq("review"), eq(10L),
                eq(1L), eq(10L), eq(false), eq(true), eq("manual publish button"));
        verifyNoMoreInteractions(audit);
    }

    @Test void editAndPublicationRemainInCallerTransactionOnLateFailure() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            edits.updateReviewText(1L, 10L, "changed before rollback", auth("worker", "ROLE_WORKER"));
            edits.updateReviewNote(1L, 10L, "changed note", auth("worker", "ROLE_WORKER"));
            try { publication.changeStatusAndOrderCounter(10L, auth("worker", "ROLE_WORKER")); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            throw new IllegalStateException("late caller failure");
        })).isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test void ownershipChangedWhileWaitingForOrderLockRejectsOldActorUnderRepeatableRead() throws Exception {
        assertConcurrentReassignmentRejected(false);
    }

    @Test void changedReviewParentIsRejectedAfterCanonicalOrderLock() throws Exception {
        assertConcurrentReassignmentRejected(true);
    }

    private void assertConcurrentReassignmentRejected(boolean changeParent) throws Exception {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
        var writerLocked = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        lockAttempt = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM review_actor_orders WHERE id=1 FOR UPDATE", Long.class);
                if (changeParent) {
                    jdbc.queryForObject("SELECT id FROM review_actor_orders WHERE id=2 FOR UPDATE", Long.class);
                    jdbc.update("UPDATE review_actor_rows SET order_id=2 WHERE id=10");
                } else {
                    jdbc.update("UPDATE review_actor_rows SET owner='other' WHERE id=10");
                }
                writerLocked.countDown();
                await(releaseWriter);
            }));
            assertThat(writerLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var command = pool.submit(() -> {
                try {
                    edits.updateReviewText(1L, 10L, "must not be written", auth("worker", "ROLE_WORKER"));
                    return (Throwable) null;
                } catch (Throwable failure) { return failure; }
                finally { SecurityContextHolder.clearContext(); }
            });
            try { assertThat(lockAttempt.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { releaseWriter.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(command.get(10, TimeUnit.SECONDS)).isInstanceOf(ResponseStatusException.class);
            assertUnchanged();
        } finally { releaseWriter.countDown(); lockAttempt = null; }
    }

    private void invoke(Action action, Authentication actor) throws Exception {
        switch (action) {
            case TEXT -> edits.updateReviewText(1L, 10L, "foreign", actor);
            case ANSWER -> edits.updateReviewAnswer(1L, 10L, "foreign", actor);
            case NOTE -> edits.updateReviewNote(1L, 10L, "foreign", actor);
            case NAGUL -> nagul.performNagulWithExceptions(10L, actor.getName(), actor);
            case PUBLISH -> publication.changeStatusAndOrderCounter(10L, actor);
        }
    }

    private Optional<Long> binding(long reviewId, boolean currentRead) {
        return jdbc.query("SELECT order_id FROM review_actor_rows WHERE id=?" + (currentRead ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), reviewId).stream().findFirst();
    }
    private Review loadReview(long reviewId, boolean currentRead) {
        return jdbc.queryForObject("SELECT * FROM review_actor_rows WHERE id=?" + (currentRead ? " FOR UPDATE" : ""), (rs, n) -> {
            Order order = Order.builder().id(rs.getLong("order_id")).amount(5).build();
            OrderDetails detail = new OrderDetails(); detail.setOrder(order); detail.setComment(rs.getString("note"));
            Bot bot = new Bot(); bot.setId(7L); bot.setLogin("fixture"); bot.setPassword("fixture"); bot.setFio("Иван Петров");
            Review row = new Review(); row.setId(reviewId); row.setOrderDetails(detail); row.setBot(bot);
            row.setText(rs.getString("body")); row.setAnswer(rs.getString("answer"));
            row.setVigul(rs.getBoolean("vigul")); row.setPublish(rs.getBoolean("published"));
            return row;
        }, reviewId);
    }
    private void assertUnchanged() {
        assertThat(jdbc.queryForMap("SELECT body,answer,note,vigul,published FROM review_actor_rows WHERE id=10"))
                .containsEntry("body", "Original review text").containsEntry("answer", "old answer")
                .containsEntry("note", "old note").containsEntry("vigul", false).containsEntry("published", false);
    }
    private Authentication auth(String name, String role) { return new TestingAuthenticationToken(name, null, role); }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tm, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    @SuppressWarnings("unchecked") private <T> T construct(Class<T> type, Map<Class<?>, Object> supplied) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(parameter -> supplied.containsKey(parameter) ? supplied.get(parameter) : mock(parameter)).toArray();
        return (T) constructor.newInstance(arguments);
    }
}
