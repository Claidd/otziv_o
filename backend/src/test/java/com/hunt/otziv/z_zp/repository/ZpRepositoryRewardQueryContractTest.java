package com.hunt.otziv.z_zp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

class ZpRepositoryRewardQueryContractTest {

    @Test
    void activeOrderCancellationMutexIsPessimisticAndOrderedBySourceId() throws Exception {
        Method method = ZpRepository.class.getMethod(
                "findActiveByOrderIdForContractorLedgerUpdate",
                Long.class
        );
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(query("findActiveByOrderIdForContractorLedgerUpdate", Long.class))
                .contains(
                        "Z.ORDERID = :ORDERID",
                        "Z.ACTIVE = TRUE",
                        "ORDER BY Z.ID"
                );
    }

    @Test
    void initialMonthQueriesSelectOnlyActiveRoleCompatibleLegacyRows() throws Exception {
        String specialist = query(
                "findLegacySpecialistRewardIdsInPeriod",
                Long.class,
                LocalDate.class,
                LocalDate.class
        );
        String specialistAfterLock = query("countEligibleLegacySpecialistRewardForSync", Long.class);
        assertLegacyScope(specialist, "WORKERS", "WORKER_ID", "SPECIALIST", "ORDER_SPECIALIST_REWARD");
        assertLegacyScope(specialistAfterLock, "WORKERS", "WORKER_ID", "SPECIALIST", "ORDER_SPECIALIST_REWARD");
        assertThat(specialist).doesNotContain("ORDER_MANAGER_REWARD");
        assertThat(specialistAfterLock).doesNotContain("ORDER_MANAGER_REWARD");

        String manager = query(
                "findLegacyManagerRewardIdsInPeriod",
                Long.class,
                LocalDate.class,
                LocalDate.class
        );
        String managerAfterLock = query("countEligibleLegacyManagerRewardForSync", Long.class);
        assertLegacyScope(manager, "MANAGERS", "MANAGER_ID", "MANAGER", "ORDER_MANAGER_REWARD");
        assertLegacyScope(managerAfterLock, "MANAGERS", "MANAGER_ID", "MANAGER", "ORDER_MANAGER_REWARD");
        assertThat(manager).doesNotContain("ORDER_SPECIALIST_REWARD");
        assertThat(managerAfterLock).doesNotContain("ORDER_SPECIALIST_REWARD");
    }

    @Test
    void cabinetAndStatisticsQueriesExcludeInactiveRowsWithoutDroppingActiveRewardKinds() throws Exception {
        assertActiveRewardQuery(query(
                "getAllWorkerZpInPeriod",
                Long.class,
                LocalDate.class,
                LocalDate.class
        ));
        assertActiveRewardQuery(query(
                "getAllWorkerZp",
                Long.class,
                LocalDate.class,
                LocalDate.class
        ));
        assertActiveRewardQuery(query("findAllToDate", LocalDate.class, LocalDate.class));
        assertActiveRewardQuery(query("findStatRowsToDate", LocalDate.class, LocalDate.class));
        assertActiveRewardQuery(query(
                "findAllToDateByOwner",
                LocalDate.class,
                LocalDate.class,
                Set.class
        ));
        assertActiveRewardQuery(query(
                "findStatRowsToDateByOwner",
                LocalDate.class,
                LocalDate.class,
                Set.class
        ));
        assertActiveRewardQuery(query("findAllByOwner", Set.class));
        assertActiveRewardQuery(query(
                "findAllToDateByUser",
                LocalDate.class,
                LocalDate.class,
                Long.class
        ));
        assertActiveRewardQuery(query("sumByUserAndCreated", Long.class, LocalDate.class));
        assertActiveRewardQuery(query("countByUserAndCreated", Long.class, LocalDate.class));

        String allUsers = query("findAllUsersWithZpToDate", LocalDate.class, LocalDate.class);
        assertThat(allUsers)
                .contains("ZP_ACTIVE = 1")
                .doesNotContain("ZP_CONTRACTOR_ROLE", "ZP_SOURCE", "ZP_ORDER");
    }

    @Test
    void incompatibleSourceGuardUsesExactRoleMatrixAndBindsTaskSuffixToSameOrder() throws Exception {
        Method method = ZpRepository.class.getMethod("countActiveIncompatibleContractorRewardSources");
        Query annotation = method.getAnnotation(Query.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.nativeQuery()).isTrue();

        String sql = query("countActiveIncompatibleContractorRewardSources");
        assertThat(sql)
                .contains(
                        "REWARD.ZP_ACTIVE = 1",
                        "REWARD.ZP_CONTRACTOR_ROLE IS NOT NULL",
                        "CAST(REWARD.ZP_CONTRACTOR_ROLE AS BINARY)",
                        "CAST('MANAGER' AS BINARY)",
                        "CAST('SPECIALIST' AS BINARY)",
                        "ORDER_MANAGER_REWARD",
                        "ORDER_SPECIALIST_REWARD",
                        "PERFORMER_PRODUCT_REWARD",
                        "ORDER_COMPLETION_MANAGER",
                        "ORDER_COMPLETION_SPECIALIST",
                        "PERFORMER_PRODUCT_COMPLETION",
                        "FROM BAD_REVIEW_TASKS SOURCE_TASK",
                        "SOURCE_TASK.BAD_REVIEW_TASK_ORDER = REWARD.ZP_ORDER",
                        "BAD_REVIEW_DONE_MANAGER:",
                        "BAD_REVIEW_CANCEL_MANAGER:",
                        "BAD_REVIEW_DONE_SPECIALIST:",
                        "BAD_REVIEW_CANCEL_SPECIALIST:",
                        "SOURCE_TASK.BAD_REVIEW_TASK_ID",
                        "ELSE 1",
                        "END = 1"
                )
                .doesNotContain("SYNC_MARKER");
    }

    private void assertLegacyScope(
            String sql,
            String professionTable,
            String professionId,
            String exactRole,
            String roleSource
    ) {
        assertThat(sql)
                .contains(
                        "INNER JOIN " + professionTable,
                        professionId + " = Z.ZP_PROFESSION",
                        "USER_ID = Z.ZP_USER",
                        "Z.ZP_CONTRACTOR_ROLE IS NULL",
                        "CAST('" + exactRole + "' AS BINARY)",
                        "Z.ZP_ACTIVE = 1",
                        "Z.ZP_SOURCE IS NULL",
                        "TRIM(Z.ZP_SOURCE) = ''",
                        "CAST(Z.ZP_SOURCE AS BINARY)",
                        roleSource,
                        "PERFORMER_PRODUCT_REWARD"
                );
    }

    private void assertActiveRewardQuery(String query) {
        assertThat(query)
                .contains("ACTIVE = TRUE")
                .doesNotContain("CONTRACTORROLE", "CONTRACTOR_ROLE", "SOURCE", "ORDERID", "ZP_ORDER");
    }

    private String query(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ZpRepository.class.getMethod(methodName, parameterTypes);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        return query.value()
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
