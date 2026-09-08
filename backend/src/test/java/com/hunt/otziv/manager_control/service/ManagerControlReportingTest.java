package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerControlReportingTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 12, 0);
    @Mock private BadReviewTaskService badReviewTasks;
    @Mock private ReviewRecoveryTaskService recoveryTasks;
    @Mock private ReviewRepository reviews;
    @Mock private OrderRepository orders;
    @Mock private WorkerRiskIncidentRepository risks;
    @Mock private UserRepository users;
    @Mock private ManagerDailyControlConcreteItemRepository concreteItems;
    @Mock private ManagerDailyControlEventRepository events;
    @Mock private AppSettingService settings;
    private ManagerControlWorkerTaskLookup taskLookup;
    private ManagerControlWorkerExplanationQueries explanations;
    private ManagerControlQualityQueries quality;
    private ManagerDailyControl control;

    @BeforeEach
    void setUp() {
        taskLookup = new ManagerControlWorkerTaskLookup(badReviewTasks, recoveryTasks, reviews, orders, risks, users);
        explanations = new ManagerControlWorkerExplanationQueries(concreteItems, risks, users, taskLookup);
        quality = new ManagerControlQualityQueries(events, settings);
        control = new ManagerDailyControl();
        control.setId(100L);
        control.setManagerUserId(17L);
    }

    @Test
    void unsynchronizedControlDoesNotQueryUnscopedHistory() {
        assertThat(explanations.workerExplanationStats(null, NOW)).isEmpty();
        assertThat(explanations.workerExplanationStats(new ManagerDailyControl(), NOW)).isEmpty();
        verifyNoInteractions(concreteItems, risks, users, orders, reviews);
    }

    @Test
    void reportKeepsOriginalRecipientAndUsesDeliveryTimeForSla() {
        var item = concrete("WORKER_ORDER_NEW", 70L);
        item.setWorkerNotificationUserId(11L);
        item.setWorkerNotificationAttemptedAt(NOW.minusHours(8));
        item.setWorkerNotificationSentAt(NOW.minusHours(2));
        when(concreteItems.findByControl(control)).thenReturn(List.of(item));
        when(users.findById(11L)).thenReturn(Optional.of(user(11L, "Первый специалист")));

        var report = explanations.workerExplanationStats(control, NOW);

        assertThat(report).singleElement().satisfies(row -> {
            assertThat(row.workerUserId()).isEqualTo(11L);
            assertThat(row.requestCount()).isEqualTo(1);
            assertThat(row.unansweredCount()).isEqualTo(1);
            assertThat(row.overdueCount()).isZero();
        });
        verifyNoInteractions(orders, reviews, risks);
        assertThat(item.getWorkerNotificationUserId()).isEqualTo(11L);
        verify(concreteItems, never()).save(any());
    }

    @Test
    void missingOriginalRecipientDoesNotAttributeHistoricalRequestToReplacementWorker() {
        var item = concrete("WORKER_ORDER_NEW", 70L);
        item.setWorkerNotificationUserId(99L);
        item.setWorkerNotificationAttemptedAt(NOW.minusHours(4));
        when(concreteItems.findByControl(control)).thenReturn(List.of(item));
        when(users.findById(99L)).thenReturn(Optional.empty());

        assertThat(explanations.workerExplanationStats(control, NOW)).isEmpty();
        verifyNoInteractions(orders, reviews, risks);
    }

    @Test
    void riskResponseIsUnansweredUntilAcceptedAndIncludesLegacyRiskRequest() {
        var item = concrete("RISK", 70L);
        var risk = new WorkerRiskIncident();
        risk.setWorkerUserId(11L);
        risk.setResolutionAction(WorkerRiskResolutionAction.EXPLANATION_REQUESTED);
        risk.setExplanationRequestedAt(NOW.minusHours(3));
        risk.setWorkerExplanationAt(NOW.minusHours(2));
        when(concreteItems.findByControl(control)).thenReturn(List.of(item));
        when(risks.findById(70L)).thenReturn(Optional.of(risk));
        when(users.findById(11L)).thenReturn(Optional.of(user(11L, "Специалист")));

        assertThat(explanations.workerExplanationStats(control, NOW)).singleElement().satisfies(row -> {
            assertThat(row.unansweredCount()).isEqualTo(1);
            assertThat(row.overdueCount()).isEqualTo(1);
            assertThat(row.averageResponseMinutes()).isZero();
        });
        risk.setExplanationAcceptedAt(NOW.minusMinutes(30));
        assertThat(explanations.workerExplanationStats(control, NOW)).singleElement().satisfies(row -> {
            assertThat(row.unansweredCount()).isZero();
            assertThat(row.overdueCount()).isZero();
            assertThat(row.averageResponseMinutes()).isEqualTo(150);
        });
        verify(risks, never()).save(any());
    }

    @Test
    void aggregateSortsOverdueFirstAndDoesNotCountUnrequestedOrNonSpecialistCards() {
        var overdue = concrete("PUBLISH_REVIEW", 70L);
        overdue.setWorkerNotificationUserId(11L);
        overdue.setWorkerNotificationAttemptedAt(NOW.minusHours(3));
        var answered = concrete("NAGUL_REVIEW", 71L);
        answered.setWorkerNotificationUserId(12L);
        answered.setWorkerNotificationAttemptedAt(NOW.minusMinutes(10));
        answered.setWorkerExplanationAt(NOW.minusMinutes(5));
        var unrequested = concrete("NAGUL_REVIEW", 72L);
        var nonSpecialist = concrete("ORDER", 73L);
        nonSpecialist.setWorkerNotificationUserId(12L);
        nonSpecialist.setWorkerNotificationAttemptedAt(NOW.minusHours(8));
        when(concreteItems.findByControl(control)).thenReturn(List.of(answered, unrequested, nonSpecialist, overdue));
        when(users.findById(11L)).thenReturn(Optional.of(user(11L, "Я")));
        when(users.findById(12L)).thenReturn(Optional.of(user(12L, "А")));

        var result = explanations.workerExplanationStats(control, NOW);

        assertThat(result).extracting(row -> row.workerUserId()).containsExactly(11L, 12L);
        assertThat(result.get(0).overdueCount()).isEqualTo(1);
        assertThat(result.get(1).averageResponseMinutes()).isEqualTo(5);
        assertThat(result.get(1).requestCount()).isEqualTo(1);
        verifyNoInteractions(orders, reviews, risks);
    }

    @Test
    void lookupPrefersReviewWorkerAndFallsBackToOrderOnlyWhenUnassigned() {
        var reviewWorker = new Worker();
        reviewWorker.setUser(user(11L, "На отзыве"));
        var orderWorker = new Worker();
        orderWorker.setUser(user(12L, "На заказе"));
        var order = new Order();
        order.setId(20L);
        order.setWorker(orderWorker);
        var details = new OrderDetails();
        details.setOrder(order);
        var review = new Review();
        review.setWorker(reviewWorker);
        review.setOrderDetails(details);
        when(reviews.findById(70L)).thenReturn(Optional.of(review));
        var item = concrete("PUBLISH_REVIEW", 70L);

        assertThat(taskLookup.workerUserForTask(item).getId()).isEqualTo(11L);
        assertThat(taskLookup.orderForTask(item)).isSameAs(order);
        assertThat(taskLookup.orderIdForTask(item)).isEqualTo(20L);
        review.setWorker(null);
        assertThat(taskLookup.workerUserForTask(item).getId()).isEqualTo(12L);
        verifyNoInteractions(orders);
    }

    @Test
    void unsupportedOrMissingTaskDoesNotQueryAnotherDomain() {
        assertThat(taskLookup.workerUserForTask(null)).isNull();
        assertThat(taskLookup.workerUserForTask(concrete("COMMON_INVOICE", 50L))).isNull();
        assertThat(taskLookup.orderForTask(concrete("UNRECOGNIZED", 50L))).isNull();
        assertThat(taskLookup.orderIdForTask(concrete("WORKER_ORDER_NEW", 51L))).isEqualTo(51L);
        verifyNoInteractions(badReviewTasks, recoveryTasks, reviews, orders, risks, users);
    }

    @Test
    void qualityIncludesStagePenaltiesButReturnsResultWithoutMutatingControl() {
        var critical = action(ManagerDailyControlSeverity.CRITICAL, ManagerDailyControlItemStatus.OPEN);
        var warning = action(ManagerDailyControlSeverity.WARNING, ManagerDailyControlItemStatus.OPEN);
        var deferred = action(ManagerDailyControlSeverity.WARNING, ManagerDailyControlItemStatus.DEFERRED);
        control.setQualityScore(99);
        control.setRiskScore(1);
        control.setQualityGrade("A");
        when(events.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of());

        var score = quality.evaluate(control, List.of(critical, warning, deferred));

        assertThat(score.riskScore()).isEqualTo(40);
        assertThat(score.qualityScore()).isEqualTo(42);
        assertThat(score.qualityGrade()).isEqualTo("D");
        assertThat(score.fastClickRisk()).isFalse();
        assertThat(control.getQualityScore()).isEqualTo(99);
        assertThat(control.getRiskScore()).isEqualTo(1);
        assertThat(control.getQualityGrade()).isEqualTo("A");
        verify(events, never()).save(any());
        verifyNoInteractions(settings);
    }

    @Test
    void fastClickWindowIsInclusiveAndOnlyUsesThisManagersClientResolutions() {
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(events.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of(
                closure(17L, "UNANSWERED_CLIENT_MESSAGES", NOW),
                closure(17L, "SUSPICIOUS_CLIENT_CLOSURES", NOW.minusSeconds(5)),
                closure(17L, "UNANSWERED_CLIENT_MESSAGES", NOW.minusSeconds(10)),
                closure(99L, "UNANSWERED_CLIENT_MESSAGES", NOW),
                closure(17L, "WORKER_OVERDUE_PUBLICATIONS", NOW)));

        assertThat(quality.hasFastClickRisk(control)).isTrue();

        when(events.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of(
                closure(17L, "UNANSWERED_CLIENT_MESSAGES", NOW),
                closure(17L, "SUSPICIOUS_CLIENT_CLOSURES", NOW.minusSeconds(5)),
                closure(17L, "UNANSWERED_CLIENT_MESSAGES", NOW.minusSeconds(11)),
                closure(99L, "UNANSWERED_CLIENT_MESSAGES", NOW),
                closure(17L, "WORKER_OVERDUE_PUBLICATIONS", NOW)));
        assertThat(quality.hasFastClickRisk(control)).isFalse();
    }

    @Test
    void eventProjectionPreservesRepositoryOrderAndOptionalItemAction() {
        var closure = closure(17L, "UNANSWERED_CLIENT_MESSAGES", NOW);
        closure.setId(2L);
        var started = new ManagerDailyControlEvent();
        started.setId(1L);
        started.setEventType(ManagerDailyControlEventType.CONTROL_CREATED);
        started.setCreatedAt(NOW.minusHours(1));
        when(events.findByControlOrderByCreatedAtDesc(control)).thenReturn(List.of(closure, started));

        var history = quality.events(control);

        assertThat(history).extracting(row -> row.eventId()).containsExactly(2L, 1L);
        assertThat(history.get(1).itemId()).isNull();
        assertThat(history.get(1).actionType()).isNull();
        verify(events, never()).save(any());
    }

    private static ManagerDailyControlConcreteItem concrete(String type, Long id) {
        var item = new ManagerDailyControlConcreteItem();
        item.setEntityType(type);
        item.setEntityId(id);
        return item;
    }

    private static User user(long id, String name) {
        var user = new User();
        user.setId(id);
        user.setFio(name);
        return user;
    }

    private static ManagerDailyControlItem action(ManagerDailyControlSeverity severity, ManagerDailyControlItemStatus status) {
        var item = new ManagerDailyControlItem();
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setSeverity(severity);
        item.setStatus(status);
        item.setCount(1);
        return item;
    }

    private static ManagerDailyControlEvent closure(long actor, String reason, LocalDateTime at) {
        var item = action(ManagerDailyControlSeverity.CRITICAL, ManagerDailyControlItemStatus.RESOLVED);
        item.setReasonCode(reason);
        var event = new ManagerDailyControlEvent();
        event.setActorUserId(actor);
        event.setItem(item);
        event.setCreatedAt(at);
        event.setActionType(ManagerDailyControlActionType.RESOLVED);
        event.setEventType(ManagerDailyControlEventType.ITEM_RESOLVED);
        return event;
    }
}
