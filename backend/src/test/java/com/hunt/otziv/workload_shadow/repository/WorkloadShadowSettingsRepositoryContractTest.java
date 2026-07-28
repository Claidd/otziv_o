package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.workload_shadow.service.WorkloadShadowSettingsService;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowSettingsRepositoryContractTest {

    @Test
    void everyRuntimeSettingsRepositoryMethodUsesAnExplicitQuery() {
        for (Method method : WorkloadShadowSettingsRepository.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Query.class))
                    .as(method.getName() + " должен использовать @Query")
                    .isTrue();
        }
    }

    @Test
    void batchUpdateIsDeclaredAsModifying() throws NoSuchMethodException {
        Method update = WorkloadShadowSettingsRepository.class.getDeclaredMethod(
                "updateAllWithRevision",
                String.class,
                String.class,
                String.class,
                long.class
        );

        assertThat(update.isAnnotationPresent(Modifying.class)).isTrue();
    }

    @Test
    void settingsServiceDoesNotOwnJdbcInfrastructure() {
        boolean hasJdbcField = Arrays.stream(WorkloadShadowSettingsService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(type -> type.contains("JdbcTemplate"));

        assertThat(hasJdbcField).isFalse();
    }
}
