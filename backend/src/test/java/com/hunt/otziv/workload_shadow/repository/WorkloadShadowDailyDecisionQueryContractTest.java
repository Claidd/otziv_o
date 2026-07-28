package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowDailyDecisionQueryContractTest {

    @Test
    void dailyDecisionReadIncludesInactiveRowsSoAReappearingTaskKeepsItsDecision()
            throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "findDailyBatchDecisions",
                Collection.class,
                LocalDate.class
        );
        String sql = normalizedSql(method);

        assertThat(sql).contains(
                "decision_code",
                "decision_origin",
                "first_detected_at",
                "where progress_date = :progressdate",
                "worker_id in (:workerids)"
        );
        assertThat(sql).doesNotContain("active = true", "active = 1");
    }

    @Test
    void decisionUpsertChangesOnlyTheLiveRemainderAndNeverRewritesTheFirstDecision()
            throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "upsertDailyBatchDecisions",
                String.class
        );
        String sql = normalizedSql(method);
        String updateClause = sql.substring(sql.indexOf("on duplicate key update"));

        assertThat(updateClause).contains(
                "remaining_units = values(remaining_units)",
                "remaining_estimated_minutes = values(remaining_estimated_minutes)",
                "last_seen_at = values(last_seen_at)",
                "active = true"
        );
        assertThat(updateClause).doesNotContain(
                "decision_code =",
                "decision_origin =",
                "cohort_key =",
                "initial_units =",
                "initial_estimated_minutes =",
                "first_detected_at =",
                "source_available_at =",
                "available_minutes_at_decision =",
                "cohort_estimated_minutes_at_decision ="
        );
    }

    private String normalizedSql(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        return query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
