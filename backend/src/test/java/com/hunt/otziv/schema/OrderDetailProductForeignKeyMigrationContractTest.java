package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Deleting a product never deletes the order lines that sell it (V1_10_409). */
class OrderDetailProductForeignKeyMigrationContractTest {

    @Test
    void orphansStopTheMigrationBeforeAnyKeyChanges() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("SIGNAL SQLSTATE '45000'")
                .contains("LEFT JOIN products p ON p.product_id = od.order_detail_product");
        assertThat(sql.indexOf("CALL preflight_order_details_product_orphans()"))
                .isLessThan(sql.indexOf("ALTER TABLE order_details"));
    }

    @Test
    void cascadingKeysAreFoundByColumnAndReplacedByOneRestrictKey() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("information_schema.key_column_usage")
                .contains("information_schema.referential_constraints")
                .contains("kcu.column_name = 'order_detail_product'")
                .contains("rc.delete_rule <> 'RESTRICT'")
                .contains("rc.delete_rule = 'RESTRICT'")
                .contains("ADD CONSTRAINT fk_order_details_product FOREIGN KEY (order_detail_product) "
                        + "REFERENCES products (product_id) ON DELETE RESTRICT ON UPDATE CASCADE");
        assertThat(sql).doesNotContain("foreign_key_checks");
    }

    private static String migration() throws Exception {
        return new ClassPathResource("db/migration/V1_10_409__order_details_product_restrict.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
