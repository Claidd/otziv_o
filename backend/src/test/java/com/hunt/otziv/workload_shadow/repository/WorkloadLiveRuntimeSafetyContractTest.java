package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadLiveRuntimeSafetyContractTest {

    @Test
    void migrationsAddRevisionQuotaFenceAndInactiveKeyboardState()
            throws Exception {
        String runtimeMigration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V1_10_235__workload_live_runtime_safety.sql"
        ));
        String keyboardMigration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V1_10_236__workload_offer_keyboard_activation.sql"
        ));

        assertThat(runtimeMigration)
                .contains("workload_shadow_runs")
                .contains("settings_revision")
                .contains("workload_shadow_worker_daily")
                .contains("live_settings_revision")
                .contains("shadow_settings_revision")
                .contains("workload_live_daily_quota_locks")
                .doesNotContain("UPDATE app_settings");
        assertThat(keyboardMigration)
                .contains("keyboard_activated")
                .contains("DEFAULT b'0'");
    }

    @Test
    void runtimeAndApplyQueriesAreFailClosedAroundLastSuccessfulRun()
            throws Exception {
        String runtime = query(
                WorkloadLiveRuntimeSafetyRepository.class,
                "runtimeState"
        );
        String apply = query(
                WorkloadTransferApplyGuardRepository.class,
                "lockGuard",
                long.class
        );

        assertThat(runtime)
                .contains("successful.status = 'SUCCEEDED'")
                .contains("running.status = 'RUNNING'")
                .contains("mismatchedCurrentSnapshotCount")
                .contains("mismatchedActiveCaseCount");
        assertThat(apply)
                .contains("workflow.status = 'APPLYING'")
                .contains("transfer_case.status = 'SHADOW_PENDING'")
                .contains("source_current.diagnostic_status = 'OK'")
                .contains("workflow.shadow_settings_revision")
                .contains("FOR UPDATE");
    }

    @Test
    void callbacksRequireDurablyActivatedKeyboard() throws Exception {
        assertThat(query(
                WorkloadTransferOfferRepository.class,
                "findCallbackOffer",
                String.class
        )).contains("offer.keyboard_activated = TRUE");
        assertThat(query(
                WorkloadTransferOfferRepository.class,
                "accept",
                String.class,
                long.class,
                int.class,
                long.class,
                Long.class,
                long.class,
                java.time.LocalDateTime.class
        )).contains("offer.keyboard_activated = TRUE");
        assertThat(query(
                WorkloadTransferOfferRepository.class,
                "decline",
                String.class,
                long.class,
                int.class,
                long.class,
                Long.class,
                long.class,
                java.time.LocalDateTime.class
        )).contains("offer.keyboard_activated = TRUE");
    }

    private String query(
            Class<?> repository,
            String method,
            Class<?>... parameterTypes
    ) throws Exception {
        return repository.getDeclaredMethod(method, parameterTypes)
                .getAnnotation(Query.class)
                .value();
    }
}
