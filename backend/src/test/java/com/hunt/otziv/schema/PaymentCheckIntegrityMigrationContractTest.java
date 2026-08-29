package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentCheckIntegrityMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_268__payment_check_integrity_and_financial_repair.sql";

    @Test
    void repairsOnlyCashBackedMissingChecksAndAuditsEveryCorrection() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("v268_missing_checks")
                .contains("payment_link.status in ('confirmed', 'amount_mismatch')")
                .contains("invoice_order.paid = 1")
                .contains("'missing_payment_check_restored'")
                .contains("insert into payment_check")
                .contains("'payment_check_quarantined'")
                .contains("set payment.check_active = 0")
                .doesNotContain("delete from payment_check");
    }

    @Test
    void activeCheckIsUniqueAndRequiresCurrentPaidOrderStatus() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("check_active_order_guard")
                .contains("uk_payment_check_active_order")
                .contains("ck_payment_check_active_paid_guard")
                .contains("fk_payment_check_paid_status_guard")
                .contains("fk_payment_check_active_paid_status");
    }

    @Test
    void cashMismatchAndReserveTailFailClosedWithoutChangingCashOrRoutes() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("'payment_amount_reconciliation_required'")
                .contains("set payment_link.status = 'amount_mismatch'")
                .contains("'contractor_reserve_overrun_frozen'")
                .contains("автоматическое освобождение запрещено")
                .doesNotContain("delete from contractor_payment_allocations")
                .doesNotContain("set allocation.status = 'released'");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
