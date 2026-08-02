package com.hunt.otziv.schema;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class JoinTableIntegrityMigrationContractTest {

    @Test
    void associationMigrationDeduplicatesBeforeAddingCompositeKeys() throws Exception {
        String sql = migration("V1_10_201__join_table_integrity.sql");

        assertThat(sql)
                .contains("DELETE FROM workers_companies")
                .contains("migration_dedupe_id")
                .contains("PRIMARY KEY (company_id, worker_id)")
                .contains("PRIMARY KEY (user_id, operator_id)")
                .contains("PRIMARY KEY (user_id, manager_id)")
                .contains("PRIMARY KEY (user_id, worker_id)")
                .contains("PRIMARY KEY (user_id, marketolog_id)");
        assertThat(sql.indexOf("DELETE duplicate_link"))
                .isLessThan(sql.indexOf("PRIMARY KEY (company_id, worker_id)"));
    }

    @Test
    void redundantIndexCleanupIsConditionalAndKeepsCanonicalUniqueIndexes() throws Exception {
        String sql = migration("V1_10_202__remove_redundant_indexes.sql");

        assertThat(sql)
                .contains("information_schema.statistics")
                .contains("DROP INDEX idx_reviews_filial")
                .contains("DROP INDEX idx_filial_url")
                .contains("DROP INDEX idx_telephone_number")
                .contains("DROP INDEX email_UNIQUE")
                .contains("DROP INDEX username_UNIQUE")
                .contains("DROP INDEX id_UNIQUE")
                .doesNotContain("DROP INDEX uc_filial_url")
                .doesNotContain("DROP INDEX telephone_number");
    }

    private static String migration(String name) throws Exception {
        try (var input = new ClassPathResource("db/migration/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
