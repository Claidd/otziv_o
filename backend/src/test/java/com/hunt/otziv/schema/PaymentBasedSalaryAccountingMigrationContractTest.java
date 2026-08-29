package com.hunt.otziv.schema;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentBasedSalaryAccountingMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_267__payment_based_salary_accounting.sql";

    @Test
    void migrationQuarantinesUnpaidSalaryWithAuditAndPreservesRows() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("'unpaid_salary_quarantined'")
                .contains("source_status.order_status_title, '') <> 'оплачено'")
                .contains("set reward.zp_active = 0")
                .contains("set ledger.active = 0")
                .contains("'unpaid_ledger_quarantined'")
                .contains("'unpaid_salary_marker_reset'")
                .doesNotContain("delete from zp")
                .doesNotContain("truncate table zp");
    }

    @Test
    void migrationAlignsEarlyRowsToPaymentDayAndMovesAuthorityToPayment() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("state.accounting_authority, 'payment'")
                .contains("set accounting_authority = 'payment'")
                .contains("check (accounting_authority in ('legacy', 'payment'))")
                .contains("'salary_date_aligned_to_payment'")
                .contains("reward.zp_date < source_order.order_pay_day")
                .contains("set reward.zp_date = correction.paid_date")
                .contains("ledger.occurred_on = correction.paid_date");
    }

    @Test
    void permanentGuardsRequirePaidStatusOnBothSidesOfTransition() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("create table salary_paid_order_status_guard")
                .contains("binary order_status_title = binary 'оплачено'")
                .contains("check (paid_guard = 1)")
                .contains("generated always as")
                .contains("ck_zp_active_order_paid_guard")
                .contains("fk_zp_active_order_paid_status")
                .contains("ck_ledger_active_order_paid_guard")
                .contains("fk_ledger_active_order_paid_status")
                .doesNotContain("create trigger trg_")
                .doesNotContain("delimiter $$");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
