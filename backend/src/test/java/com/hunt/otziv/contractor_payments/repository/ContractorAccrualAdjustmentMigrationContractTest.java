package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ContractorAccrualAdjustmentMigrationContractTest {

    @Test
    void adjustmentHistoryHasDurableIdentityAndCannotDuplicateAReversal() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V1_10_328__contractor_accrual_adjustments.sql"
        )) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE contractor_accrual_adjustments")
                .contains("profile_id BIGINT NOT NULL")
                .contains("signed_amount_kopecks BIGINT NOT NULL")
                .contains("effective_on DATE NOT NULL")
                .contains("reference_month DATE NOT NULL")
                .contains("reason VARCHAR(255) NOT NULL")
                .contains("created_by VARCHAR(160) NOT NULL")
                .contains("reversal_of_id BIGINT NULL")
                .contains("FOREIGN KEY (profile_id) REFERENCES contractor_payment_profiles (id)")
                .contains("FOREIGN KEY (reversal_of_id) REFERENCES contractor_accrual_adjustments (id)")
                .contains("UNIQUE (profile_id, idempotency_key)")
                .contains("UNIQUE (reversal_of_id)")
                .contains("(profile_id, effective_on)");
    }

    @Test
    void capacityReadUsesCurrentLockingAggregateAfterProfileMutex() throws Exception {
        Method aggregate = ContractorAccrualAdjustmentRepository.class.getMethod(
                "sumForCapacityUpdate", Long.class
        );

        Query query = aggregate.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("SUM(signed_amount_kopecks)")
                .contains("profile_id = :profileId")
                .contains("FOR UPDATE");
    }
}
