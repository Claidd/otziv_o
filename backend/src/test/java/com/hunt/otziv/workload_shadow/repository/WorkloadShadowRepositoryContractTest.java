package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

class WorkloadShadowRepositoryContractTest {

    private static final List<Class<?>> RUNTIME_REPOSITORIES = List.of(
            WorkloadShadowEventRepository.class,
            WorkloadShadowMonitorRepository.class,
            WorkloadShadowProjectionRepository.class,
            WorkloadShadowRecalculationLockRepository.class,
            WorkloadShadowRunRepository.class,
            WorkloadShadowSettingsRepository.class,
            WorkloadShadowTransferRepository.class,
            WorkloadShadowWorkerDailyRepository.class,
            WorkloadTransferGraphRepository.class,
            WorkloadTransferPreferenceRepository.class
    );

    @Test
    void everyRuntimeMethodUsesAnExplicitQuery() {
        for (Class<?> repositoryType : RUNTIME_REPOSITORIES) {
            for (Method method : repositoryType.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Query.class))
                        .as(repositoryType.getSimpleName() + "." + method.getName()
                                + " должен использовать @Query")
                        .isTrue();
            }
        }
    }

    @Test
    void noRepositoryExposesGeneratedCrudQueries() {
        for (Class<?> repositoryType : RUNTIME_REPOSITORIES) {
            assertThat(CrudRepository.class.isAssignableFrom(repositoryType))
                    .as(repositoryType.getSimpleName()
                            + " не должен открывать неявные CRUD-запросы")
                    .isFalse();
        }
    }

    @Test
    void everyIntegerDmlMethodIsMarkedAsModifying() {
        for (Class<?> repositoryType : RUNTIME_REPOSITORIES) {
            for (Method method : repositoryType.getDeclaredMethods()) {
                if (method.getReturnType() != int.class) {
                    continue;
                }
                assertThat(method.isAnnotationPresent(Modifying.class))
                        .as(repositoryType.getSimpleName() + "." + method.getName()
                                + " должен использовать @Modifying")
                        .isTrue();
            }
        }
    }
}
