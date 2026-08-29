package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SupersededActualRecipientAllocationRepairMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_274__repair_superseded_actual_recipient_allocations.sql";

    @Test
    void offsetsOnlyConfirmedOriginalAllocationsSupersededByFinalActualRecipientEvidence()
            throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("join contractor_actual_payment_attributions attribution")
                .contains("attribution.original_allocation_id = allocation.id")
                .contains("attribution.source_kind = 'payment_link'")
                .contains("actual_allocation.source_type = 'actual_payment'")
                .contains("allocation.confirmed_kopecks > allocation.returned_kopecks")
                .contains("attribution.actual_recipient_type = 'owner'")
                .contains("insert ignore into contractor_payment_allocation_events")
                .contains("'returned'")
                .contains("'migration:v274:actual_recipient_superseded'")
                .contains("allocation.returned_kopecks = allocation.confirmed_kopecks")
                .contains("allocation.row_version = allocation.row_version + 1")
                .doesNotContain("delete from contractor_payment_allocations")
                .doesNotContain("delete from contractor_payment_allocation_events");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
