package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.security.credentials.service.CredentialRevealService;
import com.hunt.otziv.u_users.service.*;
import com.hunt.otziv.worker_activity.service.*;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Invokes real application commands and policies without MVC or an ambient authenticated request. */
class WorkerRemainingCommandsTest {
    BadReviewTaskService bad=mock(BadReviewTaskService.class);
    ReviewRecoveryTaskService recovery=mock(ReviewRecoveryTaskService.class);
    ReviewService reviews=mock(ReviewService.class);
    OrderService orders=mock(OrderService.class);
    WorkerActivityService activity=mock(WorkerActivityService.class);
    WorkerCellularAccessService cellular=mock(WorkerCellularAccessService.class);
    WorkerAssignmentMutationGuardService guard=mock(WorkerAssignmentMutationGuardService.class);
    WorkerPublicationGateService gate=mock(WorkerPublicationGateService.class);
    WorkerCredentialPreparationService preparation=mock(WorkerCredentialPreparationService.class);
    UserService users=mock(UserService.class);
    ManagerService managers=mock(ManagerService.class);
    WorkerService workers=mock(WorkerService.class);
    CredentialRevealService reveal=mock(CredentialRevealService.class);
    WorkerOrderActor worker=new WorkerOrderActor("worker-a",Set.of("WORKER"));
    WorkerTaskEditingCommands editing=new WorkerTaskEditingCommands(bad,recovery,activity,cellular,guard,new WorkerTaskSchedulePolicy(bad,recovery));
    WorkerStaffAccessPolicy staff=new WorkerStaffAccessPolicy(users,managers,workers);
    WorkerTaskAssignmentCommands assignment=new WorkerTaskAssignmentCommands(bad,recovery,guard,staff);
    WorkerTaskAccountCommands accounts=new WorkerTaskAccountCommands(bad,recovery,activity,cellular,guard);
    WorkerTaskCompletionCommands completion=new WorkerTaskCompletionCommands(users,bad,recovery,activity,cellular,guard);
    WorkerReviewAccessPolicy access=new WorkerReviewAccessPolicy(reviews,cellular,guard);
    WorkerReviewContentCommands content=new WorkerReviewContentCommands(reviews,activity,access);
    WorkerReviewPublicationCommands publication=new WorkerReviewPublicationCommands(new ReviewPublicationCommandService(new ReviewPublicationMutationService(orders,reviews,guard,activity),reviews,gate,preparation,cellular,guard),reviews,gate,activity,preparation,cellular,guard);
    WorkerCredentialCommands credentials=new WorkerCredentialCommands(reviews,bad,recovery,gate,activity,preparation,cellular,guard,reveal,access,mock(org.springframework.transaction.PlatformTransactionManager.class));

    @AfterEach void clearAmbient() { SecurityContextHolder.clearContext(); }

    @TestFactory Stream<DynamicTest> everyEntryRejectsUnauthorizedExplicitActorEvenWithAmbientAdministrator() {
        return List.of(editing,assignment,accounts,completion,content,publication,credentials).stream().flatMap(owner ->
            Arrays.stream(owner.getClass().getDeclaredMethods())
                .filter(method -> Arrays.asList(method.getParameterTypes()).contains(WorkerOrderActor.class))
                .map(method -> DynamicTest.dynamicTest(method.getName(), () -> {
                    SecurityContextHolder.getContext().setAuthentication(new WorkerOrderActor("admin",Set.of("ADMIN")).authentication());
                    try {
                        Object[] args=Arrays.stream(method.getParameterTypes()).map(type -> type==WorkerOrderActor.class
                            ? new WorkerOrderActor("stranger",Set.of()) : type==Long.class ? 1L : null).toArray();
                        assertThatThrownBy(() -> method.invoke(owner,args)).isInstanceOf(InvocationTargetException.class)
                            .cause().isInstanceOf(WorkerOrderCommandException.class)
                            .hasMessage("Операция недоступна");
                        verifyNoInteractions(bad,recovery,reviews,orders,activity,cellular,guard,gate,preparation,users,managers,workers,reveal);
                    } finally { SecurityContextHolder.clearContext(); }
                })));
    }

    @Test void plainWorkerCannotAssignEvenWhenAmbientAdministrator() {
        SecurityContextHolder.getContext().setAuthentication(new WorkerOrderActor("admin",Set.of("ADMIN")).authentication());
        assertThatThrownBy(() -> assignment.reassignBadReviewTask(1L,2L,worker)).isInstanceOf(WorkerOrderCommandException.class);
        assertThatThrownBy(() -> assignment.reassignRecoveryTask(1L,2L,worker)).isInstanceOf(WorkerOrderCommandException.class);
        verifyNoInteractions(bad,recovery,guard,workers);
    }

    @Test void omittedDatesPreserveDistinctLegacyTaskContractsAndCarryActorIntoMutation() {
        LocalDate today=LocalDate.of(2026,9,7);
        when(bad.getTask(1L)).thenReturn(BadReviewTask.builder().scheduledDate(today).build());
        when(recovery.getTask(2L)).thenReturn(ReviewRecoveryTask.builder().scheduledDate(today).build());
        editing.updateBadReviewTask(1L,new WorkerTaskEditingCommands.BadTaskUpdateRequest("text",null),worker);
        editing.updateRecoveryTask(2L,new WorkerTaskEditingCommands.RecoveryTaskUpdateRequest("text","answer",null),worker);
        verify(bad).updateTask(eq(1L),eq("text"),eq(today),argThat(auth -> "worker-a".equals(auth.getName())));
        verify(recovery).updateTask(eq(2L),eq("text"),eq("answer"),isNull(),argThat(auth -> "worker-a".equals(auth.getName())));
    }

    @Test void workerCannotChangeScheduleAndFailureCannotEmitSuccessAudit() {
        when(bad.getTask(1L)).thenReturn(BadReviewTask.builder().scheduledDate(LocalDate.of(2026,9,7)).build());
        assertThatThrownBy(() -> editing.updateBadReviewTask(1L,
            new WorkerTaskEditingCommands.BadTaskUpdateRequest("text",LocalDate.of(2026,9,8)),worker))
            .isInstanceOf(WorkerOrderCommandException.class).extracting("statusCode").isEqualTo(403);
        verify(bad,never()).updateTask(any(),any(),any(),any()); verifyNoInteractions(activity);
    }

    @ParameterizedTest @ValueSource(ints={401,404,409,422,429,500,503})
    void legacyDomainStatusAndReasonSurviveBoundaryWithoutSuccessAudit(int status) {
        var original=new ResponseStatusException(HttpStatusCode.valueOf(status),"provider/domain reason");
        when(recovery.changeTaskBot(eq(1L),any(Authentication.class))).thenThrow(original);
        WorkerOrderCommandException failure=catchThrowableOfType(WorkerOrderCommandException.class,() -> accounts.changeRecoveryTaskBot(1L,worker));
        assertThat(failure.statusCode()).isEqualTo(status);
        assertThat(failure.getMessage()).isEqualTo("provider/domain reason");
        assertThat(failure.getCause()).isSameAs(original); verifyNoInteractions(activity);
    }

    @Test void spoofedNewSourceCannotBypassProtectedReviewPolicy() {
        Review review=new Review(); review.setId(5L); review.setVigul(true);
        Order order=Order.builder().status(OrderStatus.builder().title("Публикация").build()).build();
        OrderDetails details=new OrderDetails(); details.setOrder(order); review.setOrderDetails(details);
        access.enforceReviewSourceAccess(review,"new",worker.authentication());
        verify(guard).assertReview(eq(5L),argThat(auth -> auth.getName().equals("worker-a")));
        verify(cellular).enforceSection(eq("publish"),argThat(auth -> auth.getName().equals("worker-a")));
        verify(cellular,never()).enforceSection(eq("new"),any());
    }
}
