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
class SharedReviewPublicationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383");

    enum Entry { WORKER, MANAGER, LEGACY }
    com.hunt.otziv.p_products.api.ReviewPublicationCommands commands;
    com.hunt.otziv.worker_activity.service.WorkerActivityService activity;
    com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService cellular;
    com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService preparation;
    JdbcTemplate jdbc;
    PlatformTransactionManager tm;
    TransactionTemplate tx;
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS review_actor_orders(id BIGINT PRIMARY KEY,counter INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS review_actor_rows(id BIGINT PRIMARY KEY,order_id BIGINT,owner VARCHAR(40),body VARCHAR(255),answer VARCHAR(255),note VARCHAR(255),vigul BOOLEAN,published BOOLEAN) ENGINE=InnoDB");
        jdbc.update("DELETE FROM review_actor_rows");
        jdbc.update("DELETE FROM review_actor_orders");
        jdbc.update("INSERT INTO review_actor_orders(id) VALUES (1),(2)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS publication_audit(actor VARCHAR(80),kind VARCHAR(80))");
        jdbc.update("DELETE FROM publication_audit");
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
            return Optional.of(loadOrder(id));
        });
        when(orders.findByIdForMutation(anyLong())).thenAnswer(i -> Optional.of(loadOrder(i.getArgument(0))));
        when(orders.save(any(Order.class))).thenAnswer(i -> {
            Order order=i.getArgument(0);jdbc.update("UPDATE review_actor_orders SET counter=? WHERE id=?",order.getCounter(),order.getId());return order;
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
        var settings=mock(AppSettingService.class);
        audit = mock(BusinessAuditService.class);
        doAnswer(i -> {jdbc.update("INSERT INTO publication_audit VALUES (?,?)",i.getArgument(0,Authentication.class).getName(),"business");return null;})
                .when(audit).recordSafely(any(Authentication.class),eq("review_published"),eq("review"),anyLong(),anyLong(),anyLong(),any(),any(),anyString());
        var counter=construct(com.hunt.otziv.p_products.service.OrderStatusCheckerServiceImpl.class,Map.of(OrderRepository.class,orders));
        publication = proxy(construct(OrderServiceImpl.class, Map.of(
                ReviewRepository.class, reviews, OrderRepository.class, orders,
                WorkerAssignmentMutationGuardService.class, guard, AppSettingService.class, settings,
                BusinessAuditService.class, audit, PlatformTransactionManager.class, tm,
                com.hunt.otziv.p_products.service.OrderStatusCheckerService.class,counter)));
        activity=mock(com.hunt.otziv.worker_activity.service.WorkerActivityService.class);
        doAnswer(i -> {jdbc.update("INSERT INTO publication_audit VALUES (?,?)",i.getArgument(0,Authentication.class).getName(),"worker");return null;})
                .when(activity).recordSafely(any(Authentication.class),any(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
        var read=mock(com.hunt.otziv.r_review.service.ReviewService.class);
        when(read.getReviewById(anyLong())).thenAnswer(i -> loadReview(i.getArgument(0),false));
        var mutation=proxy(new com.hunt.otziv.p_products.application.ReviewPublicationMutationService(publication,read,guard,activity));
        cellular=mock(com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService.class);
        preparation=mock(com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService.class);
        commands=new com.hunt.otziv.p_products.application.ReviewPublicationCommandService(mutation,read,
                mock(com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService.class),preparation,cellular,guard);
    }

    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(Entry.class)
    void eachEntryUsesActualDomainCounterAndExplicitAuditWithoutAmbientActor(Entry entry) throws Exception {
        invoke(entry,auth("worker","ROLE_WORKER"));
        assertThat(jdbc.queryForObject("SELECT published FROM review_actor_rows WHERE id=10",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT counter FROM review_actor_orders WHERE id=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT actor FROM publication_audit",String.class)).containsExactly("worker");
        if(entry==Entry.MANAGER)verifyNoInteractions(cellular);
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void foreignExplicitActorCannotBorrowAmbientAdminInAnyEntry(Entry entry) {
        SecurityContextHolder.getContext().setAuthentication(auth("admin","ROLE_ADMIN"));
        assertThatThrownBy(() -> invoke(entry,auth("other","ROLE_WORKER"))).isInstanceOf(RuntimeException.class);
        assertUnchanged();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_audit",Integer.class)).isZero();
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void lateCallerFailureRollsBackPublishedRowCounterAndAuditForEveryEntry(Entry entry) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            try {invoke(entry,auth("worker","ROLE_WORKER"));}catch(Exception error){throw new IllegalStateException(error);}
            throw new IllegalStateException("late caller failure");
        })).hasMessage("late caller failure");
        assertUnchanged();assertThat(jdbc.queryForObject("SELECT counter FROM review_actor_orders WHERE id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_audit",Integer.class)).isZero();
    }

    @Test void managerAndLegacyRejectMismatchedLockedParentBeforeDomainWrites() {
        var actor=auth("worker","ROLE_WORKER");
        assertThatThrownBy(() -> commands.publishManager(2L,10L,actor,"")).hasMessage("Отзыв не найден");
        assertThatThrownBy(() -> commands.publishLegacy(999L,1L,10L,actor)).hasMessage("Заказ не найден");
        assertUnchanged();verify(reviews,never()).save(any());
    }

    @Test void failureAfterActualPublicationBeforeSharedCommandCompletesRollsBackEverything() {
        doThrow(new IllegalStateException("late activity failure")).when(activity)
                .recordSafely(any(Authentication.class),any(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
        assertThatThrownBy(() -> commands.publishManager(1L,10L,auth("worker","ROLE_WORKER"),"")).hasMessage("late activity failure");
        assertUnchanged();assertThat(jdbc.queryForObject("SELECT counter FROM review_actor_orders WHERE id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_audit",Integer.class)).isZero();
        verifyNoInteractions(preparation);
    }

    private void invoke(Entry entry,Authentication actor) throws Exception {
        switch(entry) {
            case WORKER -> commands.publishWorker(10L,actor);
            case MANAGER -> commands.publishManager(1L,10L,actor,"sourcePage=fixture;");
            case LEGACY -> assertThat(commands.publishLegacy(100L,1L,10L,actor).published()).isTrue();
        }
    }
    private Order loadOrder(long id) {
        return Order.builder().id(id).amount(5).counter(jdbc.queryForObject("SELECT counter FROM review_actor_orders WHERE id=?",Integer.class,id))
                .company(com.hunt.otziv.c_companies.model.Company.builder().id(100L).build()).build();
    }

    private Optional<Long> binding(long reviewId, boolean currentRead) {
        return jdbc.query("SELECT order_id FROM review_actor_rows WHERE id=?" + (currentRead ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), reviewId).stream().findFirst();
    }
    private Review loadReview(long reviewId, boolean currentRead) {
        return jdbc.queryForObject("SELECT * FROM review_actor_rows WHERE id=?" + (currentRead ? " FOR UPDATE" : ""), (rs, n) -> {
            Order order = loadOrder(rs.getLong("order_id"));
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
