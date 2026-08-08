package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ContractorDirectSettlementRepositoryContractTest {

    @Test
    void reconciliationQueuesSelectOnlyInvoiceEvidenceSources() {
        assertReconciliationSource("findPaymentLinksForReconciliation", "PAYMENT_LINK");
        assertReconciliationSource("findCommonInvoicesForReconciliation", "COMMON_INVOICE");
    }

    @Test
    void terminalRowsArePeriodicallyDueEvenWithEarlierOrFutureApplicationTimestamps() {
        for (String methodName : List.of(
                "findPaymentLinksForReconciliation",
                "findCommonInvoicesForReconciliation"
        )) {
            Method method = Arrays.stream(ContractorPaymentAllocationRepository.class.getMethods())
                    .filter(value -> value.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            String query = method.getAnnotation(Query.class).value();
            assertThat(query)
                    .contains("a.lastReconciledAt <= :terminalDueBefore")
                    .contains("a.lastReconciledAt > :now")
                    .contains("updatedAt > a.lastReconciledAt");
        }
    }

    private void assertReconciliationSource(String methodName, String expectedSource) {
        Method method = Arrays.stream(ContractorPaymentAllocationRepository.class.getMethods())
                .filter(value -> value.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        String query = method.getAnnotation(Query.class).value();
        assertThat(query).contains(expectedSource).doesNotContain("DIRECT_SETTLEMENT");
    }
}
