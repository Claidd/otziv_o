package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowLateFirstAvailabilityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final LocalDateTime HISTORICAL_SOURCE =
            LocalDateTime.of(2026, 2, 25, 10, 0);

    @Test
    void tenBackdatedCardsFirstTrackedAfterCutoffBelongToNextDay() {
        LocalDateTime firstTracked = DAY.atTime(23, 9, 23);
        LocalDateTime observed = DAY.atTime(23, 12, 8);
        LocalDateTime available = WorkloadShadowProjectionService.effectiveOrderAvailability(
                HISTORICAL_SOURCE, firstTracked, HISTORICAL_SOURCE, 1L, 1L, observed,
                false
        );
        assertEquals(firstTracked, available);

        List<WorkloadShadowProjectionService.WorkBatch> cards = LongStream.range(500001, 500011)
                .mapToObj(reviewId -> card(reviewId, available))
                .toList();
        Map<String, WorkloadShadowProjectionService.BatchDecision> evening = classify(
                cards, Map.of(), DAY, observed, DAY.atTime(22, 55)
        );

        assertEquals(10, evening.size());
        assertTrue(evening.values().stream().allMatch(decision ->
                decision.decisionCode() == WorkloadShadowProjectionService.DecisionCode.LATE
                        && decision.decisionOrigin()
                        == WorkloadShadowProjectionService.DecisionOrigin.AFTER_CUTOFF));

        Map<String, WorkloadShadowProjectionService.BatchDecision> nextDay = classify(
                cards, Map.of(), DAY.plusDays(1), DAY.plusDays(1).atTime(0, 9), null
        );
        assertEquals(10, nextDay.size());
        assertTrue(nextDay.values().stream().allMatch(decision ->
                decision.decisionCode() == WorkloadShadowProjectionService.DecisionCode.MANDATORY
                        && decision.decisionOrigin()
                        == WorkloadShadowProjectionService.DecisionOrigin.CARRY_OVER));
    }

    @Test
    void genuinelyOldCarryOverRemainsMandatoryAfterDelayedObservation() {
        LocalDateTime available = WorkloadShadowProjectionService.effectiveOrderAvailability(
                HISTORICAL_SOURCE,
                DAY.minusDays(7).atTime(13, 0),
                HISTORICAL_SOURCE,
                1L,
                1L,
                DAY.atTime(23, 12),
                false
        );
        assertEquals(DAY.minusDays(7).atTime(13, 0), available);
        var decision = classify(
                List.of(card(500001, available)), Map.of(), DAY,
                DAY.atTime(23, 12), DAY.atTime(21, 30)
        ).get("NEW:500001");
        assertEquals(WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                decision.decisionCode());
        assertEquals(WorkloadShadowProjectionService.DecisionOrigin.CARRY_OVER,
                decision.decisionOrigin());
    }

    @Test
    void reassignedOrderCannotInheritPreviousSpecialistsAvailability() {
        LocalDateTime observed = DAY.atTime(23, 12);
        assertEquals(observed, WorkloadShadowProjectionService.effectiveOrderAvailability(
                HISTORICAL_SOURCE,
                DAY.minusDays(30).atTime(12, 0),
                HISTORICAL_SOURCE,
                2L,
                1L,
                observed,
                false
        ));
    }

    @Test
    void reopeningClientWaitBeforeLifecycleSyncUsesCurrentObservation() {
        LocalDateTime observed = DAY.atTime(23, 12);
        LocalDateTime available = WorkloadShadowProjectionService.effectiveOrderAvailability(
                HISTORICAL_SOURCE,
                DAY.minusDays(7).atTime(13, 0),
                HISTORICAL_SOURCE,
                1L,
                1L,
                observed,
                true
        );
        assertEquals(observed, available);
        var decision = classify(List.of(card(500001, available)), Map.of(), DAY,
                observed, DAY.atTime(22, 55)).get("NEW:500001");
        assertEquals(WorkloadShadowProjectionService.DecisionCode.LATE,
                decision.decisionCode());
    }

    @Test
    void postCutoffSnapshotProvesPreviouslyUnknownBatchIsNew() {
        var batch = card(500001, HISTORICAL_SOURCE);
        var late = classify(List.of(batch), Map.of(), DAY,
                DAY.atTime(23, 12), DAY.atTime(22, 55)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.LATE,
                late.decisionCode());
        assertEquals(WorkloadShadowProjectionService.DecisionOrigin.AFTER_CUTOFF,
                late.decisionOrigin());

        var boundary = classify(List.of(batch), Map.of(), DAY,
                DAY.atTime(22, 5), DAY.atTime(21, 55)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.LATE,
                boundary.decisionCode());

        var unknown = classify(List.of(batch), Map.of(), DAY,
                DAY.atTime(23, 12), DAY.atTime(21, 30)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                unknown.decisionCode());
    }

    @Test
    void preCutoffMandatoryBatchReopenedAfterCutoffMovesToNextDay() {
        var batch = cardWithEpisode(500001, DAY.atTime(23, 5), DAY.atTime(23, 5));
        var previous = new WorkloadShadowProjectionService.BatchDecision(
                batch.batchKey(), WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                WorkloadShadowProjectionService.DecisionOrigin.LIVE,
                "NEW:42001", 1, 10, DAY.atTime(20, 0), DAY.atTime(20, 0), 120, 10
        );
        var updated = classify(List.of(batch), Map.of(batch.batchKey(), previous),
                DAY, DAY.atTime(23, 5), DAY.atTime(22, 55)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.LATE,
                updated.decisionCode());
        assertEquals(WorkloadShadowProjectionService.DecisionOrigin.AFTER_CUTOFF,
                updated.decisionOrigin());
    }

    @Test
    void lateLifecycleTrackingDoesNotForgivePreCutoffMandatoryBatch() {
        var batch = cardWithEpisode(500001, DAY.atTime(23, 5), HISTORICAL_SOURCE);
        var previous = new WorkloadShadowProjectionService.BatchDecision(
                batch.batchKey(), WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                WorkloadShadowProjectionService.DecisionOrigin.CARRY_OVER,
                "NEW:42001", 1, 10, DAY.atTime(20, 0), HISTORICAL_SOURCE, 120, 10
        );
        var updated = classify(List.of(batch), Map.of(batch.batchKey(), previous),
                DAY, DAY.atTime(23, 5), DAY.atTime(22, 55)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                updated.decisionCode());
    }

    @Test
    void existingBadMandatoryDecisionIsCorrectedWhenTrackingTimeAppears() {
        var batch = card(500001, DAY.atTime(23, 9));
        var oldDecision = new WorkloadShadowProjectionService.BatchDecision(
                batch.batchKey(), WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                WorkloadShadowProjectionService.DecisionOrigin.CARRY_OVER,
                "NEW:42001", 1, 10, DAY.atTime(23, 12), HISTORICAL_SOURCE, 0, 10
        );
        var corrected = classify(List.of(batch), Map.of(batch.batchKey(), oldDecision),
                DAY, DAY.atTime(23, 15), DAY.atTime(23, 12)).get(batch.batchKey());
        assertEquals(WorkloadShadowProjectionService.DecisionCode.LATE,
                corrected.decisionCode());
        assertEquals(WorkloadShadowProjectionService.DecisionOrigin.AFTER_CUTOFF,
                corrected.decisionOrigin());
    }

    @Test
    void orderQueryExposesTrackingEvidenceForEveryBatch() throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "findOrderBatches", Collection.class, LocalDateTime.class, String.class
        );
        String sql = method.getAnnotation(Query.class).value().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("tracked_order.work_item_key = concat('order:', orders.order_id)"));
        assertTrue(sql.contains("tracked_order.created_at as lifecycle_created_at"));
        assertTrue(sql.contains("tracked_order.available_at as lifecycle_available_at"));
        assertTrue(sql.contains("target_order.lifecycle_worker_id"));
        assertTrue(sql.contains("target_order.lifecycle_excluded"));
        assertTrue(sql.contains("target_order.lifecycle_exclusion_reason"));
        assertTrue(sql.contains("tracked_order.created_at <= :observedat"));
    }

    private static WorkloadShadowProjectionService.WorkBatch card(
            long reviewId, LocalDateTime availableAt
    ) {
        return new WorkloadShadowProjectionService.WorkBatch(
                1L, 300L, 42001L, "NEW", 1, 10, availableAt, "NEW:" + reviewId
        );
    }

    private static WorkloadShadowProjectionService.WorkBatch cardWithEpisode(
            long reviewId, LocalDateTime availableAt, LocalDateTime availabilityEpisodeAt
    ) {
        return new WorkloadShadowProjectionService.WorkBatch(
                1L, 300L, 42001L, "NEW", 1, 10, availableAt,
                "NEW:" + reviewId, false, availabilityEpisodeAt
        );
    }

    private static Map<String, WorkloadShadowProjectionService.BatchDecision> classify(
            List<WorkloadShadowProjectionService.WorkBatch> batches,
            Map<String, WorkloadShadowProjectionService.BatchDecision> previous,
            LocalDate date,
            LocalDateTime observedAt,
            LocalDateTime previousObservationAt
    ) {
        return WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                batches, previous, date, observedAt, date.atTime(10, 0),
                date.atTime(22, 0), false, previousObservationAt
        );
    }
}
