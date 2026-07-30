package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

class WorkloadTransferOfferScopeContractTest {

    @Test
    void callbackAndDeliveryProjectionsExposeManagerIdWithoutExtraReads()
            throws NoSuchMethodException {
        assertThat(
                WorkloadTransferOfferRepository.DeliveryProjection.class
                        .getMethod("getManagerId")
                        .getReturnType()
        ).isEqualTo(Long.class);
        assertThat(
                WorkloadTransferOfferRepository.CallbackProjection.class
                        .getMethod("getManagerId")
                        .getReturnType()
        ).isEqualTo(Long.class);
        assertThat(query("findClaimedOffers", String.class))
                .contains("workflow.manager_id AS managerId");
        assertThat(query("findCallbackOffer", String.class))
                .contains("workflow.manager_id AS managerId");
    }

    @Test
    void callbackMutationsGuardManagerAndSettingsRevision()
            throws NoSuchMethodException {
        Class<?>[] signature = {
                String.class,
                long.class,
                int.class,
                long.class,
                Long.class,
                long.class,
                LocalDateTime.class
        };

        assertCallbackGuard("accept", signature);
        assertCallbackGuard("decline", signature);
    }

    @Test
    void excludedClaimedOffersAreCancelledByOneExplicitBulkQuery()
            throws NoSuchMethodException {
        Method method = WorkloadTransferOfferRepository.class
                .getDeclaredMethod(
                        "cancelClaimedOffersOutsideScope",
                        String.class,
                        List.class,
                        String.class
                );
        assertThat(method.getAnnotation(Modifying.class)).isNotNull();
        String sql = query(method);
        assertThat(sql)
                .contains("offer.processing_token = :processingToken")
                .contains("offer.workload_transfer_offer_id IN (:offerIds)")
                .contains("offer.status = 'CANCELLED'")
                .contains("workflow.status = 'CANCELLED'")
                .contains("workflow.active = FALSE")
                .contains("candidate.status = 'CANCELLED'");
    }

    private void assertCallbackGuard(
            String methodName,
            Class<?>[] signature
    ) throws NoSuchMethodException {
        Method method = WorkloadTransferOfferRepository.class
                .getDeclaredMethod(methodName, signature);
        assertThat(method.getAnnotation(Modifying.class)).isNotNull();
        assertThat(query(method))
                .contains("workflow.manager_id = :managerId")
                .contains("'workload.live.settings-revision'")
                .contains("= :settingsRevision");
    }

    private String query(
            String methodName,
            Class<?>... signature
    ) throws NoSuchMethodException {
        return query(
                WorkloadTransferOfferRepository.class
                        .getDeclaredMethod(methodName, signature)
        );
    }

    private String query(Method method) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        return query.value();
    }
}
