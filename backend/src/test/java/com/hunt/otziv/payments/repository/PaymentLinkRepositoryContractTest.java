package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class PaymentLinkRepositoryContractTest {

    @Test
    void bulkManualExpirationAdvancesTheOptimisticLockVersion() throws NoSuchMethodException {
        Method method = PaymentLinkRepository.class.getMethod(
                "expireManualLinks",
                Collection.class,
                Collection.class,
                PaymentLinkStatus.class,
                String.class,
                LocalDateTime.class
        );

        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value().replaceAll("\\s+", " "))
                .contains("link.rowVersion = link.rowVersion + 1");
    }

    @Test
    void adminRowsAndSummaryFilterDurablePrivilegedContractorRecipients() {
        Method pageMethod = methodNamed("findAdminPage");
        Method summaryMethod = methodNamed("summarizeAdminPage");

        Query pageQuery = pageMethod.getAnnotation(Query.class);
        Query summaryQuery = summaryMethod.getAnnotation(Query.class);

        assertThat(pageQuery).isNotNull();
        assertPrivilegedTargetFilter(pageQuery.value());
        assertPrivilegedTargetFilter(pageQuery.countQuery());
        assertThat(summaryQuery).isNotNull();
        assertPrivilegedTargetFilter(summaryQuery.value());
    }

    private static Method methodNamed(String name) {
        return Arrays.stream(PaymentLinkRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertPrivilegedTargetFilter(String query) {
        assertThat(query.replaceAll("\\s+", " "))
                .contains(":excludePrivilegedTargets = false")
                .contains("allocation.id = link.contractorAllocationId")
                .contains("JOIN allocation.recipientProfile recipientProfile")
                .contains("recipientRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')");
    }
}
