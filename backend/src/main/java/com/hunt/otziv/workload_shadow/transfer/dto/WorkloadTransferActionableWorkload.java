package com.hunt.otziv.workload_shadow.transfer.dto;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.BadTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.RecoveryTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewStage;

/**
 * The currently actionable part of a complete company transfer graph.
 *
 * <p>The complete graph is still the transfer boundary. This projection is used only to
 * decide whether the company has a current problem and how much of that problem should be
 * counted by the shadow recommendation.</p>
 */
public record WorkloadTransferActionableWorkload(
        long newUnits,
        long correctionUnits,
        long nagulUnits,
        long publishUnits,
        long recoveryUnits,
        long badUnits,
        long estimatedMinutes
) {

    public static WorkloadTransferActionableWorkload calculate(
            WorkloadTransferCompanyGraph graph,
            EstimateRates rates
    ) {
        Counter counter = new Counter(rates);
        for (OrderNode order : graph.orders()) {
            if (order.waitingForClient()) {
                continue;
            }
            counter.newUnits = safeAdd(counter.newUnits, order.newUnits());
            counter.correctionUnits = safeAdd(counter.correctionUnits, order.correctionUnits());
            order.reviews().forEach(counter::addReview);
            order.recoveryTasks().forEach(counter::addRecovery);
            order.badTasks().forEach(counter::addBad);
        }
        graph.detachedReviews().forEach(counter::addReview);
        graph.detachedRecoveryTasks().forEach(counter::addRecovery);
        graph.detachedBadTasks().forEach(counter::addBad);
        return counter.toValue();
    }

    public long problemUnits() {
        long total = 0;
        total = safeAdd(total, newUnits);
        total = safeAdd(total, correctionUnits);
        total = safeAdd(total, nagulUnits);
        total = safeAdd(total, publishUnits);
        total = safeAdd(total, recoveryUnits);
        return safeAdd(total, badUnits);
    }

    public record EstimateRates(
            int newMinutesPerCard,
            int correctionMinutesPerOrder,
            int walkMinutesPerCard,
            int publishMinutesPerCard,
            int recoveryMinutesPerTask,
            int badMinutesPerTask
    ) {
    }

    private static final class Counter {

        private final EstimateRates rates;
        private long newUnits;
        private long correctionUnits;
        private long nagulUnits;
        private long publishUnits;
        private long recoveryUnits;
        private long badUnits;

        private Counter(EstimateRates rates) {
            this.rates = rates;
        }

        private void addReview(ReviewNode review) {
            if (!review.textReady()
                    || review.suppressedByOpenRecovery()
                    || review.orderWaitingForClient()) {
                return;
            }
            if (review.stage() == ReviewStage.NAGUL) {
                if (!review.outsideNagulLookahead()) {
                    nagulUnits = safeAdd(nagulUnits, 1);
                }
                return;
            }
            if (review.dueOnDate()) {
                publishUnits = safeAdd(publishUnits, 1);
            }
        }

        private void addRecovery(RecoveryTaskNode recovery) {
            if (recovery.dueOnDate()) {
                recoveryUnits = safeAdd(recoveryUnits, 1);
            }
        }

        private void addBad(BadTaskNode bad) {
            if (bad.dueOnDate()) {
                badUnits = safeAdd(badUnits, 1);
            }
        }

        private WorkloadTransferActionableWorkload toValue() {
            long minutes = 0;
            minutes = safeAdd(minutes, safeMultiply(newUnits, rates.newMinutesPerCard()));
            minutes = safeAdd(
                    minutes,
                    safeMultiply(correctionUnits, rates.correctionMinutesPerOrder())
            );
            minutes = safeAdd(minutes, safeMultiply(nagulUnits, rates.walkMinutesPerCard()));
            minutes = safeAdd(minutes, safeMultiply(publishUnits, rates.publishMinutesPerCard()));
            minutes = safeAdd(minutes, safeMultiply(recoveryUnits, rates.recoveryMinutesPerTask()));
            minutes = safeAdd(minutes, safeMultiply(badUnits, rates.badMinutesPerTask()));
            return new WorkloadTransferActionableWorkload(
                    newUnits,
                    correctionUnits,
                    nagulUnits,
                    publishUnits,
                    recoveryUnits,
                    badUnits,
                    minutes
            );
        }
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long safeMultiply(long value, int multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}
