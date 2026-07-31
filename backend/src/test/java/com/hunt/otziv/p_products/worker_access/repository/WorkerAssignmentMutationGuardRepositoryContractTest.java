package com.hunt.otziv.p_products.worker_access.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkerAssignmentMutationGuardRepositoryContractTest {

    @Test
    void badTaskOwnershipAllowsCompletedOrdersButStillRequiresTheSameWorker()
            throws Exception {
        assertBadTaskOwnershipQuery("countOwnedBadTask");
        assertBadTaskOwnershipQuery("lockOwnedBadTask");
    }

    @Test
    void recoveryOwnershipAllowsCompletedOrdersButStillRequiresTheSameWorker()
            throws Exception {
        assertRecoveryOwnershipQuery("countOwnedRecoveryTask");
        assertRecoveryOwnershipQuery("lockOwnedRecoveryTask");
    }

    private void assertRecoveryOwnershipQuery(String methodName) throws Exception {
        Method method = WorkerAssignmentMutationGuardRepository.class.getDeclaredMethod(
                methodName,
                long.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();

        String sql = query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
                "user.username = :username",
                "task.review_recovery_task_status = 'planned'",
                "batch.review_recovery_batch_status = 'open'",
                "orders.order_id is null or ( orders.order_worker = task.review_recovery_task_worker )"
        );
        assertThat(sql).doesNotContain(
                "coalesce(orders.order_complete, 0) = 0"
        );
    }

    private void assertBadTaskOwnershipQuery(String methodName) throws Exception {
        Method method = WorkerAssignmentMutationGuardRepository.class.getDeclaredMethod(
                methodName,
                long.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();

        String sql = query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
                "orders.order_worker = task.bad_review_task_worker",
                "user.username = :username",
                "task.bad_review_task_status = 'new'"
        );
        assertThat(sql).doesNotContain(
                "coalesce(orders.order_complete, 0) = 0"
        );
    }
}
