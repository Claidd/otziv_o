package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Explicit rehearsal against a disposable clone of the production-like
 * schema. It is disabled in ordinary test runs and must never point at the
 * live application database.
 */
class ContractorPaymentProdLikeUpgradeRehearsalTest {

    @Test
    void upgradesExactV217CloneToLatestAndValidatesContractorSchema() throws Exception {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(optional("OTZIV_CONTRACTOR_MIGRATION_REHEARSAL")),
                "explicit production-like migration rehearsal is disabled"
        );
        String url = required("OTZIV_CONTRACTOR_MIGRATION_REHEARSAL_JDBC_URL");
        String username = required("OTZIV_CONTRACTOR_MIGRATION_REHEARSAL_USERNAME");
        String password = required("OTZIV_CONTRACTOR_MIGRATION_REHEARSAL_PASSWORD");
        assertThat(url)
                .as("rehearsal must use the explicitly named disposable database")
                .contains("contractor_migration_rehearsal")
                .doesNotEndWith("/otziv");

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("1.10.217"));

        flyway.migrate();
        flyway.validate();

        assertThat(flyway.info().current().getVersion())
                .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("1.10.229"));

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'contractor_direct_settlements',
                        'contractor_payment_accounting_phase',
                        'contractor_payment_rollout_state',
                        'contractor_completion_reward_markers',
                        'contractor_completion_reward_repair_state',
                        'contractor_completion_cutover_state'
                      )
                    """)).isEqualTo(6L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM contractor_payment_rollout_state
                    WHERE id = 1
                      AND accounting_authority = 'LEGACY'
                      AND routing_requested = FALSE
                      AND attribution_start_date IS NULL
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM contractor_completion_cutover_state
                    """)).isZero();
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_contractor_completion_cutover_singleton'
                      AND check_clause LIKE '%`id` = 1%'
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'contractor_payment_allocations'
                      AND column_name = 'source_generation_snapshot'
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND collation_name = 'utf8mb4_unicode_ci'
                      AND (
                        (table_name IN (
                          'payment_links',
                          'archive_payment_links',
                          'common_invoices',
                          'archive_common_invoices'
                        ) AND column_name = 'shadow_route_generation')
                        OR
                        (table_name = 'contractor_payment_allocations'
                          AND column_name = 'source_generation_snapshot')
                      )
                    """)).isEqualTo(5L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM app_settings
                    WHERE setting_key = 'contractor-payments.shadow-preparation-started-at'
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM app_settings
                    WHERE setting_key = 'contractor-payments.live-readiness-confirmed'
                      AND setting_value = 'false'
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'zp'
                      AND column_name = 'zp_completion_idempotency_key'
                      AND extra LIKE '%STORED GENERATED%'
                    """)).isEqualTo(1L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'contractor_payment_allocations'
                      AND column_name IN (
                        'routing_decision_reason',
                        'specialist_rejection_reason',
                        'manager_rejection_reason'
                      )
                    """)).isEqualTo(3L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND (
                        (table_name = 'contractor_payment_profiles'
                          AND column_name = 'payment_comment'
                          AND character_maximum_length = 2048)
                        OR
                        (table_name = 'contractor_payment_allocations'
                          AND column_name = 'payment_comment_snapshot'
                          AND character_maximum_length = 2048)
                      )
                    """)).isEqualTo(2L);
            assertThat(count(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'ck_common_invoices_contractor_pii_blank',
                        'ck_archive_common_invoices_contractor_pii_blank'
                      )
                      AND check_clause LIKE '%payment_route_instruction_text%'
                    """)).isEqualTo(2L);
        }
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String required(String name) {
        String value = optional(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the explicit rehearsal");
        }
        return value.trim();
    }

    private String optional(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? System.getenv(name) : value;
    }
}
