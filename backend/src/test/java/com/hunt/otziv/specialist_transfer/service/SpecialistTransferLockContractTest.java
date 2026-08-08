package com.hunt.otziv.specialist_transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the cross-module order -> frozen-allocation lock protocol. */
class SpecialistTransferLockContractTest {

    @Test
    void applyLocksCanonicalOrderSnapshotBeforeFrozenGuardAndConstrainsBulkUpdates() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/hunt/otziv/specialist_transfer/service/SpecialistTransferService.java"),
                StandardCharsets.UTF_8
        ).replace("\r\n", "\n");

        int lock = source.indexOf("ORDER BY o.order_id\n                FOR UPDATE");
        int frozenGuard = source.indexOf("SELECT COUNT(DISTINCT o.order_id)");

        assertThat(lock).isGreaterThan(0);
        assertThat(frozenGuard).isGreaterThan(lock);
        assertThat(source)
                .contains("params.addValue(\n                \"lockedOrderIds\"")
                .contains("UPDATE orders o")
                .contains("WHERE o.order_id IN (:lockedOrderIds)")
                .contains("AND allocation.mode = 'LIVE'")
                .contains("'RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED'")
                .contains("int companyLinksRemoved = 0")
                .doesNotContain("DELETE source_link");
    }
}
