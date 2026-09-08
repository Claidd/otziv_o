package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowRefreshSignal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Application service is invoked without MVC, through the real Spring transaction interceptor. */
@Testcontainers
class WorkerOrderCommandsMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_commands").withUsername("root").withPassword("root");
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    WorkerOrderCommands commands;
    com.hunt.otziv.p_products.api.OrderStatusCommands sharedStatus;
    OrderService orders;
    ReviewService reviews;
    WorkerRiskEvaluationService risk;
    WorkerActivityEventRepository events;
    WorkerOrderActor worker = new WorkerOrderActor("worker",Set.of("WORKER"));
    UUID detailId = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        var tm = new DataSourceTransactionManager(ds); tx = new TransactionTemplate(tm);
        jdbc.execute("DROP TABLE IF EXISTS command_audit"); jdbc.execute("DROP TABLE IF EXISTS command_orders");
        jdbc.execute("CREATE TABLE command_orders(id BIGINT PRIMARY KEY,owner VARCHAR(40),note VARCHAR(255),status VARCHAR(80),waiting BOOLEAN)");
        jdbc.execute("CREATE TABLE command_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor VARCHAR(40),action VARCHAR(60))");
        jdbc.update("INSERT INTO command_orders VALUES (1,'worker','old','Новый',true)");
        var orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByIdForCounterUpdate(1L)).thenAnswer(invocation -> {
            jdbc.queryForObject("SELECT id FROM command_orders WHERE id=1 FOR UPDATE",Long.class);
            return Optional.of(loadOrder());
        });
        var locks = new OrderAggregateMutationLockService(orderRepository,
                mock(com.hunt.otziv.p_products.repository.OrderDetailsRepository.class),
                mock(com.hunt.otziv.r_review.repository.ReviewRepository.class));
        var ownership = mock(WorkerAssignmentMutationGuardRepository.class);
        when(ownership.countOwnedOrder(anyLong(),anyString())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_orders WHERE id=? AND owner=?",Long.class,invocation.getArgument(0),invocation.getArgument(1)));
        when(ownership.lockOwnedOrder(anyLong(),anyString())).thenAnswer(invocation -> jdbc.query(
                "SELECT id FROM command_orders WHERE id=? AND owner=? FOR UPDATE",(rs,row) -> rs.getLong(1),invocation.getArgument(0),invocation.getArgument(1)).stream().findFirst());
        var guard = new WorkerAssignmentMutationGuardService(ownership,mock(ManagerAccessService.class),locks);
        orders=mock(OrderService.class); reviews=mock(ReviewService.class);
        when(orders.getOrder(1L)).thenAnswer(invocation -> loadOrder());
        doAnswer(invocation -> {
            Order order=invocation.getArgument(0);
            jdbc.update("UPDATE command_orders SET note=?,waiting=? WHERE id=?",order.getZametka(),order.isWaitingForClient(),order.getId());
            return null;
        }).when(orders).save(any(Order.class));
        when(orders.changeStatusForOrder(eq(1L),anyString(),any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE command_orders SET status=? WHERE id=1",invocation.getArgument(1,String.class)); return true;
        });
        var users=mock(UserService.class);
        User user=User.builder().id(10L).username("worker").build();
        when(users.findByUserNameWithAssignments("worker")).thenReturn(Optional.of(user));
        events=mock(WorkerActivityEventRepository.class);
        when(events.save(any(WorkerActivityEvent.class))).thenAnswer(invocation -> {
            WorkerActivityEvent event=invocation.getArgument(0);
            jdbc.update("INSERT INTO command_audit(actor,action) VALUES (?,?)",event.getWorkerUsername(),event.getAction().name());
            return event;
        });
        risk=mock(WorkerRiskEvaluationService.class);
        var activity=new WorkerActivityService(events,users,risk,mock(WorkloadShadowRefreshSignal.class),tm);
        var reminders=mock(ScheduledClientMessageService.class);
        var shared=new OrderStatusCommandService(orders,mock(OrderDetailsService.class),reviews,guard,reminders,users,mock(WorkerService.class));
        var sharedProxy=new ProxyFactory(shared);sharedProxy.setProxyTargetClass(true);
        sharedProxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        sharedStatus=(com.hunt.otziv.p_products.api.OrderStatusCommands)sharedProxy.getProxy();
        var target=new WorkerOrderCommands(orders,guard,reminders,mock(CompanyService.class),activity,sharedStatus);
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        commands=(WorkerOrderCommands)proxy.getProxy();
    }

    @Test void explicitUnauthorizedActorCannotUseAmbientAdministrator() {
        SecurityContextHolder.getContext().setAuthentication(new WorkerOrderActor("admin",Set.of("ADMIN")).authentication());
        try {
            assertThatThrownBy(() -> commands.changeOrderNote(1L,"unauthorized",new WorkerOrderActor("stranger",Set.of())))
                    .isInstanceOf(WorkerOrderCommandException.class);
            assertThat(note()).isEqualTo("old"); assertThat(audits()).isZero();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void foreignWorkerCannotBypassOwnershipWithoutSecurityContext() {
        assertThatThrownBy(() -> commands.changeOrderNote(1L,"foreign",new WorkerOrderActor("other",Set.of("WORKER"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(note()).isEqualTo("old"); assertThat(audits()).isZero();
        verify(orders,never()).save(any());
    }

    @Test void wholeCommandRollbackRemovesAuditAndSuccessfulRetryWritesOnce() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            commands.changeOrderNote(1L,"new",worker);
            throw new IllegalStateException("outer command failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(note()).isEqualTo("old"); assertThat(audits()).isZero();
        verifyNoInteractions(risk);
        commands.changeOrderNote(1L,"new",worker);
        commands.changeOrderNote(1L,"new",worker);
        assertThat(note()).isEqualTo("new"); assertThat(audits()).isEqualTo(1);
        verify(risk,times(1)).evaluateSafely(any(),any());
    }

    @Test void failedPublicationDatesRollBackStatusAndWaitingTogether() {
        when(reviews.updateOrderDetailAndReviewAndPublishDate(any())).thenReturn(false);
        assertThatThrownBy(() -> commands.changeStatus(1L,"Публикация",new WorkerOrderActor("admin",Set.of("ADMIN"))))
                .isInstanceOf(WorkerOrderCommandException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM command_orders WHERE id=1",String.class)).isEqualTo("Новый");
        assertThat(jdbc.queryForObject("SELECT waiting FROM command_orders WHERE id=1",Boolean.class)).isTrue();
        assertThat(audits()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.hunt.otziv.p_products.api.OrderStatusCommands.EntryPoint.class)
    void everyStatusEntryRollsBackEarlierMutationWhenPublicationDatesThrow(com.hunt.otziv.p_products.api.OrderStatusCommands.EntryPoint entry) {
        when(reviews.updateOrderDetailAndReviewAndPublishDate(any())).thenThrow(new IllegalStateException("date persistence failed"));
        assertThatThrownBy(() -> sharedStatus.changeStatus(1L,null,"Публикация",new WorkerOrderActor("admin",Set.of("ADMIN")).authentication(),entry))
                .hasMessage("date persistence failed");
        assertThat(jdbc.queryForObject("SELECT status FROM command_orders WHERE id=1",String.class)).isEqualTo("Новый");
        assertThat(jdbc.queryForObject("SELECT waiting FROM command_orders WHERE id=1",Boolean.class)).isTrue();
    }

    @Test void managerWorkerEntryDoesNotInheritWorkerBoardWaitingRequirement() throws Exception {
        jdbc.update("UPDATE command_orders SET waiting=false WHERE id=1");
        assertThat(sharedStatus.changeStatus(1L,null,"В проверку",worker.authentication(),
                com.hunt.otziv.p_products.api.OrderStatusCommands.EntryPoint.MANAGER_BOARD)).isTrue();
        assertThatThrownBy(() -> sharedStatus.changeStatus(1L,null,"Оплачено",worker.authentication(),
                com.hunt.otziv.p_products.api.OrderStatusCommands.EntryPoint.MANAGER_BOARD)).isInstanceOf(WorkerOrderCommandException.class);
    }

    Order loadOrder() {
        return jdbc.queryForObject("SELECT * FROM command_orders WHERE id=1",(rs,row) -> {
            Order order=Order.builder().id(1L).zametka(rs.getString("note")).waitingForClient(rs.getBoolean("waiting"))
                    .status(OrderStatus.builder().title(rs.getString("status")).build()).build();
            OrderDetails detail=new OrderDetails(); detail.setId(detailId); detail.setOrder(order); order.setDetails(List.of(detail));
            return order;
        });
    }
    String note() { return jdbc.queryForObject("SELECT note FROM command_orders WHERE id=1",String.class); }
    int audits() { return jdbc.queryForObject("SELECT COUNT(*) FROM command_audit",Integer.class); }
}
