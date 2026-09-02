package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentCheckWorkerAttributionMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_281__repair_payment_check_worker_attribution.sql";

    @Test
    void repairsOnlyActiveChecksFromUnambiguousOrderWorkerIdentity() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("from payment_check payment")
                .contains("join orders base_order on base_order.order_id = payment.check_order")
                .contains("join workers actual_worker on actual_worker.worker_id = base_order.order_worker")
                .contains("from archive_payment_check payment")
                .contains("join archive_orders base_order on base_order.order_id = payment.check_order")
                .contains("where payment.check_active = 1")
                .contains("payment.check_worker = payment.check_manager")
                .contains("actual_worker.user_id is not null")
                .contains("not (payment.check_worker <=> actual_worker.user_id)")
                .contains("payment_check_worker_reattribution_required")
                .contains("check_worker=unchanged")
                .contains("actual_worker.user_id is null")
                .contains("worker_mismatch_requires_review")
                .contains("set payment.check_worker = repair.actual_worker_user_id")
                .doesNotContain("set payment.check_manager")
                .doesNotContain("set payment.check_sum")
                .doesNotContain("set payment.check_active");
    }

    @Test
    void correctionIsAuditedIdempotentlyAndSchedulesAnalyticsRebuild() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("'system:flyway-v281'")
                .contains("'payment_check_worker_reattributed'")
                .contains("where not exists")
                .contains("'financial-integrity.v268-analytics-rebuild-pending'")
                .contains("where exists (select 1 from v281_live_payment_check_worker_repairs)")
                .contains("or exists (select 1 from v281_archive_payment_check_worker_repairs)")
                .contains("on duplicate key update")
                .contains("drop temporary table v281_archive_payment_check_worker_repairs")
                .contains("drop temporary table v281_archive_payment_check_worker_unresolved")
                .contains("drop temporary table v281_live_payment_check_worker_repairs");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }
}
