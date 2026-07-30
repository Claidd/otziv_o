package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WorkloadDeliveryBatchTokenMigrationContractTest {

    @Test
    void sharedBatchTokensAreIndexedButNotUnique() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_156__workload_delivery_batch_tokens.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains(
                        "DROP INDEX uk_workload_transfer_offer_processing_token",
                        "ADD INDEX idx_workload_transfer_offer_processing_token",
                        "DROP INDEX uk_workload_transfer_emergency_notification_token",
                        "ADD INDEX idx_workload_transfer_emergency_notification_token"
                )
                .doesNotContain("ADD UNIQUE");
    }
}
