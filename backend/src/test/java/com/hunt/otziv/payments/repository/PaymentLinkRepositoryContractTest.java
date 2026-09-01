package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
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

    @Test
    void remainingConfirmedPaymentGuardIsOrderWideAndNotTimeOrdered() throws NoSuchMethodException {
        Method method = PaymentLinkRepository.class.getMethod(
                "existsOtherConfirmedPayment",
                Long.class,
                Long.class
        );

        Query query = method.getAnnotation(Query.class);
        String normalized = query.value().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("link.order.id = :orderId")
                .contains("link.id <> :returnedLinkId")
                .contains("PaymentLinkStatus.CONFIRMED")
                .contains("PaymentLinkStatus.AMOUNT_MISMATCH")
                .doesNotContain("paidAt")
                .doesNotContain("createdAt")
                .doesNotContain("returnedAt");
    }

    @Test
    void confirmedPrepaymentRecoveryIsLockedReadyOnlyAndUniquePerOrder()
            throws NoSuchMethodException {
        Method lockedLookup = PaymentLinkRepository.class.getMethod(
                "findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc",
                Long.class,
                PaymentLinkStatus.class,
                String.class
        );
        Lock lock = lockedLookup.getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);

        Method candidateQuery = PaymentLinkRepository.class.getMethod(
                "findConfirmedPrepaymentRecoveryOrderIds",
                String.class,
                LocalDateTime.class,
                Pageable.class
        );
        Query query = candidateQuery.getAnnotation(Query.class);
        String normalized = query.value().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("PaymentLinkStatus.CONFIRMED")
                .contains("link.lastError LIKE CONCAT(:lastErrorPrefix, '%')")
                .contains("link.paidAt IS NOT NULL")
                .contains("link.paidAt <= :attemptBefore")
                .contains("link.order.complete = true OR link.order.counter >= link.order.amount")
                .contains("NOT EXISTS")
                .contains("ReviewRecoveryTaskStatus.PLANNED")
                .contains("ReviewRecoveryBatchStatus.OPEN")
                .contains("GROUP BY link.order.id")
                .contains("ORDER BY MAX(link.updatedAt), MIN(link.paidAt), MIN(link.id)");
    }

    @Test
    void recentNotificationUpdateCannotDelayAnOldDurablePrepayment() {
        Method candidateQuery = methodNamed("findConfirmedPrepaymentRecoveryOrderIds");
        Query query = candidateQuery.getAnnotation(Query.class);
        String normalized = query.value().replaceAll("\\s+", " ");
        String eligibility = normalized.substring(
                normalized.indexOf(" WHERE "),
                normalized.indexOf(" GROUP BY ")
        );

        // paidAt is written with durable CONFIRMED prepayment evidence and is
        // stable. updatedAt may be recent solely because a notification retry
        // touched the row, so it must influence ordering but never eligibility.
        assertThat(eligibility)
                .contains("link.paidAt IS NOT NULL")
                .contains("link.paidAt <= :attemptBefore")
                .doesNotContain("link.updatedAt");
        assertThat(normalized.substring(normalized.indexOf(" ORDER BY ")))
                .startsWith(" ORDER BY MAX(link.updatedAt)");
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
