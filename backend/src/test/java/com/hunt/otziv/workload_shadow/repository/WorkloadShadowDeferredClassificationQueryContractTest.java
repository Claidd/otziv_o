package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowDeferredClassificationQueryContractTest {

    @Test
    void managerDeferredUnitsCountConcreteCardsMovedToFutureToday() throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "findDeferredAndBlockedUnits",
                Collection.class,
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
                "count(distinct event.review_id) as manager_deferred_units",
                "join reviews review on review.review_id = event.review_id",
                "event.action = 'review_publish_date_changed'",
                "event.source in ('manager_board', 'admin_api')",
                "event.created_at >= :today",
                "event.created_at < date_add(:today, interval 1 day)",
                "str_to_date(event.new_value, '%y-%m-%d') > :today",
                "sum(classified.manager_deferred_units) as manager_deferred_units"
        );
        assertThat(sql).doesNotContain(
                "event.source = 'worker_board'",
                "event.source = 'cron_or_maintenance'"
        );
    }
}
