package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.BadTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.RecoveryTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewStage;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WorkloadTotals;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadTransferPlanTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 22);
    private static final WorkloadTotals TOTALS = new WorkloadTotals(
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0
    );

    @Test
    void actualTransferPlanIgnoresDetachedTasksWithoutOwnedOrder() {
        WorkloadTransferCompanyGraph graph = new WorkloadTransferCompanyGraph(
                100L,
                "Компания",
                true,
                "Активна",
                7L,
                true,
                List.of(9L),
                false,
                0,
                0,
                List.of(order(
                        10L,
                        List.of(review(101L, 10L)),
                        List.of(recovery(201L, 10L)),
                        List.of(bad(301L, 10L))
                )),
                List.of(review(102L, 999L)),
                List.of(recovery(202L, null)),
                List.of(bad(302L, 999L)),
                TOTALS,
                List.of()
        );

        WorkloadTransferPlan plan = WorkloadTransferPlan.from(graph);

        assertEquals(List.of(10L), plan.orderIds());
        assertEquals(List.of(101L), plan.reviewIds());
        assertEquals(List.of(201L), plan.recoveryTaskIds());
        assertEquals(List.of(301L), plan.badTaskIds());
    }

    private static OrderNode order(
            long orderId,
            List<ReviewNode> reviews,
            List<RecoveryTaskNode> recoveryTasks,
            List<BadTaskNode> badTasks
    ) {
        return new OrderNode(
                orderId,
                "Новый",
                9L,
                7L,
                false,
                false,
                DATE,
                DATE,
                1,
                1,
                1,
                reviews.size(),
                1,
                0,
                reviews,
                recoveryTasks,
                badTasks,
                TOTALS,
                List.of()
        );
    }

    private static ReviewNode review(long reviewId, long orderId) {
        return new ReviewNode(
                reviewId,
                orderId,
                9L,
                99L,
                true,
                9L,
                DATE,
                ReviewStage.NAGUL,
                true,
                true,
                false,
                true,
                false,
                false,
                1,
                null,
                0,
                0,
                0,
                List.of()
        );
    }

    private static RecoveryTaskNode recovery(long taskId, Long orderId) {
        return new RecoveryTaskNode(
                taskId,
                orderId,
                null,
                9L,
                7L,
                7L,
                99L,
                true,
                DATE,
                true,
                false,
                List.of()
        );
    }

    private static BadTaskNode bad(long taskId, long orderId) {
        return new BadTaskNode(
                taskId,
                orderId,
                101L,
                9L,
                99L,
                true,
                DATE,
                true,
                List.of()
        );
    }
}
