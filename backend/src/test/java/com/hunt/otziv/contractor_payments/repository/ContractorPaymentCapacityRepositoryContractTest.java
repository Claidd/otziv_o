package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ContractorPaymentCapacityRepositoryContractTest {

    @Test
    void capacityAggregatesAreNativeCurrentLockingReads() throws Exception {
        Method ledger = ContractorRewardLedgerRepository.class.getMethod(
                "sumActiveForCapacityUpdate",
                Long.class
        );
        Method allocations = ContractorPaymentAllocationRepository.class.getMethod(
                "capacityTotalsForUpdate",
                Long.class,
                String.class
        );

        Query ledgerQuery = ledger.getAnnotation(Query.class);
        Query allocationQuery = allocations.getAnnotation(Query.class);
        assertThat(ledgerQuery.nativeQuery()).isTrue();
        assertThat(allocationQuery.nativeQuery()).isTrue();
        assertThat(ledgerQuery.value()).contains("SUM(", "FOR UPDATE");
        assertThat(allocationQuery.value()).contains("SUM(", "confirmed_kopecks", "returned_kopecks", "FOR UPDATE");
    }

    @Test
    void profileMutexQueriesDoNotImplicitlyLockUserRows() throws Exception {
        Method byRole = ContractorPaymentProfileRepository.class.getMethod(
                "findByUserIdAndRoleForUpdate", Long.class,
                com.hunt.otziv.contractor_payments.model.ContractorRole.class
        );
        Method allForUser = ContractorPaymentProfileRepository.class.getMethod(
                "findAllByUserIdForUpdate", Long.class
        );
        Method allById = ContractorPaymentProfileRepository.class.getMethod(
                "findAllByIdForUpdate", Collection.class
        );

        assertThat(byRole.getAnnotation(Query.class).value()).doesNotContainIgnoringCase("join fetch");
        assertThat(allForUser.getAnnotation(Query.class).value()).doesNotContainIgnoringCase("join fetch");
        assertThat(allById.getAnnotation(Query.class).value()).doesNotContainIgnoringCase("join fetch");
    }

    @Test
    void latestAttemptGenerationUsesNativeScalarLockingRead() throws Exception {
        Method latest = ContractorPaymentAllocationRepository.class.getMethod(
                "findLatestIdForUpdate",
                String.class,
                String.class,
                Long.class
        );

        Query query = latest.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).contains(
                "ORDER BY allocation.attempt_no DESC, allocation.id DESC",
                "LIMIT 1",
                "FOR UPDATE"
        );
        assertThat(latest.getReturnType()).isEqualTo(java.util.Optional.class);
    }

    @Test
    void dailyRoutingLimitCountsAllInvoiceAttemptsWithCurrentLockingRead() throws Exception {
        Method dailyTotals = ContractorPaymentAllocationRepository.class.getMethod(
                "dailyRoutingTotalsForUpdate",
                Long.class,
                String.class,
                LocalDateTime.class,
                LocalDateTime.class
        );

        Query query = dailyTotals.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).contains(
                "SUM(",
                "COUNT(*)",
                "PAYMENT_LINK",
                "COMMON_INVOICE",
                "reserved_at >= :from",
                "reserved_at < :to",
                "FOR UPDATE"
        );
        assertThat(query.value()).doesNotContain("DIRECT_SETTLEMENT", "allocation.status IN");
    }
}
