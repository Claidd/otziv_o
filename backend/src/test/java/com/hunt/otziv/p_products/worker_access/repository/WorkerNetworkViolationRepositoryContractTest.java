package com.hunt.otziv.p_products.worker_access.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.p_products.worker_access.service.WorkerNetworkViolationRetentionJob;
import com.hunt.otziv.p_products.worker_access.service.WorkerNetworkViolationService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

class WorkerNetworkViolationRepositoryContractTest {

    @Test
    void repositoryOnlyExposesExplicitQueries() {
        assertThat(Repository.class.isAssignableFrom(
                WorkerNetworkViolationRepository.class
        )).isTrue();
        assertThat(CrudRepository.class.isAssignableFrom(
                WorkerNetworkViolationRepository.class
        )).isFalse();

        for (Method method
                : WorkerNetworkViolationRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            assertThat(query)
                    .as(method.getName() + " должен использовать @Query")
                    .isNotNull();
            assertThat(query.nativeQuery())
                    .as(method.getName() + " должен использовать явный native SQL")
                    .isTrue();
        }
    }

    @Test
    void modifyingQueriesHaveTransactionBoundaries() {
        for (Method method
                : WorkerNetworkViolationRepository.class.getDeclaredMethods()) {
            if (method.getReturnType() != int.class) {
                continue;
            }
            assertThat(method.isAnnotationPresent(Modifying.class))
                    .as(method.getName() + " должен использовать @Modifying")
                    .isTrue();
            assertThat(method.isAnnotationPresent(Transactional.class))
                    .as(method.getName() + " должен иметь транзакционную границу")
                    .isTrue();
        }
    }

    @Test
    void episodeUpsertPreservesDeduplicationAndAttemptCounter() throws Exception {
        Method method = WorkerNetworkViolationRepository.class.getDeclaredMethod(
                "upsertEpisode",
                long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                java.time.LocalDateTime.class,
                java.time.LocalDateTime.class,
                String.class,
                String.class,
                String.class
        );
        String sql = method.getAnnotation(Query.class).value()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
                "insert into worker_network_violation_episodes",
                "on duplicate key update",
                "attempt_count = attempt_count + 1",
                "last_seen_at = values(last_seen_at)"
        );
    }

    @Test
    void readAndCleanupQueriesPreservePeriodBoundaries() throws Exception {
        Method read = WorkerNetworkViolationRepository.class.getDeclaredMethod(
                "findActiveForUsers",
                java.util.Collection.class,
                java.time.LocalDateTime.class,
                java.time.LocalDateTime.class
        );
        String readSql = normalized(read);
        assertThat(readSql).contains(
                "worker_user_id as userid",
                "first_seen_at as firstseenat",
                "last_seen_at as lastseenat",
                "worker_user_id in (:userids)",
                "last_seen_at >= :frominclusive",
                "first_seen_at < :toexclusive",
                "access_result <> 'invalidated'",
                "order by last_seen_at desc"
        );

        Method cleanup = WorkerNetworkViolationRepository.class.getDeclaredMethod(
                "deleteBefore",
                java.time.LocalDateTime.class
        );
        assertThat(normalized(cleanup)).contains(
                "delete from worker_network_violation_episodes",
                "last_seen_at < :cutoff"
        );
    }

    @Test
    void servicesDoNotOwnJdbcInfrastructure() {
        assertThat(Arrays.stream(WorkerNetworkViolationService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .noneMatch(type -> type.contains("Jdbc"))).isTrue();
        assertThat(Arrays.stream(WorkerNetworkViolationRetentionJob.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .noneMatch(type -> type.contains("Jdbc"))).isTrue();
    }

    private String normalized(Method method) {
        return method.getAnnotation(Query.class).value()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
