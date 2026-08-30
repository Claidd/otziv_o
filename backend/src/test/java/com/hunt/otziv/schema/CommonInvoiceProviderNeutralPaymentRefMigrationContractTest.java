package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CommonInvoiceProviderNeutralPaymentRefMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_278__common_invoice_provider_neutral_payment_refs.sql";

    @Test
    void liveAndArchiveRegistriesReceiveTheSameProviderSnapshotColumns() throws Exception {
        String sql = migration();

        String liveColumns = statement(sql, "alter table common_invoice_payment_refs");
        String archiveColumns = statement(sql, "alter table archive_common_invoice_payment_refs");

        assertProviderSnapshotColumns(liveColumns);
        assertProviderSnapshotColumns(archiveColumns);
        assertThat(archiveColumns).doesNotContain("unique key", " add index ");
    }

    @Test
    void legacyTbankEvidenceIsBackfilledInBothRegistries() throws Exception {
        String sql = migration();

        String liveBackfill = statement(sql, "update common_invoice_payment_refs payment_ref");
        String archiveBackfill = statement(sql, "update archive_common_invoice_payment_refs payment_ref");

        assertLegacyTbankBackfill(liveBackfill);
        assertLegacyTbankBackfill(archiveBackfill);
    }

    @Test
    void liveRegistryUsesProviderScopedIdentityAndReconciliationIndexes() throws Exception {
        String sql = migration();
        int firstAlterEnd = sql.indexOf(';', sql.indexOf("alter table common_invoice_payment_refs"));
        String liveIndexes = statement(
                sql,
                "alter table common_invoice_payment_refs",
                firstAlterEnd + 1
        );

        assertThat(liveIndexes)
                .contains("unique key uk_common_invoice_payment_ref_provider_order (provider, provider_order_id)")
                .contains("unique key uk_common_invoice_payment_ref_provider_payment (provider, provider_payment_id)")
                .contains("index idx_common_invoice_payment_ref_provider_status (provider, status, updated_at)")
                .contains("index idx_common_invoice_payment_ref_profile (payment_profile_id, payment_ref_id)");
        assertThat(sql)
                .doesNotContain("alter table archive_common_invoice_payment_refs\n    add unique key")
                .doesNotContain("drop index", "drop key", "delete from");
    }

    private static void assertProviderSnapshotColumns(String statement) {
        assertThat(statement)
                .contains("add column provider varchar(32) not null default 't_bank'")
                .contains("add column payment_profile_id bigint null")
                .contains("add column provider_order_id varchar(64) null")
                .contains("add column provider_payment_id varchar(64) null")
                .contains("add column provider_merchant_id varchar(64) null")
                .contains("add column provider_payment_mode varchar(32) null")
                .contains("add column provider_test_mode boolean null")
                .contains("add column provider_status varchar(32) null")
                .contains("add column provider_payment_url varchar(1024) null")
                .contains("add column provider_expires_at datetime(6) null");
    }

    private static void assertLegacyTbankBackfill(String statement) {
        assertThat(statement)
                .contains("left join payment_profiles profile")
                .contains("profile.provider = 't_bank'")
                .contains("payment_ref.tbank_terminal_key")
                .contains("profile.terminal_key")
                .contains("payment_ref.provider = 't_bank'")
                .contains("payment_ref.payment_profile_id = coalesce(payment_ref.payment_profile_id, profile.id)")
                .contains("payment_ref.provider_order_id = coalesce(")
                .contains("payment_ref.tbank_order_id")
                .contains("payment_ref.provider_payment_id = coalesce(")
                .contains("payment_ref.tbank_payment_id")
                .contains("payment_ref.provider_merchant_id = coalesce(")
                .contains("payment_ref.provider_status = coalesce(")
                .contains("payment_ref.status");
    }

    private static String migration() throws Exception {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .toLowerCase(Locale.ROOT);
    }

    private static String statement(String sql, String prefix) {
        return statement(sql, prefix, 0);
    }

    private static String statement(String sql, String prefix, int fromIndex) {
        int start = sql.indexOf(prefix, Math.max(0, fromIndex));
        assertThat(start).as("statement start for %s", prefix).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf(';', start);
        assertThat(end).as("statement terminator for %s", prefix).isGreaterThan(start);
        return sql.substring(start, end);
    }
}
