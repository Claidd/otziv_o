package com.hunt.otziv.workload_shadow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Pure selection policy used by the observation-only transfer simulation.
 */
final class WorkloadTransferSelectionPolicy {

    private static final int MAX_STATES_PER_CARDINALITY = 20_000;

    private WorkloadTransferSelectionPolicy() {
    }

    static Tier tier(
            int failureDays,
            int allowedFailureDays,
            Tier firstExcessFailure,
            Tier secondExcessFailure,
            Tier thirdAndLaterExcessFailure
    ) {
        int excessFailures = Math.max(1, failureDays - allowedFailureDays);
        if (excessFailures == 1) {
            return firstExcessFailure;
        }
        if (excessFailures == 2) {
            return secondExcessFailure;
        }
        return thirdAndLaterExcessFailure;
    }

    static <T> List<T> selectClosest(
            List<T> candidates,
            int percent,
            int maxCompanies,
            ToLongFunction<T> units,
            ToLongFunction<T> stableKey
    ) {
        List<T> positive = candidates.stream()
                .filter(value -> units.applyAsLong(value) > 0)
                .toList();
        if (positive.isEmpty() || maxCompanies <= 0) {
            return List.of();
        }

        long totalUnits = positive.stream()
                .mapToLong(units)
                .reduce(0, WorkloadTransferSelectionPolicy::safeAdd);
        long targetUnits = Math.max(1, BigDecimal.valueOf(totalUnits)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                .longValue());
        int cardinalityLimit = Math.min(maxCompanies, positive.size());

        List<Map<Long, Selection>> states = new ArrayList<>(cardinalityLimit + 1);
        for (int count = 0; count <= cardinalityLimit; count++) {
            states.add(new HashMap<>());
        }
        states.getFirst().put(0L, new Selection(0, List.of()));

        for (int index = 0; index < positive.size(); index++) {
            long candidateUnits = units.applyAsLong(positive.get(index));
            int upperCount = Math.min(cardinalityLimit, index + 1);
            for (int count = upperCount; count >= 1; count--) {
                List<Selection> previous = List.copyOf(states.get(count - 1).values());
                Map<Long, Selection> current = states.get(count);
                for (Selection selection : previous) {
                    long combinedUnits = safeAdd(selection.units(), candidateUnits);
                    List<Integer> combinedIndexes = new ArrayList<>(selection.indexes());
                    combinedIndexes.add(index);
                    Selection combined = new Selection(combinedUnits, List.copyOf(combinedIndexes));
                    current.merge(
                            combinedUnits,
                            combined,
                            (left, right) -> lexicographicallyBefore(left, right, positive, stableKey)
                                    ? left
                                    : right
                    );
                }
                prune(current, targetUnits, positive, stableKey);
            }
        }

        Selection best = null;
        for (int count = 1; count <= cardinalityLimit; count++) {
            for (Selection candidate : states.get(count).values()) {
                if (best == null || compare(candidate, best, targetUnits, positive, stableKey) < 0) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return List.of();
        }
        return best.indexes().stream().map(positive::get).toList();
    }

    private static <T> void prune(
            Map<Long, Selection> states,
            long targetUnits,
            List<T> candidates,
            ToLongFunction<T> stableKey
    ) {
        if (states.size() <= MAX_STATES_PER_CARDINALITY) {
            return;
        }
        List<Selection> retained = states.values().stream()
                .sorted((left, right) -> compare(left, right, targetUnits, candidates, stableKey))
                .limit(MAX_STATES_PER_CARDINALITY)
                .toList();
        states.clear();
        retained.forEach(value -> states.put(value.units(), value));
    }

    private static <T> int compare(
            Selection left,
            Selection right,
            long targetUnits,
            List<T> candidates,
            ToLongFunction<T> stableKey
    ) {
        int comparison = Long.compare(distance(left.units(), targetUnits), distance(right.units(), targetUnits));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Boolean.compare(left.units() > targetUnits, right.units() > targetUnits);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.indexes().size(), right.indexes().size());
        if (comparison != 0) {
            return comparison;
        }
        return compareLexicographically(left, right, candidates, stableKey);
    }

    private static <T> boolean lexicographicallyBefore(
            Selection left,
            Selection right,
            List<T> candidates,
            ToLongFunction<T> stableKey
    ) {
        return compareLexicographically(left, right, candidates, stableKey) <= 0;
    }

    private static <T> int compareLexicographically(
            Selection left,
            Selection right,
            List<T> candidates,
            ToLongFunction<T> stableKey
    ) {
        int length = Math.min(left.indexes().size(), right.indexes().size());
        for (int index = 0; index < length; index++) {
            long leftKey = stableKey.applyAsLong(candidates.get(left.indexes().get(index)));
            long rightKey = stableKey.applyAsLong(candidates.get(right.indexes().get(index)));
            int comparison = Long.compare(leftKey, rightKey);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.indexes().size(), right.indexes().size());
    }

    private static long distance(long value, long target) {
        return value >= target ? value - target : target - value;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record Tier(int percent, int maxCompanies) {
    }

    private record Selection(long units, List<Integer> indexes) {
    }
}
