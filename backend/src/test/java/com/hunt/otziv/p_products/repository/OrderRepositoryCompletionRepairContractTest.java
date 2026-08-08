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
    void repairSelectionIncludesCorruptedCompletionRowsForFailClosedValidation() throws Exception {
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
                .contains("COUNT(DISTINCT marker.logicalSource)")
                .contains("marker.logicalSource IN :requiredMarkers")
                .contains("< :requiredMarkerCount")
                .contains("repair.nextAttemptAt > :dueAt")
                .doesNotContain("COUNT(review.id)", "review.publish", ">= o.amount");
    }
}
