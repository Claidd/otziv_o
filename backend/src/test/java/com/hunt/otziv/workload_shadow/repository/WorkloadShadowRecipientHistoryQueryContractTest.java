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
                "daily.finalized = true",
                "daily.progress_date < :to",
                "achievement.result_date < :currentdate"
        );
        assertThat(sql).doesNotContain("workload_shadow_worker_current");
    }
}
