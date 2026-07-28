package com.hunt.otziv.workload_shadow.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.ReviewStage;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferCompanyGraph.WorkloadTotals;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadTransferEmergencyCardSelectorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 27);
    private static final WorkloadTotals EMPTY_TOTALS = new WorkloadTotals(
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0
    );

    @Test
    void prioritizesDuePublishThenNagulThenPendingCardInsideNewOrder() {
        ReviewNode pendingNew = review(
                103L,
                10L,
                ReviewStage.NAGUL,
                false,
                false,
                false
        );
        ReviewNode nagul = review(
                102L,
                10L,
                ReviewStage.NAGUL,
                false,
                true,
                false
        );
        ReviewNode duePublish = review(
                101L,
                10L,
                ReviewStage.PUBLISH,
                true,
                true,
                false
        );

        assertEquals(
                101L,
                WorkloadTransferEmergencyCardSelector.select(
                        graph(order(10L, "Новый", false, pendingNew, nagul, duePublish))
                )
        );
        assertEquals(
                102L,
                WorkloadTransferEmergencyCardSelector.select(
                        graph(order(10L, "Новый", false, pendingNew, nagul))
                )
        );
        assertEquals(
                103L,
                WorkloadTransferEmergencyCardSelector.select(
                        graph(order(10L, "Новый", false, pendingNew))
                )
        );
    }

    @Test
    void excludesWaitingSuppressedAndUnreadyCardsOutsideNewOrder() {
        ReviewNode waitingPending = review(
                201L,
                20L,
                ReviewStage.NAGUL,
                false,
                false,
                false
        );
        ReviewNode suppressedPending = review(
                202L,
                21L,
                ReviewStage.NAGUL,
                false,
                false,
                true
        );
        ReviewNode correctionPending = review(
                203L,
                22L,
                ReviewStage.NAGUL,
                false,
                false,
                false
        );

        WorkloadTransferCompanyGraph graph = graph(
                order(20L, "Новый", true, waitingPending),
                order(21L, "Новый", false, suppressedPending),
                order(22L, "Коррекция", false, correctionPending)
        );

        assertNull(WorkloadTransferEmergencyCardSelector.select(graph));

        ReviewNode detachedWaiting = review(
                204L,
                99L,
                ReviewStage.PUBLISH,
                true,
                true,
                false,
                true
        );
        assertNull(WorkloadTransferEmergencyCardSelector.select(
                graphWithDetached(detachedWaiting)
        ));
    }

    private static WorkloadTransferCompanyGraph graph(OrderNode... orders) {
        return graph(List.of(), List.of(orders));
    }

    private static WorkloadTransferCompanyGraph graphWithDetached(ReviewNode... reviews) {
        return graph(List.of(reviews), List.of());
    }

    private static WorkloadTransferCompanyGraph graph(
            List<ReviewNode> detachedReviews,
            List<OrderNode> orders
    ) {
        return new WorkloadTransferCompanyGraph(
                1L,
                "Компания",
                true,
                "Активна",
                7L,
                true,
                List.of(9L),
                false,
                0,
                0,
                orders,
                detachedReviews,
                List.of(),
                List.of(),
                EMPTY_TOTALS,
                List.of()
        );
    }

    private static OrderNode order(
            long orderId,
            String status,
            boolean waitingForClient,
            ReviewNode... reviews
    ) {
        return new OrderNode(
                orderId,
                status,
                9L,
                7L,
                waitingForClient,
                false,
                DATE,
                DATE,
                0,
                0,
                0,
                reviews.length,
                0,
                0,
                List.of(reviews),
                List.of(),
                List.of(),
                EMPTY_TOTALS,
                List.of()
        );
    }

    private static ReviewNode review(
            long reviewId,
            long orderId,
            ReviewStage stage,
            boolean dueOnDate,
            boolean textReady,
            boolean suppressed
    ) {
        return review(
                reviewId,
                orderId,
                stage,
                dueOnDate,
                textReady,
                suppressed,
                false
        );
    }

    private static ReviewNode review(
            long reviewId,
            long orderId,
            ReviewStage stage,
            boolean dueOnDate,
            boolean textReady,
            boolean suppressed,
            boolean orderWaitingForClient
    ) {
        return new ReviewNode(
                reviewId,
                orderId,
                9L,
                99L,
                true,
                9L,
                DATE,
                stage,
                dueOnDate,
                stage == ReviewStage.NAGUL,
                false,
                textReady,
                suppressed,
                orderWaitingForClient,
                1,
                null,
                0,
                0,
                0,
                List.of()
        );
    }
}
