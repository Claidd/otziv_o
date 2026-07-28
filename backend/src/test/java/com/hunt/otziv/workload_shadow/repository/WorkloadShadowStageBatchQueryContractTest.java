package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowStageBatchQueryContractTest {

    @Test
    void newRemainsOneBatchPerCardAndCorrectionOneBatchPerOrder() throws Exception {
        String sql = sql("findOrderBatches", Collection.class, String.class);

        assertThat(sql).contains(
                "'correction:'",
                "date_format(target_order.available_at, '%y%m%d%h%i%s%f')",
                "concat('new:', review.review_id) as batch_key",
                "1 as units",
                "where target_order.status_title = 'коррекция'",
                "where target_order.status_title = 'новый'",
                "cast(:shiftstart as time)"
        );
        assertThat(sql).doesNotContain("interval 10 hour");
    }

    @Test
    void everyCompletedCorrectionCycleCountsAsOneTask() throws Exception {
        String sql = sql(
                "findCorrectionCompletions",
                Collection.class,
                LocalDateTime.class,
                LocalDateTime.class
        );

        assertThat(sql).contains(
                "count(*) as units",
                "event.old_value = 'коррекция'",
                "coalesce(event.new_value, '') <> 'коррекция'"
        );
        assertThat(sql).doesNotContain(
                "count(distinct",
                "group by event.order_id"
        );
    }

    @Test
    void nagulIsOneStableBatchPerReviewAndUsesEveryReadinessBoundary() throws Exception {
        String sql = sql(
                "findNagulBatches",
                Collection.class,
                LocalDate.class,
                String.class
        );

        assertThat(sql).contains(
                "1 as units",
                "concat('nagul:', relevant.review_id) as batch_key",
                "review.review_text_ready_at",
                "review.review_vigul_changed_at",
                "'publication_allowed'",
                "'review_account_walk_schedule_checked'",
                "'review_publish_date_changed'",
                "bin_to_uuid(relevant.order_detail_id)",
                "cast(:shiftstart as time)"
        );
        assertThat(sql).doesNotContain(
                "count(distinct review.review_id) as units",
                "group by review.review_worker, orders.order_company, orders.order_id",
                "interval 10 hour"
        );
    }

    @Test
    void publishIsOneStableBatchPerReviewAndStartsAtTheCurrentVigulTransition() throws Exception {
        String sql = sql(
                "findPublishBatches",
                Collection.class,
                LocalDate.class,
                String.class
        );

        assertThat(sql).contains(
                "1 as units",
                "concat('publish:', relevant.review_id) as batch_key",
                "review.review_vigul_changed_at",
                "timestamp( review.review_publish_date, cast(:shiftstart as time) )",
                "'publication_allowed'",
                "'review_publish_date_changed'"
        );
        assertThat(sql).doesNotContain(
                "count(distinct review.review_id) as units",
                "max(nagul_event.created_at)",
                "interval 10 hour"
        );
    }

    @Test
    void badIsOneStableBatchPerConcreteTask() throws Exception {
        String sql = sql(
                "findBadBatches",
                Collection.class,
                LocalDate.class,
                String.class
        );

        assertThat(sql).contains(
                "task.bad_review_task_id",
                "1 as units",
                "concat('bad:', task.bad_review_task_id) as batch_key",
                "task.bad_review_task_created_at",
                "cast(:shiftstart as time)"
        );
        assertThat(sql).doesNotContain(
                "count(*) as units",
                "group by task.bad_review_task_worker",
                "interval 10 hour"
        );
    }

    @Test
    void recoveryIsOneStableBatchPerConcreteTask() throws Exception {
        String sql = sql(
                "findRecoveryBatches",
                Collection.class,
                LocalDate.class,
                String.class
        );

        assertThat(sql).contains(
                "task.review_recovery_task_id",
                "1 as units",
                "concat('recovery:', task.review_recovery_task_id) as batch_key",
                "task.review_recovery_task_created_at",
                "cast(:shiftstart as time)"
        );
        assertThat(sql).doesNotContain(
                "count(*) as units",
                "group by task.review_recovery_task_worker",
                "interval 10 hour"
        );
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                methodName,
                parameterTypes
        );
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        return query.value().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
