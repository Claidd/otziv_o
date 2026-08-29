package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class Order23275PrimaryPaymentEvidenceMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_271__reconcile_order_23275_primary_payment_evidence.sql";

    @Test
    void reconcilesEveryActiveFinancialSourceWithoutDeletingHistory() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("v271_preflight_guard")
                .contains("payment.check_sum = 2000.00")
                .contains("payment_link.amount_kopecks = 200000")
                .contains("payment_link.reserved_amount_kopecks = 200000")
                .contains("payment_link.confirmed_amount_kopecks = 200000")
                .contains("payment_link.status = 'confirmed'")
                .contains("'correction'")
                .contains("-75000")
                .contains("correction_of_id")
                .contains("'payment_primary_evidence_reconciled'")
                .contains("'payment_link_primary_evidence_reconciled'")
                .doesNotContain("delete from payment_check")
                .doesNotContain("delete from payment_links")
                .doesNotContain("delete from manual_payment_task_ledger_entries");
    }

    @Test
    void leavesSalaryCompanyAndContractorAccountingUntouchedAndRequestsAnalyticsRebuild()
            throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("financial-integrity.v268-analytics-rebuild-pending")
                .doesNotContain("update companies")
                .doesNotContain("update zp")
                .doesNotContain("update contractor_reward_ledger")
                .doesNotContain("update contractor_payment_allocations");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
