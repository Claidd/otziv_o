package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DeviceTokenMigrationMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("device_token_contract")
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
        jdbc.execute("DROP TABLE IF EXISTS device_tokens");
        jdbc.execute("""
                CREATE TABLE device_tokens (
                    token VARCHAR(255) PRIMARY KEY,
                    telephone_id BIGINT NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    active BOOLEAN NOT NULL DEFAULT TRUE
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void migrationHashesLegacyBearerAddsExpiryAndNarrowsStorage() {
        String legacyToken = UUID.randomUUID().toString();
        String alreadyHashedUpperCase =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        jdbc.update(
                "INSERT INTO device_tokens (token, telephone_id, active) VALUES (?, ?, 1)",
                legacyToken,
                10L
        );
        jdbc.update(
                "INSERT INTO device_tokens (token, telephone_id, active) VALUES (?, ?, 1)",
                alreadyHashedUpperCase,
                11L
        );

        runMigration();

        String expectedDigest = jdbc.queryForObject("SELECT LOWER(SHA2(?, 256))", String.class, legacyToken);
        assertThat(jdbc.queryForObject(
                "SELECT token FROM device_tokens WHERE telephone_id = 10",
                String.class
        )).isEqualTo(expectedDigest);
        assertThat(jdbc.queryForObject(
                "SELECT token FROM device_tokens WHERE telephone_id = 11",
                String.class
        )).isEqualTo(alreadyHashedUpperCase.toLowerCase());

        Integer remainingDays = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(DAY, CURRENT_TIMESTAMP(6), expires_at) "
                        + "FROM device_tokens WHERE telephone_id = 10",
                Integer.class
        );
        assertThat(remainingDays).isBetween(29, 30);

        assertThat(jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'device_tokens'
                  AND column_name = 'token'
                """, Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("""
                SELECT collation_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'device_tokens'
                  AND column_name = 'token'
                """, String.class)).isEqualTo("ascii_bin");
    }

    private void runMigration() {
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_10_210__secure_device_tokens.sql")
        ).execute(dataSource);
    }
}
