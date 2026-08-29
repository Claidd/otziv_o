package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LegacyOrderPaymentReconciliationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_275__audited_legacy_order_payment_reconciliations.sql";

    @Test
    void preservesCashHistoryAndRecordsSignedOwnerConfirmedAdjustments() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("create table order_payment_reconciliations")
                .contains("v275_preflight_guard")
                .contains("'v275:order:24273:accepted-settlement'")
                .contains("'accepted_settlement'")
                .contains("45000")
                .contains("'v275:order:24378:client-overpayment'")
                .contains("-100000")
                .contains("'v275:order:25667:client-overpayment'")
                .contains("-5000")
                .contains("'order_payment_history_reconciled'")
                .contains("'owner_confirmation:2026-08-29'")
                .doesNotContain("delete from payment_links")
                .doesNotContain("delete from payment_check")
                .doesNotContain("update payment_check")
                .doesNotContain("update orders")
                .doesNotContain("update zp");
    }

    @Test
    void resolvesOnlyTheReviewedLinkStatesAndRetainsPrimaryAmounts() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("where id in (4117, 3918, 5758)")
                .contains("and status = 'amount_mismatch'")
                .contains("set status = 'confirmed'")
                .doesNotContain("set amount_kopecks")
                .doesNotContain("set confirmed_amount_kopecks")
                .doesNotContain("set reserved_amount_kopecks");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }
}
