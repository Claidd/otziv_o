package com.hunt.otziv.schema;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsCanonicalSalarySourceMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_266__analytics_canonical_salary_source.sql";

    @Test
    void canonicalViewExcludesCancelledLegacyRowsAndUsesFinalLedgerAttribution() throws Exception {
        String sql;
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }

        assertThat(sql)
                .contains("create or replace view analytics_salary_source")
                .contains("z.zp_active = 1")
                .contains("not exists")
                .contains("contractor_reward_ledger ledger")
                .contains("ledger.active = 1")
                .contains("contractor_payment_profiles profile")
                .contains("profile.user_id")
                .contains("ledger.amount_kopecks")
                .contains("ledger.work_units");
    }
}
