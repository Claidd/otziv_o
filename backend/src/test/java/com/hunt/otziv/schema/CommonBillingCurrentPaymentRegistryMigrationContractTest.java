package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CommonBillingCurrentPaymentRegistryMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_200__common_billing_current_payment_registry.sql";

    @Test
    void migrationAddsALiveOnlySingleCurrentGuardAndLookupIndex() throws Exception {
        String sql = sql();

        assertThat(sql)
                .contains("generated always as (case when status = _utf8mb4''current'' then invoice_id else null end) virtual")
                .contains("uk_common_invoice_payment_refs_current_invoice (current_invoice_id)")
                .contains("idx_common_invoice_payment_refs_invoice_status_updated (invoice_id, status, updated_at, payment_ref_id)")
                .contains("information_schema.columns")
                .contains("information_schema.statistics")
                .contains("prepare cb_registry_stmt")
                .contains("@cb_registry_generation_introducer", "information_schema.character_sets")
                .contains("__v200_invalid_current_invoice_id_guard")
                .contains("__v200_invalid_current_invoice_unique_guard")
                .contains("__v200_invalid_current_invoice_lookup_guard")
                .contains("create temporary table cb_registry_current_bindings engine = innodb as")
                .contains("invoice.tbank_order_id")
                .contains("ref.tbank_payment_id as provider_value")
                .contains("ref.tbank_order_id as provider_value")
                .contains("octet_length(binding.payment_url) = char_length(binding.payment_url)")
                .contains("binding.payment_url regexp '^[a-za-z0-9:/?#@!$&''()*+,;=._~%-]+$'")
                .contains("regexp_replace(binding.payment_url")
                .doesNotContain("alter table archive_common_invoice_payment_refs")
                .doesNotContain("collate utf8mb4_0900_ai_ci")
                .doesNotContain("add column if not exists")
                .doesNotContain("drop index")
                .doesNotContain("drop key")
                .doesNotContain("insert ignore");
    }

    @Test
    void migrationQuarantinesAmbiguousIncompleteAndUnsafeLegacyBindings() throws Exception {
        String sql = sql();

        assertThat(sql)
                .contains("provider_identity_cross_invoice_collision")
                .contains("same_invoice_payment_ref_mismatch")
                .contains("multiple_current_payment_refs")
                .contains("matching_payment_ref_lifecycle_conflict")
                .contains("nonterminal_or_unknown_payment_ref_on_invoice")
                .contains("paid_payment_without_provider_identity")
                .contains("payment_init_without_payment_id")
                .contains("payment_init_in_progress")
                .contains("unsafe_or_incomplete_current_payment")
                .contains("current_ref_without_safe_live_projection")
                .contains("invoice.status = 'needs_attention'")
                .contains("invoice.payment_url = null")
                .contains("invoice.next_reminder_at = null")
                .contains("lower(binding.payment_url) like 'http://%'")
                .contains("lower(binding.payment_url) like 'https://%'")
                .contains("not regexp '%(0[0-9a-f]|1[0-9a-f]|7f)'")
                .contains("ref.status <> 'current'")
                .contains("ref.status <> 'applied'");
    }

    @Test
    void migrationKeepsProviderEvidenceAndPaidProjectionParity() throws Exception {
        String sql = sql();

        assertThat(sql)
                .contains("migration_paid_payment_registry")
                .contains("binding.invoice_status = 'paid'")
                .contains("invoice.tbank_order_id = null")
                .contains("invoice.tbank_payment_id = null")
                .contains("invoice.tbank_terminal_key = null")
                .contains("invoice.tbank_payment_amount_kopecks = null")
                .contains("invoice.tbank_payment_created_at = null")
                .doesNotContain("delete from common_invoice_payment_refs")
                .doesNotContain("drop index uk_common_invoice_payment_ref_payment");
    }

    private String sql() throws Exception {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }
}
