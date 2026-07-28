package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadShadowProjectionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 27);
    private static final LocalDateTime SHIFT_START = DATE.atTime(10, 0);
    private static final LocalDateTime SHIFT_END = DATE.atTime(23, 0);

    @Test
    void firstDecisionUsesActualObservationTimeAndTreatsSimultaneousCohortAtomically() {
        var first = batch("NAGUL:1", 100L, 1, 4, DATE.atTime(22, 50));
        var second = batch(
                "NAGUL:2",
                100L,
                1,
                4,
                DATE.atTime(22, 50).plusSeconds(40)
        );

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(first, second),
                Map.of(),
                DATE,
                DATE.atTime(22, 55),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.LATE,
                decisions.get("NAGUL:1").decisionCode()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.LATE,
                decisions.get("NAGUL:2").decisionCode()
        );
        assertEquals(8, decisions.get("NAGUL:1").cohortEstimatedMinutesAtDecision());
        assertEquals(5, decisions.get("NAGUL:1").availableMinutesAtDecision());
    }

    @Test
    void persistedMandatoryRemainderConsumesCapacityBeforeNewWork() {
        var mandatory = batch("NAGUL:1", 100L, 1, 10, DATE.atTime(20, 0));
        var incoming = batch("NAGUL:2", 101L, 1, 6, DATE.atTime(22, 40));
        var persisted = decision(
                mandatory,
                WorkloadShadowProjectionService.DecisionCode.MANDATORY
        );

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(mandatory, incoming),
                Map.of(mandatory.batchKey(), persisted),
                DATE,
                DATE.atTime(22, 45),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                decisions.get(mandatory.batchKey()).decisionCode()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.LATE,
                decisions.get(incoming.batchKey()).decisionCode()
        );
    }

    @Test
    void mandatoryDecisionCannotBeForgivenLaterTheSameDay() {
        var remaining = batch("NAGUL:1", 100L, 1, 4, DATE.atTime(20, 0));
        var mandatory = decision(
                remaining,
                WorkloadShadowProjectionService.DecisionCode.MANDATORY
        );

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(remaining),
                Map.of(remaining.batchKey(), mandatory),
                DATE,
                DATE.atTime(22, 59),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(mandatory, decisions.get(remaining.batchKey()));
    }

    @Test
    void lateDecisionRemainsLateAfterPartialCompletionMakesRemainderSmall() {
        var remaining = batch("NAGUL:3", 100L, 1, 4, DATE.atTime(22, 50));
        var late = decision(remaining, WorkloadShadowProjectionService.DecisionCode.LATE);

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(remaining),
                Map.of(remaining.batchKey(), late),
                DATE,
                DATE.atTime(22, 55),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(late, decisions.get(remaining.batchKey()));
    }

    @Test
    void completedCardsDisappearButEveryRemainingCardKeepsTheLateDecision() {
        var first = batch("NAGUL:1", 100L, 1, 4, DATE.atTime(22, 50));
        var second = batch("NAGUL:2", 100L, 1, 4, DATE.atTime(22, 50));
        var third = batch("NAGUL:3", 100L, 1, 4, DATE.atTime(22, 50));
        var fourth = batch("NAGUL:4", 100L, 1, 4, DATE.atTime(22, 50));
        var initial = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(first, second, third, fourth),
                Map.of(),
                DATE,
                DATE.atTime(22, 50),
                SHIFT_START,
                SHIFT_END,
                false
        );

        var afterTwoCompleted = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(third, fourth),
                initial,
                DATE,
                DATE.atTime(22, 58),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(2, afterTwoCompleted.size());
        assertTrue(afterTwoCompleted.values().stream().allMatch(
                decision -> decision.decisionCode()
                        == WorkloadShadowProjectionService.DecisionCode.LATE
        ));
    }

    @Test
    void previousDayRemainderIsMandatoryEvenWhenItCannotFitToday() {
        var carryOver = batch(
                "NAGUL:1",
                100L,
                50,
                4,
                DATE.minusDays(1).atTime(22, 55)
        );

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(carryOver),
                Map.of(),
                DATE,
                DATE.atTime(22, 55),
                SHIFT_START,
                SHIFT_END,
                false
        );

        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                decisions.get(carryOver.batchKey()).decisionCode()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionOrigin.CARRY_OVER,
                decisions.get(carryOver.batchKey()).decisionOrigin()
        );
    }

    @Test
    void recoveredObservationForgivesOnlyWorkThatWasImpossibleAtArrival() {
        var feasibleAtArrival = batch(
                "NAGUL:1",
                100L,
                10,
                4,
                DATE.atTime(20, 0)
        );
        var impossibleAtArrival = batch(
                "NAGUL:2",
                101L,
                50,
                4,
                DATE.atTime(22, 0)
        );

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(feasibleAtArrival, impossibleAtArrival),
                Map.of(),
                DATE,
                DATE.atTime(22, 55),
                SHIFT_START,
                SHIFT_END,
                true
        );

        assertEquals(
                WorkloadShadowProjectionService.DecisionOrigin.RECOVERED_MANDATORY,
                decisions.get(feasibleAtArrival.batchKey()).decisionOrigin()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionOrigin.RECOVERED_LATE,
                decisions.get(impossibleAtArrival.batchKey()).decisionOrigin()
        );
    }

    @Test
    void recoveredCohortsShareOneReconstructedTimeline() {
        var first = batch("NAGUL:1", 100L, 1, 40, DATE.atTime(21, 30));
        var second = batch("NAGUL:2", 101L, 1, 40, DATE.atTime(21, 30));
        var third = batch("NAGUL:3", 102L, 1, 40, DATE.atTime(21, 30));

        var decisions = WorkloadShadowProjectionService.classifyDailyBatchDecisions(
                List.of(first, second, third),
                Map.of(),
                DATE,
                DATE.atTime(22, 55),
                SHIFT_START,
                SHIFT_END,
                true
        );

        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                decisions.get(first.batchKey()).decisionCode()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.MANDATORY,
                decisions.get(second.batchKey()).decisionCode()
        );
        assertEquals(
                WorkloadShadowProjectionService.DecisionCode.LATE,
                decisions.get(third.batchKey()).decisionCode()
        );
    }

    @Test
    void recipientRequiresBothLastFinalizedDayAndStrongCurrentMonthHistory() {
        assertFalse(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                12,
                11,
                false,
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(91.67),
                1,
                85,
                80,
                2
        ));
        assertFalse(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                12,
                9,
                true,
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(75),
                3,
                85,
                80,
                2
        ));
        assertTrue(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                12,
                11,
                true,
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(91.67),
                1,
                85,
                80,
                2
        ));
    }

    @Test
    void unfinishedCurrentPercentIsNotAnEligibilityInput() {
        assertTrue(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                10,
                9,
                true,
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(90),
                1,
                85,
                80,
                2
        ));
    }

    @Test
    void recipientWithoutWorkerGroupIsExcludedBecausePersonalOffersAreForbidden() {
        assertFalse(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                false,
                10,
                9,
                true,
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(90),
                1,
                85,
                80,
                2
        ));
    }

    @Test
    void recipientNeedsAtLeastOneFinalizedHundredPercentDayInTheHistoryWindow() {
        assertFalse(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                10,
                0,
                true,
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(90),
                1,
                85,
                80,
                2
        ));
    }

    @Test
    void currentMonthEligibilityStatsNeverFallBackToOlderRollingHistory() {
        var history = new WorkloadShadowProjectionService.HistoryStats(
                0,
                0,
                0,
                12,
                1,
                true,
                DATE.minusDays(1)
        );

        var month = WorkloadShadowProjectionService.currentMonthStats(history);

        assertEquals(0, month.evaluatedDays());
        assertEquals(0, month.hundredDays());
        assertEquals(0, month.failureDays());
        assertEquals(0, month.hundredPercentRate().compareTo(BigDecimal.ZERO));
        assertFalse(WorkloadShadowProjectionService.isRecipientEligible(
                true,
                true,
                month.evaluatedDays(),
                month.hundredDays(),
                history.lastDayReached100(),
                BigDecimal.valueOf(95),
                month.hundredPercentRate(),
                month.failureDays(),
                85,
                80,
                2
        ));
    }

    @Test
    void currentDayAffectsEligibilityHistoryOnlyAfterItIsFinalized() {
        var history = WorkloadShadowProjectionService.HistoryStats.empty();

        var unfinished = WorkloadShadowProjectionService.includeCurrentFinalizedDay(
                history,
                DATE,
                SHIFT_END.minusMinutes(1),
                SHIFT_END,
                10,
                true,
                0
        );
        var finalized = WorkloadShadowProjectionService.includeCurrentFinalizedDay(
                history,
                DATE,
                SHIFT_END,
                SHIFT_END,
                10,
                true,
                0
        );
        var repeated = WorkloadShadowProjectionService.includeCurrentFinalizedDay(
                finalized,
                DATE,
                SHIFT_END.plusMinutes(5),
                SHIFT_END,
                10,
                true,
                0
        );

        assertEquals(0, unfinished.hundredDays());
        assertFalse(unfinished.lastDayReached100());
        assertEquals(1, finalized.hundredDays());
        assertTrue(finalized.lastDayReached100());
        assertEquals(DATE, finalized.latestProgressDate());
        assertEquals(1, repeated.hundredDays());
    }

    @Test
    void futureSourceAvailabilityInvalidatesPersistedDecisionForRecalculation() {
        assertFalse(WorkloadShadowProjectionService.isPersistedDecisionUsable(
                DATE.plusDays(20).atTime(12, 19),
                DATE.atTime(14, 30)
        ));
        assertTrue(WorkloadShadowProjectionService.isPersistedDecisionUsable(
                DATE.atTime(10, 0),
                DATE.atTime(14, 30)
        ));
        assertTrue(WorkloadShadowProjectionService.isPersistedDecisionUsable(
                null,
                DATE.atTime(14, 30)
        ));
    }

    private WorkloadShadowProjectionService.WorkBatch batch(
            String batchKey,
            long orderId,
            long units,
            int minutesPerUnit,
            LocalDateTime availableAt
    ) {
        return new WorkloadShadowProjectionService.WorkBatch(
                1L,
                10L,
                orderId,
                "NAGUL",
                units,
                minutesPerUnit,
                availableAt,
                batchKey
        );
    }

    private WorkloadShadowProjectionService.BatchDecision decision(
            WorkloadShadowProjectionService.WorkBatch batch,
            WorkloadShadowProjectionService.DecisionCode code
    ) {
        return new WorkloadShadowProjectionService.BatchDecision(
                batch.batchKey(),
                code,
                WorkloadShadowProjectionService.DecisionOrigin.LIVE,
                "cohort",
                batch.units(),
                batch.estimatedMinutes(),
                DATE.atTime(20, 0),
                batch.availableAt(),
                180,
                batch.estimatedMinutes()
        );
    }
}
