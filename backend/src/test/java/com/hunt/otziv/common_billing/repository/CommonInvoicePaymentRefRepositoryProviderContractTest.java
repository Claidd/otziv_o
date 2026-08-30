package com.hunt.otziv.common_billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

class CommonInvoicePaymentRefRepositoryProviderContractTest {

    @Test
    void repositoryExposesProviderScopedOrderAndPaymentLookups() throws Exception {
        Method byOrder = CommonInvoicePaymentRefRepository.class.getMethod(
                "findByProviderAndProviderOrderId",
                String.class,
                String.class
        );
        Method byPayment = CommonInvoicePaymentRefRepository.class.getMethod(
                "findByProviderAndProviderPaymentId",
                String.class,
                String.class
        );

        assertThat(byOrder.getReturnType()).isEqualTo(Optional.class);
        assertThat(byPayment.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void reconciliationCandidatesAreProviderScoped() throws Exception {
        Method method = CommonInvoicePaymentRefRepository.class.getMethod(
                "findProviderReconciliationCandidates",
                String.class,
                Collection.class,
                LocalDateTime.class,
                Pageable.class
        );
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(normalize(query.value()))
                .contains("ref.provider = :provider")
                .contains("ref.status in :statuses")
                .contains("ref.updatedAt <= :updatedBefore")
                .contains("order by ref.updatedAt asc, ref.id asc");
    }

    @Test
    void currentProviderRefsAreLockedAndProviderScoped() throws Exception {
        Method method = CommonInvoicePaymentRefRepository.class.getMethod(
                "findCurrentProviderRefsForUpdate",
                Long.class,
                String.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(query).isNotNull();
        assertThat(normalize(query.value()))
                .contains("ref.invoice.id = :invoiceId")
                .contains("ref.provider = :provider")
                .contains("ref.status = :status")
                .contains("order by ref.updatedAt desc, ref.id desc");
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void archivedTbankCancelCandidatesAreProviderScopedBeforePagination() throws Exception {
        Method method = CommonInvoicePaymentRefRepository.class.getMethod(
                "findCancelableRefs",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDateTime.class,
                LocalDateTime.class,
                int.class,
                Pageable.class
        );
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        String normalized = normalize(query.value());
        assertThat(normalized)
                .contains("ref.provider is null")
                .contains("trim(ref.provider) = ''")
                .contains("upper(ref.provider) = upper(:provider)")
                .contains("and ( ref.status = :pendingStatus")
                .contains("order by ref.updatedAt asc, ref.id asc");
    }

    @Test
    void providerLifecycleRefsAreLockedForArchiveIntent() throws Exception {
        Method method = CommonInvoicePaymentRefRepository.class.getMethod(
                "findProviderRefsForUpdate",
                Long.class,
                String.class,
                Collection.class
        );
        Query query = method.getAnnotation(Query.class);
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(query).isNotNull();
        assertThat(normalize(query.value()))
                .contains("ref.invoice.id = :invoiceId")
                .contains("ref.provider = :provider")
                .contains("ref.status in :statuses");
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private static String normalize(String query) {
        return query.replaceAll("\\s+", " ").trim();
    }
}
