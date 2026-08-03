package com.hunt.otziv.security.credentials;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CredentialEncryptionBackfillMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("credential_backfill_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
        for (String table : new String[]{
                "telephones", "bots", "bad_review_tasks", "review_recovery_tasks", "archive_bad_review_tasks"
        }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE telephones (
                    telephone_id BIGINT PRIMARY KEY,
                    telephone_google_password VARCHAR(1024),
                    telephone_avito_password VARCHAR(1024),
                    telephone_mail_password VARCHAR(1024)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE bots (
                    bot_id BIGINT PRIMARY KEY,
                    bot_password VARCHAR(1024)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE bad_review_tasks (
                    bad_review_task_id BIGINT PRIMARY KEY,
                    bad_review_task_bot_password_snapshot VARCHAR(1024)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE review_recovery_tasks (
                    review_recovery_task_id BIGINT PRIMARY KEY,
                    review_recovery_task_bot_password_snapshot VARCHAR(1024)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_bad_review_tasks (
                    bad_review_task_id BIGINT PRIMARY KEY,
                    bad_review_task_bot_password_snapshot VARCHAR(1024)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void encryptsPlaintextAndOldKeyRowsExactlyOnceIncludingUnderscoreKeyIds() {
        CredentialCipher oldCipher = new CredentialCipher(properties(
                "prodXkey",
                key((byte) 1),
                null
        ));
        CredentialEncryptionProperties activeProperties = properties(
                "prod_key",
                key((byte) 2),
                "prodXkey=" + key((byte) 1)
        );
        activeProperties.setBackfillBatchSize(2);
        CredentialCipher activeCipher = new CredentialCipher(activeProperties);

        String alreadyActive = activeCipher.encrypt("already-active");
        jdbc.update("INSERT INTO bots (bot_id, bot_password) VALUES (?, ?)", 1L, "plain-secret");
        jdbc.update("INSERT INTO bots (bot_id, bot_password) VALUES (?, ?)", 2L, alreadyActive);
        jdbc.update("INSERT INTO bots (bot_id, bot_password) VALUES (?, ?)", 3L, oldCipher.encrypt("old-secret"));
        jdbc.update(
                "INSERT INTO telephones "
                        + "(telephone_id, telephone_google_password, telephone_avito_password, telephone_mail_password) "
                        + "VALUES (?, ?, ?, ?)",
                4L,
                "google-secret",
                "avito-secret",
                "mail-secret"
        );

        CredentialEncryptionBackfill backfill = new CredentialEncryptionBackfill(
                jdbc,
                activeCipher,
                activeProperties
        );
        backfill.run(null);

        String first = value("bots", "bot_id", 1L, "bot_password");
        String second = value("bots", "bot_id", 2L, "bot_password");
        String third = value("bots", "bot_id", 3L, "bot_password");
        assertThat(first).startsWith(activeCipher.activeEnvelopePrefix());
        assertThat(second).isEqualTo(alreadyActive);
        assertThat(third).startsWith(activeCipher.activeEnvelopePrefix());
        assertThat(activeCipher.decrypt(first)).isEqualTo("plain-secret");
        assertThat(activeCipher.decrypt(third)).isEqualTo("old-secret");
        assertThat(activeCipher.decrypt(value(
                "telephones", "telephone_id", 4L, "telephone_google_password"
        ))).isEqualTo("google-secret");
        assertThat(activeCipher.decrypt(value(
                "telephones", "telephone_id", 4L, "telephone_avito_password"
        ))).isEqualTo("avito-secret");
        assertThat(activeCipher.decrypt(value(
                "telephones", "telephone_id", 4L, "telephone_mail_password"
        ))).isEqualTo("mail-secret");

        backfill.run(null);
        assertThat(value("bots", "bot_id", 1L, "bot_password")).isEqualTo(first);
        assertThat(value("bots", "bot_id", 2L, "bot_password")).isEqualTo(second);
        assertThat(value("bots", "bot_id", 3L, "bot_password")).isEqualTo(third);
    }

    private String value(String table, String idColumn, long id, String valueColumn) {
        return jdbc.queryForObject(
                "SELECT " + valueColumn + " FROM " + table + " WHERE " + idColumn + " = ?",
                String.class,
                id
        );
    }

    private CredentialEncryptionProperties properties(String keyId, String activeKey, String previousKeys) {
        CredentialEncryptionProperties properties = new CredentialEncryptionProperties();
        properties.setRequired(true);
        properties.setActiveKeyId(keyId);
        properties.setActiveKeyBase64(activeKey);
        properties.setPreviousKeys(previousKeys);
        return properties;
    }

    private String key(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }
}
