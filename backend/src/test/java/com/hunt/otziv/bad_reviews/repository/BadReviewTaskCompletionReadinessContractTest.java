package com.hunt.otziv.bad_reviews.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class BadReviewTaskCompletionReadinessContractTest {

    @Test
    void doneTaskGapUsesItsImmutableMarkerAndDurableOrderBackoff() throws Exception {
        Method method = BadReviewTaskRepository.class.getMethod(
                "findCompletionRewardRepairGapTaskIds",
                String.class,
                String.class,
                LocalDateTime.class,
                Pageable.class
        );

        Query query = method.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("SELECT task.bad_review_task_id")
                .contains("task.bad_review_task_status = :completedStatus")
                .contains("marker.order_id = task.bad_review_task_order")
                .contains("marker.logical_source = CONCAT(:markerPrefix, task.bad_review_task_id)")
                .contains("repair.next_attempt_at > :dueAt")
                .doesNotContain("bad_review_task_completed_date >=");
    }

    @Test
    void canceledTaskGapNeedsPriorCompletionEvidenceAndMissingCancelMarker() throws Exception {
        Method method = BadReviewTaskRepository.class.getMethod(
                "findCompletionRewardCancellationRepairGapTaskIds",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDateTime.class,
                Pageable.class
        );

        String query = method.getAnnotation(Query.class).value();
        assertThat(query)
                .contains("task.bad_review_task_status = :canceledStatus")
                .contains("CONCAT(:cancelMarkerPrefix, task.bad_review_task_id)")
                .contains("CONCAT(:doneMarkerPrefix, task.bad_review_task_id)")
                .contains("reward.zp_active = 1")
                .contains("CONCAT(:doneManagerPrefix, task.bad_review_task_id)")
                .contains("CONCAT(:doneSpecialistPrefix, task.bad_review_task_id)")
                .contains("repair.next_attempt_at > :dueAt");
    }
}
