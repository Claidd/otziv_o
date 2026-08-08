package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class ContractorPaymentMigrationContractTest {

    @Test
    void alreadyAppliedV217KeepsItsFlywayChecksum() throws IOException {
        String creation = migration("/db/migration/V1_10_217__contractor_reward_payment_shadow.sql");

        assertThat(flywayChecksum(creation)).isEqualTo(2_233_343_901L);
    }

    @Test
    void accountingHistorySurvivesOrderAndSourceArchival() throws IOException {
        String creation = migration("/db/migration/V1_10_217__contractor_reward_payment_shadow.sql");
        String migration = migration("/db/migration/V1_10_218__contractor_payment_accounting_history.sql");
        String delivery = migration("/db/migration/V1_10_219__contractor_route_delivery_and_transfer.sql");
        String identityHardening = migration(
                "/db/migration/V1_10_220__durable_order_specialist_identity.sql"
        );
        String preparation = migration(
                "/db/migration/V1_10_222__contractor_shadow_route_preparation.sql"
        );
        String generationCollation = migration(
                "/db/migration/V1_10_228__contractor_shadow_generation_collation.sql"
        );

        assertThat(creation)
                .contains("CONSTRAINT fk_contractor_payment_allocations_order")
                .contains("CONSTRAINT fk_contractor_payment_allocations_common_invoice");

        assertThat(migration)
                .contains("DROP TABLE contractor_reward_applications")
                .contains("DROP FOREIGN KEY fk_contractor_payment_allocations_order")
                .contains("DROP FOREIGN KEY fk_contractor_payment_allocations_common_invoice")
                .contains("DROP FOREIGN KEY fk_contractor_reward_ledger_zp")
                .contains("last_reconciled_at")
                .contains("ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0")
                .contains("FROM workers w")
                .contains("FROM managers m")
                .contains("CREATE TABLE contractor_shadow_backfill_claims")
                .contains("actor VARCHAR(150) NOT NULL DEFAULT 'system'")
                .contains("ALTER TABLE archive_zp")
                .contains("zp_contractor_role")
                .contains("zp_attribution_final")
                .contains("zp_attribution_snapshot")
                .contains("zp_updated_at");
        assertThat(migration).doesNotContain("fk_contractor_reward_sync_marker_zp");

        assertThat(delivery)
                .contains("ALTER TABLE archive_payment_links")
                .contains("ADD COLUMN contractor_allocation_id BIGINT NULL AFTER payment_profile_name")
                .contains("ADD COLUMN manual_bank_name VARCHAR(120) NULL AFTER manual_recipient_name")
                .contains("ALTER TABLE archive_common_invoices")
                .contains("ADD COLUMN payment_route_type VARCHAR(32) NULL AFTER payment_method")
                .contains("ADD COLUMN payment_route_manual_bank_name VARCHAR(120) NULL")
                .contains("ADD COLUMN client_reported_at DATETIME(6) NULL");

        assertThat(identityHardening)
                .contains("ALTER TABLE orders")
                .contains("DROP FOREIGN KEY order_worker")
                .contains("ADD CONSTRAINT order_worker")
                .contains("FOREIGN KEY (order_worker) REFERENCES workers (worker_id)")
                .contains("ON DELETE RESTRICT")
                .contains("ON UPDATE NO ACTION")
                .doesNotContain("ON DELETE CASCADE");

        assertThat(preparation)
                .contains("shadow_route_generation")
                .contains("shadow_route_prepared_at")
                .contains("contractor-payments.shadow-preparation-started-at");
        assertThat(generationCollation)
                .contains("ALTER TABLE contractor_payment_allocations")
                .contains("MODIFY COLUMN source_generation_snapshot VARCHAR(36)")
                .contains("ALTER TABLE payment_links")
                .contains("MODIFY COLUMN shadow_route_generation VARCHAR(36)")
                .contains("ALTER TABLE archive_payment_links")
                .contains("ALTER TABLE common_invoices")
                .contains("ALTER TABLE archive_common_invoices")
                .contains("CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }

    @Test
    void contractorPiiIsScrubbedAndCannotReturnToLegacyRouteColumns() throws IOException {
        String migration = migration(
                "/db/migration/V1_10_224__contractor_route_pii_single_source.sql"
        );
        String archiveRouteMigration = migration(
                "/db/migration/V1_10_219__contractor_route_delivery_and_transfer.sql"
        );

        assertThat(archiveRouteMigration)
                .contains("ALTER TABLE archive_common_invoices")
                .contains("ADD COLUMN payment_route_instruction_text VARCHAR(1000) NULL");

        assertThat(migration)
                .contains("UPDATE payment_links")
                .contains("UPDATE archive_payment_links")
                .contains("UPDATE common_invoices")
                .contains("UPDATE archive_common_invoices")
                .contains("manual_source = 'CONTRACTOR_PAYMENT_PROFILE'")
                .contains("payment_route_manual_source = 'CONTRACTOR_PAYMENT_PROFILE'")
                .contains("manual_phone = NULL")
                .contains("manual_recipient_name = NULL")
                .contains("manual_comment = NULL")
                .contains("payment_route_manual_phone = NULL")
                .contains("payment_route_manual_recipient = NULL")
                .contains("payment_route_manual_comment = NULL")
                .contains("payment_route_instruction_text = NULL")
                .contains("COALESCE(TRIM(manual_comment), '') = ''")
                .contains("COALESCE(TRIM(payment_route_manual_comment), '') = ''")
                .contains("COALESCE(TRIM(payment_route_instruction_text), '') = ''")
                .contains("ck_payment_links_contractor_pii_blank")
                .contains("ck_archive_payment_links_contractor_pii_blank")
                .contains("ck_common_invoices_contractor_pii_blank")
                .contains("ck_archive_common_invoices_contractor_pii_blank");
    }

    @Test
    void completionCutoverMigrationCreatesAnEmptyImmutableSingletonLatch() throws IOException {
        String migration = migration(
                "/db/migration/V1_10_227__completion_based_contractor_rewards.sql"
        );

        assertThat(migration)
                .contains("CREATE TABLE contractor_completion_cutover_state")
                .contains("attribution_start_date DATE NOT NULL")
                .contains("locked_at DATETIME(6) NOT NULL")
                .contains("CONSTRAINT chk_contractor_completion_cutover_singleton CHECK (id = 1)")
                .doesNotContain("INSERT INTO contractor_completion_cutover_state")
                .doesNotContain("INSERT IGNORE INTO contractor_completion_cutover_state");
    }

    @Test
    void rolloutStateMigrationPreservesAnyPreviouslyStartedAccountingAuthority() throws IOException {
        String migration = migration(
                "/db/migration/V1_10_229__contractor_payment_rollout_state.sql"
        );

        assertThat(migration)
                .contains("CREATE TABLE contractor_payment_rollout_state")
                .contains("accounting_authority VARCHAR(16) NOT NULL")
                .contains("routing_requested BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("attribution_start_date DATE NULL")
                .contains("row_version BIGINT NOT NULL DEFAULT 0")
                .contains("CONSTRAINT ck_contractor_payment_rollout_state_id CHECK (id = 1)")
                .contains("CHECK (accounting_authority IN ('LEGACY', 'COMPLETION'))")
                .contains("cutover.attribution_start_date IS NOT NULL")
                .contains("phase.phase = 'LIVE'")
                .contains("'contractor-payments.reward-attribution-live-enabled'")
                .contains("'contractor-payments.live-routing-enabled'")
                .contains("'contractor-payments.completion-attribution-start-date'")
                .contains("THEN 'COMPLETION'")
                .contains("THEN TRUE")
                .contains("UPDATE contractor_payment_accounting_phase phase")
                .contains("SET phase.phase = 'LIVE'")
                .contains("WHERE rollout.accounting_authority = 'COMPLETION'")
                .contains("AND phase.phase = 'SHADOW'")
                .doesNotContain("UPDATE contractor_payment_rollout_state")
                .doesNotContain("DELETE FROM contractor_payment_rollout_state");
    }

    private String migration(String resource) throws IOException {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError(resource + " migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long flywayChecksum(String sql) {
        CRC32 checksum = new CRC32();
        sql.lines().forEach(line -> checksum.update(line.getBytes(StandardCharsets.UTF_8)));
        return checksum.getValue();
    }
}
