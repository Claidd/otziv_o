package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public record WorkloadTransferPlan(
        List<Long> orderIds,
        List<Long> reviewIds,
        List<Long> badTaskIds,
        List<Long> recoveryTaskIds
) {

    public WorkloadTransferPlan {
        orderIds = normalized(orderIds);
        reviewIds = normalized(reviewIds);
        badTaskIds = normalized(badTaskIds);
        recoveryTaskIds = normalized(recoveryTaskIds);
    }

    public static WorkloadTransferPlan from(WorkloadTransferCompanyGraph graph) {
        TreeSet<Long> orderIds = new TreeSet<>();
        TreeSet<Long> reviewIds = new TreeSet<>();
        TreeSet<Long> badTaskIds = new TreeSet<>();
        TreeSet<Long> recoveryTaskIds = new TreeSet<>();

        for (OrderNode order : graph.orders()) {
            orderIds.add(order.orderId());
            order.reviews().forEach(value -> reviewIds.add(value.reviewId()));
            order.badTasks().forEach(value -> badTaskIds.add(value.taskId()));
            order.recoveryTasks().forEach(value -> recoveryTaskIds.add(value.taskId()));
        }
        graph.detachedReviews().forEach(value -> reviewIds.add(value.reviewId()));
        graph.detachedBadTasks().forEach(value -> badTaskIds.add(value.taskId()));
        graph.detachedRecoveryTasks().forEach(value -> recoveryTaskIds.add(value.taskId()));
        return new WorkloadTransferPlan(
                List.copyOf(orderIds),
                List.copyOf(reviewIds),
                List.copyOf(badTaskIds),
                List.copyOf(recoveryTaskIds)
        );
    }

    public boolean empty() {
        return orderIds.isEmpty()
                && reviewIds.isEmpty()
                && badTaskIds.isEmpty()
                && recoveryTaskIds.isEmpty();
    }

    private static List<Long> normalized(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList();
    }
}
