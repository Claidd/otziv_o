package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CommonBillingCompanyReconcileMigrationContractTest {

    @Test
    void existingEnabledLinksStayReadyDuringRolloutAndNewJobsAreLeased() throws Exception {
        String sql = migration("V1_10_199__common_billing_company_reconcile_jobs.sql");

        assertThat(sql)
                .contains("reconcile_pending boolean not null default false")
                .contains("reconcile_attempts int not null default 0")
                .contains("reconcile_lease_token char(36)")
                .contains("reconcile_lease_until datetime(6)")
                .contains("idx_common_billing_company_reconcile")
                .doesNotContain("update common_billing_account_companies")
                .doesNotContain("reconcile_pending = true");
    }

    @Test
    void databaseStillSerializesOneEnabledAccountPerCompany() throws Exception {
        String sql = migration("V1_10_51__common_billing_enabled_company_guard.sql");

        assertThat(sql)
                .contains("generated always as (case when enabled then company_id else null end) stored")
                .contains("unique key uk_common_billing_enabled_company (enabled_company_id)");
    }

    private String migration(String name) throws Exception {
        try (var input = new ClassPathResource("db/migration/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
