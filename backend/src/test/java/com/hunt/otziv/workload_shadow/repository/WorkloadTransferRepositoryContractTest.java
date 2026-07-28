package com.hunt.otziv.workload_shadow.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.workload_shadow.service.WorkloadShadowTransferSimulationService;
import com.hunt.otziv.workload_shadow.transfer.WorkloadTransferGraphQueryService;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadTransferRepositoryContractTest {

    @Test
    void everyDeclaredRuntimeRepositoryMethodHasQueryAnnotation() {
        assertQueryOnly(WorkloadTransferGraphRepository.class);
        assertQueryOnly(WorkloadShadowTransferRepository.class);
    }

    @Test
    void transferServicesDoNotOwnJdbcTemplates() {
        assertNoJdbcFields(WorkloadTransferGraphQueryService.class);
        assertNoJdbcFields(WorkloadShadowTransferSimulationService.class);
    }

    private void assertQueryOnly(Class<?> repositoryType) {
        for (Method method : repositoryType.getDeclaredMethods()) {
            assertTrue(
                    method.isAnnotationPresent(Query.class),
                    () -> repositoryType.getSimpleName() + "." + method.getName()
                            + " должен быть объявлен через @Query"
            );
        }
    }

    private void assertNoJdbcFields(Class<?> serviceType) {
        boolean hasJdbc = Arrays.stream(serviceType.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(name -> name.contains("JdbcTemplate"));
        assertFalse(hasJdbc, () -> serviceType.getSimpleName() + " не должен владеть JdbcTemplate");
    }
}
