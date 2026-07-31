package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowRecipientHistoryQueryContractTest {

    @Test
    void recipientHistoryUsesCurrentMonthFinalizedRowsAndAnExclusiveRequestedBoundary()
            throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "findHistory",
                Collection.class,
                LocalDate.class,
                LocalDate.class,
                LocalDate.class,
                LocalDate.class
        );
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();

        String sql = query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
                "when ranked.progress_date >= :monthfrom then ranked.hundred_day",
                "when ranked.progress_date >= :monthfrom then ranked.failure_day",
                "when ranked.latest_rank = 1 then ranked.hundred_day",
                "max(ranked.progress_date) as latest_progress_date",
                "case when daily.reached_100 = true then 1 else 0 end as hundred_day",
                "when daily.reached_100 = false and daily.freeze_applied = false",
                "daily.finalized = true",
                "daily.progress_date < :to",
                "achievement.result_date < :currentdate"
        );
        assertThat(sql).doesNotContain(
                "case when daily.reached_100_once = true then 1 else 0 end as hundred_day",
                "when daily.reached_100_once = false and daily.freeze_applied = false"
        );
        assertThat(sql).doesNotContain("workload_shadow_worker_current");
    }

    @Test
    void freezeCreditsUseTheFinalDailyResult() throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "findPendingFreezeEvaluationRows",
                LocalDate.class
        );
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();

        String sql = query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains("daily.reached_100 as reached_100");
        assertThat(sql).doesNotContain("daily.reached_100_once as reached_100");
    }
}
