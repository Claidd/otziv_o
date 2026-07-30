package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class WorkloadOfferStagingBatchMigrationContractTest {

    @Test
    void stagingBatchTokenIsNullableAndIndexedForBoundedBulkBinding()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_157__workload_offer_staging_batches.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains(
                        "ADD COLUMN staging_batch_token CHAR(36) NULL",
                        "ADD INDEX idx_workload_transfer_offer_staging_batch",
                        "staging_batch_token",
                        "workload_transfer_offer_id"
                )
                .doesNotContain("ADD UNIQUE");
    }
}
