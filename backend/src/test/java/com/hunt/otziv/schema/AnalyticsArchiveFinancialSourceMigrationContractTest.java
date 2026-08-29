package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AnalyticsArchiveFinancialSourceMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_269__archive_aware_analytics_financial_sources.sql";

    @Test
    void financialViewsKeepArchivedHistoryWithoutDoubleCountingRestoredRows() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("create or replace view analytics_payment_source")
                .contains("from archive_payment_check archived")
                .contains("from payment_check live_payment")
                .contains("live_payment.check_id = archived.check_id")
                .contains("create or replace view analytics_salary_source")
                .contains("from archive_zp archived")
                .contains("from zp live_reward")
                .contains("live_reward.zp_id = archived.zp_id")
                .contains("contractor_reward_ledger ledger")
                .contains("ledger.source_zp_id = archived.zp_id");
    }

    @Test
    void migrationSchedulesRepairOfMonthsRebuiltFromLiveOnlySources() throws Exception {
        assertThat(migration())
                .contains("financial-integrity.v268-analytics-rebuild-pending")
                .contains("setting_value = values(setting_value)");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
