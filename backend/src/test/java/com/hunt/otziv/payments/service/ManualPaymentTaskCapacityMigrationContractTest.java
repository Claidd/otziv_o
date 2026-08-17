package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManualPaymentTaskCapacityMigrationContractTest {

    @Test
    void migrationBackfillsTaskCommitmentAndKeepsTerminalNetUnbackedExposure() throws Exception {
        String resource = "db/migration/V1_10_254__manual_payment_task_profile_capacity.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql).contains("manual_task_commitment_kopecks BIGINT NOT NULL DEFAULT 0");
            assertThat(sql).contains("manual_task_overrun_ack_kopecks BIGINT NOT NULL DEFAULT 0");
            assertThat(sql).contains("target_overrun_acknowledged_kopecks BIGINT NULL");
            assertThat(sql).contains("manual_payment_task_id BIGINT NULL");
            assertThat(sql).contains("allocation.source_type = 'ACTUAL_PAYMENT'")
                    .contains("attribution.actual_manual_payment_task_id")
                    .contains("allocation.id = attribution.original_allocation_id")
                    .contains("SIGNAL SQLSTATE '45000'")
                    .contains("allocation.manual_payment_task_id <> task.id")
                    .contains("allocation.manual_payment_task_id IS NULL");
            assertThat(sql).contains("task_capacity_projected_overrun_kopecks BIGINT NULL");
            assertThat(sql).contains("task.target_amount_kopecks")
                    .contains("GREATEST(0, COALESCE(balance.confirmed_kopecks, 0))")
                    .contains("GREATEST(0, COALESCE(balance.pending_kopecks, 0))")
                    .contains("balance.unbacked_confirmed_kopecks")
                    .contains("entry.event_type = 'CONFIRMED_TO_TASK'")
                    .contains("source.backed_confirmed_kopecks")
                    .contains("source.negative_confirmed_kopecks")
                    .contains("WHEN entry.confirmed_delta_kopecks < 0");
            assertThat(sql).contains("task.status IN ('ACTIVE', 'PAUSED', 'NEEDS_ATTENTION')");
            assertThat(sql).contains("WHEN task.status IN ('COMPLETED', 'CANCELED')")
                    .contains("task.status IN ('COMPLETED', 'CANCELED')")
                    .contains("COALESCE(balance.unbacked_confirmed_kopecks, 0)) > 0");
            assertThat(sql).contains("profile.manual_task_overrun_ack_kopecks = 0");
        }
    }
}
