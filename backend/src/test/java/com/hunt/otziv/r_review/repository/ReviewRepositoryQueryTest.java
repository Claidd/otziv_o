package com.hunt.otziv.r_review.repository;

import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRepositoryQueryTest {

    @Test
    void walkReadinessQueryFetchesEveryAssociationUsedDuringReconciliation() throws Exception {
        Method method = ReviewRepository.class.getMethod("findAllForWalkReadinessReconciliation");
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String jpql = query.value()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertTrue(jpql.contains("join fetch r.bot"));
        assertTrue(jpql.contains("left join fetch r.orderdetails od"));
        assertTrue(jpql.contains("left join fetch od.order"));
    }
}
