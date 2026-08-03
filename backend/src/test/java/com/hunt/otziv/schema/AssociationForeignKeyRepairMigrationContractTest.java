package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AssociationForeignKeyRepairMigrationContractTest {

    @Test
    void invalidLinksAreQuarantinedBeforeTheyAreDeleted() throws Exception {
        String quarantine = migration("V1_10_204__association_orphan_quarantine.sql");
        String cleanup = migration("V1_10_205__quarantine_and_remove_association_orphans.sql");

        assertThat(quarantine)
                .contains("CREATE TABLE association_orphan_quarantine")
                .contains("PRIMARY KEY (association_table, left_id, right_id)");
        assertThat(cleanup)
                .contains("INSERT IGNORE INTO association_orphan_quarantine")
                .contains("FROM users_roles link")
                .contains("FROM workers_companies link")
                .contains("FROM workers_users link");
        assertThat(cleanup.indexOf("INSERT IGNORE INTO association_orphan_quarantine"))
                .isLessThan(cleanup.indexOf("DELETE link"));
    }

    @Test
    void foreignKeysAreAddedConditionallyByReferencedColumn() throws Exception {
        String sql = migration("V1_10_206__restore_association_foreign_keys.sql");

        assertThat(sql)
                .contains("information_schema.key_column_usage")
                .contains("information_schema.referential_constraints")
                .contains("referenced_table_schema = DATABASE()")
                .contains("rc.delete_rule = 'CASCADE'")
                .contains("rc.update_rule = 'CASCADE'")
                .contains("fk_users_roles_user")
                .contains("fk_users_roles_role")
                .contains("fk_workers_companies_company")
                .contains("fk_workers_companies_worker")
                .contains("fk_workers_users_user")
                .contains("fk_workers_users_worker")
                .contains("ON DELETE CASCADE ON UPDATE CASCADE");
        assertThat(sql).doesNotContain("foreign_key_checks");
    }

    private static String migration(String name) throws Exception {
        return new ClassPathResource("db/migration/" + name)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
