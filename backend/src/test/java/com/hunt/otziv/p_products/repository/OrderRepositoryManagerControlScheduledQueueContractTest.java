package com.hunt.otziv.p_products.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class OrderRepositoryManagerControlScheduledQueueContractTest {

    @Test
    void managerSummaryTreatsRateLimitedFutureAttemptAsHealthyQueue() {
        Query query = queryFor("summarizeManagerControlOverdueOrdersByManager");

        assertHealthyRateLimitForEveryScenario(query.value(), 4);
    }

    @Test
    void managerExamplesAndCountTreatRateLimitedFutureAttemptAsHealthyQueue() {
        Query query = queryFor("findPageIdForManagerControlOverdueByManager");

        assertHealthyRateLimitForEveryScenario(query.value(), 4);
        assertHealthyRateLimitForEveryScenario(query.countQuery(), 4);
    }

    private Query queryFor(String methodName) {
        return java.util.Arrays.stream(OrderRepository.class.getMethods())
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Query.class);
    }

    private void assertHealthyRateLimitForEveryScenario(String query, int expectedOccurrences) {
        String marker = "LOWER(state.lastErrorCode) LIKE '%rate_limited%'";
        assertThat(query).contains(marker);
        assertThat(query.split(java.util.regex.Pattern.quote(marker), -1)).hasSize(expectedOccurrences + 1);
        String futureAttempt = "state.nextAttemptAt > CURRENT_TIMESTAMP";
        assertThat(query.split(java.util.regex.Pattern.quote(futureAttempt), -1))
                .hasSize(expectedOccurrences + 1);
    }
}