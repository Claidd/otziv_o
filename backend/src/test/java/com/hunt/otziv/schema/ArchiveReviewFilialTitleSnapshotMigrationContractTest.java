package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ArchiveReviewFilialTitleSnapshotMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_214__archive_review_filial_title_snapshot.sql";

    @Test
    void legacyBackfillDoesNotInventAReviewFilialTitleFromADifferentOrderFilial() throws Exception {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ADD COLUMN review_filial_title_snapshot VARCHAR(255) NULL",
                "WHEN ar.review_filial IS NULL OR ar.review_filial = ao.order_filial",
                "ELSE NULL"
        );
    }
}
