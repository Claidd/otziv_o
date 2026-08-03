package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class AssociationForeignKeyRepairMigrationMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("association_fk_contract")
            .withUsername("root")
            .withPassword("root");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        initializeLegacySchema();
    }

    @Test
    void migrationPreservesEvidenceRemovesOrphansAndEnforcesReferences() {
        runMigration("V1_10_204__association_orphan_quarantine.sql");
        runMigration("V1_10_205__quarantine_and_remove_association_orphans.sql");
        runMigration("V1_10_206__restore_association_foreign_keys.sql");

        assertThat(count("users_roles")).isEqualTo(1);
        assertThat(count("workers_companies")).isEqualTo(1);
        assertThat(count("workers_users")).isEqualTo(1);
        assertThat(count("association_orphan_quarantine")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name IN ('users_roles', 'workers_companies', 'workers_users')
                """, Integer.class)).isEqualTo(6);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO users_roles VALUES (999, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO workers_companies VALUES (999, 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationReplacesRestrictiveLegacyForeignKeyWithCanonicalCascadeRules() {
        runMigration("V1_10_204__association_orphan_quarantine.sql");
        runMigration("V1_10_205__quarantine_and_remove_association_orphans.sql");
        jdbc.execute("""
                ALTER TABLE users_roles
                ADD CONSTRAINT legacy_users_roles_user_restrict
                FOREIGN KEY (user_id) REFERENCES users (id)
                ON DELETE RESTRICT ON UPDATE RESTRICT
                """);

        runMigration("V1_10_206__restore_association_foreign_keys.sql");

        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(kcu.constraint_name, ':', rc.delete_rule, ':', rc.update_rule)
                FROM information_schema.key_column_usage kcu
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_schema = kcu.constraint_schema
                 AND rc.table_name = kcu.table_name
                 AND rc.constraint_name = kcu.constraint_name
                WHERE kcu.table_schema = DATABASE()
                  AND kcu.table_name = 'users_roles'
                  AND kcu.column_name = 'user_id'
                  AND kcu.referenced_table_schema = DATABASE()
                  AND kcu.referenced_table_name = 'users'
                  AND kcu.referenced_column_name = 'id'
                """, String.class))
                .isEqualTo("fk_users_roles_user:CASCADE:CASCADE");

        jdbc.update("DELETE FROM users WHERE id = 1");
        assertThat(count("users_roles")).isZero();
        assertThat(count("workers_users")).isZero();
    }

    private void initializeLegacySchema() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "association_orphan_quarantine", "workers_users", "workers_companies",
                "users_roles", "companies", "workers", "roles", "users"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.execute("CREATE TABLE users (id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE roles (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workers (worker_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE companies (company_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE users_roles (user_id BIGINT NOT NULL, role_id INT NOT NULL, PRIMARY KEY (user_id, role_id), INDEX idx_users_roles_role (role_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workers_companies (company_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, PRIMARY KEY (company_id, worker_id), INDEX idx_workers_companies_worker (worker_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workers_users (user_id BIGINT NOT NULL, worker_id BIGINT NOT NULL, PRIMARY KEY (user_id, worker_id), INDEX idx_workers_users_worker (worker_id)) ENGINE=InnoDB");

        jdbc.update("INSERT INTO users VALUES (1)");
        jdbc.update("INSERT INTO roles VALUES (1)");
        jdbc.update("INSERT INTO workers VALUES (1)");
        jdbc.update("INSERT INTO companies VALUES (1)");
        jdbc.update("INSERT INTO users_roles VALUES (1, 1), (999, 1)");
        jdbc.update("INSERT INTO workers_companies VALUES (1, 1), (999, 1)");
        jdbc.update("INSERT INTO workers_users VALUES (1, 1), (999, 1)");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void runMigration(String name) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + name))
                .execute(dataSource);
    }
}
