package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

class R2SchemaFoundationMigrationContractTest {

    private static final List<String> MIGRATIONS = List.of(
            "V1_10_166__r2_users_concurrency_and_deactivation.sql",
            "V1_10_167__r2_companies_row_version.sql",
            "V1_10_168__r2_orders_row_version.sql",
            "V1_10_169__r2_order_details_row_version.sql",
            "V1_10_170__r2_reviews_row_version.sql",
            "V1_10_171__r2_mobile_push_revocation_epoch.sql",
            "V1_10_172__r2_review_check_capabilities.sql",
            "V1_10_173__r2_integration_outbox.sql",
            "V1_10_174__r2_command_idempotency.sql",
            "V1_10_175__r2_scheduler_leases.sql",
            "V1_10_176__r2_scheduler_job_runs.sql",
            "V1_10_177__r2_review_external_claim_columns.sql",
            "V1_10_178__r2_review_external_claim_indexes.sql",
            "V1_10_179__r2_review_external_claim_constraint.sql",
            "V1_10_180__r2_payment_link_token_hash.sql",
            "V1_10_181__r2_payment_link_token_hash_index.sql",
            "V1_10_182__r2_archive_payment_link_token_hash.sql",
            "V1_10_183__r2_archive_payment_link_token_hash_index.sql",
            "V1_10_184__r2_common_invoice_token_hash.sql",
            "V1_10_185__r2_common_invoice_token_hash_index.sql",
            "V1_10_186__r2_archive_common_invoice_token_hash.sql",
            "V1_10_187__r2_archive_common_invoice_token_hash_index.sql"
    );

    private static final Pattern DDL = Pattern.compile(
            "(?im)^\\s*(ALTER\\s+TABLE|CREATE\\s+TABLE)\\b"
    );
    private static final Pattern DML = Pattern.compile(
            "(?im)^\\s*(INSERT|UPDATE|DELETE)\\s+"
    );

    @Test
    void eachFlywayVersionContainsExactlyOneAtomicDdl() throws Exception {
        assertThat(MIGRATIONS).hasSize(22);

        for (String migration : MIGRATIONS) {
            String sql = migrationSql(migration);

            assertThat(DDL.matcher(sql).results().count())
                    .as(migration)
                    .isEqualTo(1);
            assertThat(DML.matcher(sql).find())
                    .as(migration)
                    .isFalse();
            assertThat(sql.toLowerCase(Locale.ROOT))
                    .as(migration)
                    .doesNotContain(
                            "prepare ",
                            "execute ",
                            "foreign_key_checks"
                    );
        }
    }

    @Test
    void migrationsAreAdditiveAndDoNotBackfillCapabilities() throws Exception {
        String sql = combinedSql();

        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("insert into review_check_capabilities")
                .doesNotContain("workload_transfer_")
                .doesNotContain("scheduled_client_messages");
    }

    @Test
    void smallActorFkTablesUseOneChecksOnCopyDdl() throws Exception {
        String users = migrationSql(MIGRATIONS.get(0));
        String pushTokens = migrationSql(MIGRATIONS.get(5));

        assertThat(users)
                .contains(
                        "ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0",
                        "ADD COLUMN deactivated_at DATETIME(6) NULL",
                        "fk_users_deactivated_by_user",
                        "ck_users_deactivation_metadata",
                        "ALGORITHM=COPY",
                        "LOCK=SHARED"
                );
        assertThat(pushTokens)
                .contains(
                        "ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0",
                        "ADD COLUMN revoked_at DATETIME(6) NULL",
                        "ADD COLUMN revoked_reason VARCHAR(160) NULL",
                        "ADD COLUMN revoked_by_user_id BIGINT NULL",
                        "fk_mobile_push_tokens_revoked_by_user",
                        "ck_mobile_push_tokens_active_revocation",
                        "ALGORITHM=COPY",
                        "LOCK=SHARED"
                );
        assertThat(users + pushTokens).doesNotContain("foreign_key_checks");
    }

    @Test
    void rowVersionsUseTheAgreedMinimalScope() throws Exception {
        String sql = combinedSql();

        assertThat(sql)
                .contains(
                        "ALTER TABLE users",
                        "ALTER TABLE companies",
                        "ALTER TABLE orders",
                        "ALTER TABLE order_details",
                        "ALTER TABLE reviews"
                );
        assertThat(StringUtils.countOccurrencesOf(
                sql,
                "ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0"
        )).isEqualTo(5);
    }

    @Test
    void reviewCheckCapabilitiesAreEmptyHashOnlyAndArchiveSafe()
            throws Exception {
        String sql = migrationSql(MIGRATIONS.get(6));

        assertThat(sql)
                .contains(
                        "order_detail_id BINARY(16) NOT NULL",
                        "token_hash BINARY(32) NOT NULL",
                        "UNIQUE KEY uk_review_check_capabilities_token_hash",
                        "token_type IN ('LEGACY_UUID', 'OPAQUE')",
                        "scope_mask BIGINT UNSIGNED NOT NULL",
                        "CHECK (scope_mask > 0)",
                        "expires_at DATETIME(6) NULL",
                        "revoked_at DATETIME(6) NULL"
                )
                .doesNotContain(
                        "token VARCHAR",
                        "capability_scope",
                        "FOREIGN KEY (order_detail_id)",
                        "UNIQUE KEY uk_review_check_capabilities_order_detail",
                        "INSERT INTO"
                );
    }

    @Test
    void paymentHashesAreVirtualAndIndexesAreSeparateVersions()
            throws Exception {
        String sql = combinedSql();
        String generatedHash =
                "GENERATED ALWAYS AS (UNHEX(SHA2(token, 256))) VIRTUAL";

        assertThat(StringUtils.countOccurrencesOf(sql, generatedHash)).isEqualTo(4);
        assertThat(sql)
                .contains(
                        "uk_payment_links_token_hash",
                        "uk_archive_payment_links_token_hash",
                        "uk_common_invoices_token_hash",
                        "uk_archive_common_invoices_token_hash",
                        "ALGORITHM=INSTANT",
                        "ALGORITHM=INPLACE",
                        "LOCK=NONE"
                )
                .doesNotContain(
                        "GENERATED ALWAYS AS (UNHEX(SHA2(token, 256))) STORED"
                );
    }

    @Test
    void asynchronousFoundationsHaveDeduplicationAndLeaseFences()
            throws Exception {
        String sql = combinedSql();

        assertThat(sql)
                .contains(
                        "CREATE TABLE integration_outbox",
                        "UNIQUE KEY uk_integration_outbox_dedup_hash",
                        "ck_integration_outbox_processing_lease",
                        "CREATE TABLE command_idempotency",
                        "UNIQUE KEY uk_command_idempotency_scope_key_hash",
                        "NOT NULL DEFAULT 'PENDING'",
                        "ck_command_idempotency_processing_lease",
                        "CREATE TABLE scheduler_leases",
                        "fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 1",
                        "CREATE TABLE scheduler_job_runs",
                        "UNIQUE KEY uk_scheduler_job_runs_job_run_key (job_name, run_key)",
                        "ADD COLUMN deduplication_key_hash BINARY(32) NULL",
                        "ADD COLUMN processing_token CHAR(36)",
                        "ADD UNIQUE INDEX uk_review_external_checks_dedup_hash",
                        "ADD UNIQUE INDEX uk_review_external_checks_processing_token",
                        "ADD INDEX idx_review_external_checks_due_claim",
                        "ck_review_external_checks_processing_lease"
                );
    }

    private String combinedSql() throws Exception {
        StringBuilder sql = new StringBuilder();
        for (String migration : MIGRATIONS) {
            sql.append(migrationSql(migration)).append('\n');
        }
        return sql.toString();
    }

    private String migrationSql(String migration) throws Exception {
        return new ClassPathResource("db/migration/" + migration)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
