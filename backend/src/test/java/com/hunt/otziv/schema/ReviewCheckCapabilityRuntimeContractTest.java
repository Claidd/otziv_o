package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ReviewCheckCapabilityRuntimeContractTest {

    @Test
    void capabilityMigrationStoresHashesAndKeepsResourceArchiveSafe() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_172__r2_review_check_capabilities.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains(
                        "token_hash BINARY(32) NOT NULL",
                        "token_type IN ('LEGACY_UUID', 'OPAQUE')",
                        "scope_mask BIGINT UNSIGNED NOT NULL",
                        "last_used_at DATETIME(6) NULL"
                )
                .doesNotContain(
                        "raw_token",
                        "token VARCHAR",
                        "FOREIGN KEY (order_detail_id)",
                        "INSERT INTO review_check_capabilities"
                );
    }

    @Test
    void mutationLockMigrationUsesOneArchiveSafeRowPerOrder() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_194__review_check_mutation_locks.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains(
                        "CREATE TABLE review_check_mutation_locks",
                        "order_id BIGINT NOT NULL",
                        "PRIMARY KEY (order_id)"
                )
                .doesNotContain("FOREIGN KEY");
    }
}
