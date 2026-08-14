package com.hunt.otziv.p_products.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class OrderRepositoryCompletionRepairContractTest {

    @Test
    void repairSelectionRequiresActuallyCompletedWorkAndStillIncludesCorruptedMarkers() throws Exception {
        Method method = OrderRepository.class.getMethod(
                "findCompletionRewardRepairOrderIds",
                Collection.class,
                Collection.class,
                long.class,
                LocalDateTime.class,
                Pageable.class
        );

        String query = method.getAnnotation(Query.class).value();
        assertThat(query)
                .contains("s.title IN :completionStatuses")
                .contains("o.amount > 0")
                .contains("SELECT COUNT(review.id)")
                .contains("review.publish = true")
                .contains(") = o.amount")
                .contains("COUNT(DISTINCT marker.logicalSource)")
                .contains("marker.logicalSource IN :requiredMarkers")
                .contains("< :requiredMarkerCount")
                .contains("ReviewRecoveryTask recovery")
                .contains("recovery.status = com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus.PLANNED")
                .contains("recovery.batch.status = com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus.OPEN")
                .contains("AND NOT EXISTS")
                .contains("repair.nextAttemptAt > :dueAt");
    }

    @Test
    void deferredRecoveryCountUsesTheSameBaseGapAndActiveHoldPredicates() throws Exception {
        Method method = OrderRepository.class.getMethod(
                "countCompletionRewardDeferredByActiveRecovery",
                Collection.class,
                Collection.class,
                long.class
        );

        String query = method.getAnnotation(Query.class).value();
        assertThat(query)
                .contains("s.title IN :completionStatuses")
                .contains("review.publish = true")
                .contains(") = o.amount")
                .contains("marker.logicalSource IN :requiredMarkers")
                .contains("< :requiredMarkerCount")
                .contains("ReviewRecoveryTask recovery")
                .contains("ReviewRecoveryTaskStatus.PLANNED")
                .contains("ReviewRecoveryBatchStatus.OPEN")
                .contains("AND EXISTS");
    }
}
