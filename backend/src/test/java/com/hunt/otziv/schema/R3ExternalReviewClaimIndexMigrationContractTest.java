package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class R3ExternalReviewClaimIndexMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_190__r3_review_external_stale_claim_index.sql";

    @Test
    void staleCheckingRecoveryHasItsOwnLeaseFirstOnlineIndex() throws Exception {
        String sql = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ALTER TABLE review_external_checks",
                "ADD INDEX idx_review_external_checks_stale_claim",
                "(status, processing_lease_until, attempt_count, review_external_check_id)",
                "ALGORITHM=INPLACE",
                "LOCK=NONE"
        );
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("insert ", "update ", "delete ", "drop ");
    }
}
