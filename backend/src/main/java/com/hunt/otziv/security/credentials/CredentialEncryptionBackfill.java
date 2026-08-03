package com.hunt.otziv.security.credentials;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CredentialEncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionBackfill.class);
    private static final List<CredentialColumn> CREDENTIAL_COLUMNS = List.of(
            new CredentialColumn("telephones", "telephone_id", "telephone_google_password"),
            new CredentialColumn("telephones", "telephone_id", "telephone_avito_password"),
            new CredentialColumn("telephones", "telephone_id", "telephone_mail_password"),
            new CredentialColumn("bots", "bot_id", "bot_password"),
            new CredentialColumn("bad_review_tasks", "bad_review_task_id", "bad_review_task_bot_password_snapshot"),
            new CredentialColumn(
                    "review_recovery_tasks",
                    "review_recovery_task_id",
                    "review_recovery_task_bot_password_snapshot"
            ),
            new CredentialColumn(
                    "archive_bad_review_tasks",
                    "bad_review_task_id",
                    "bad_review_task_bot_password_snapshot"
            )
    );

    private final JdbcTemplate jdbc;
    private final CredentialCipher credentialCipher;
    private final CredentialEncryptionProperties properties;

    public CredentialEncryptionBackfill(
            JdbcTemplate jdbc,
            CredentialCipher credentialCipher,
            CredentialEncryptionProperties properties
    ) {
        this.jdbc = jdbc;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isBackfillEnabled() || !credentialCipher.isEnabled()) {
            return;
        }
        int batchSize = properties.getBackfillBatchSize();
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalStateException("Credential encryption backfill batch size must be between 1 and 10000");
        }

        long totalUpdated = 0;
        for (CredentialColumn column : CREDENTIAL_COLUMNS) {
            long updated = backfill(column, batchSize);
            totalUpdated += updated;
            if (updated > 0) {
                log.info(
                        "Credential encryption backfill updated {} values in {}.{}",
                        updated,
                        column.table(),
                        column.column()
                );
            }
        }
        log.info("Credential encryption backfill completed; updated {} values", totalUpdated);
    }

    private long backfill(CredentialColumn column, int batchSize) {
        String selectSql = "SELECT " + column.idColumn() + " AS row_id, "
                + column.column() + " AS credential_value FROM " + column.table()
                + " WHERE " + column.idColumn() + " > ?"
                + " AND " + column.column() + " IS NOT NULL"
                + " AND " + column.column() + " <> ''"
                + " AND LEFT(" + column.column() + ", ?) <> ?"
                + " ORDER BY " + column.idColumn() + " LIMIT ?"; // sql-guard: allow -- static CREDENTIAL_COLUMNS only
        String updateSql = "UPDATE " + column.table() + " SET " + column.column() + " = ?"
                + " WHERE " + column.idColumn() + " = ? AND " + column.column() + " = ?";

        long lastId = 0;
        long updated = 0;
        while (true) {
            List<CredentialRow> rows = jdbc.query(
                    selectSql,
                    (rs, rowNum) -> new CredentialRow(rs.getLong("row_id"), rs.getString("credential_value")),
                    lastId,
                    credentialCipher.activeEnvelopePrefix().length(),
                    credentialCipher.activeEnvelopePrefix(),
                    batchSize
            );
            if (rows.isEmpty()) {
                return updated;
            }
            for (CredentialRow row : rows) {
                lastId = row.id();
                if (!credentialCipher.needsReencryption(row.storedValue())) {
                    continue;
                }
                String encrypted = credentialCipher.encrypt(row.storedValue());
                updated += jdbc.update(updateSql, encrypted, row.id(), row.storedValue());
            }
        }
    }

    private record CredentialColumn(String table, String idColumn, String column) {
    }

    private record CredentialRow(long id, String storedValue) {
    }
}
