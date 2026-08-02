package com.hunt.otziv.workload_shadow.transfer.service;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewStage;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Chooses the concrete review card that must receive an emergency fallback worker.
 */
public final class WorkloadTransferEmergencyCardSelector {

    private static final String STATUS_NEW = "Новый";

    private WorkloadTransferEmergencyCardSelector() {
    }

    public static Long select(WorkloadTransferCompanyGraph graph) {
        Stream<Candidate> owned = graph.orders().stream()
                .filter(order -> !order.waitingForClient())
                .flatMap(order -> order.reviews().stream()
                        .map(review -> candidate(review, isNew(order))));
        Stream<Candidate> detached = graph.detachedReviews().stream()
                .map(review -> candidate(review, false));
        return Stream.concat(owned, detached)
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(Candidate::priority)
                        .thenComparing(
                                value -> value.review().publicationDate(),
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparingLong(value -> value.review().reviewId()))
                .map(value -> value.review().reviewId())
                .findFirst()
                .orElse(null);
    }

    private static Candidate candidate(ReviewNode review, boolean pendingNewAllowed) {
        if (review.suppressedByOpenRecovery() || review.orderWaitingForClient()) {
            return null;
        }
        if (review.textReady()
                && review.stage() == ReviewStage.PUBLISH
                && review.dueOnDate()) {
            return new Candidate(review, 0);
        }
        if (review.textReady()
                && review.stage() == ReviewStage.NAGUL
                && !review.outsideNagulLookahead()) {
            return new Candidate(review, 1);
        }
        if (pendingNewAllowed && !review.textReady()) {
            return new Candidate(review, 2);
        }
        return null;
    }

    private static boolean isNew(OrderNode order) {
        return order.status() != null && STATUS_NEW.equals(order.status().trim());
    }

    private record Candidate(ReviewNode review, int priority) {
    }
}
