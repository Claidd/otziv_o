package com.hunt.otziv.admin.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Import provenance deliberately outlives deletion of the account itself. */
@Repository
@RequiredArgsConstructor
public class BotImportOriginRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public void lockImports() {
        jdbc.queryForObject("SELECT lock_id FROM bot_import_lock WHERE lock_id = 1 FOR UPDATE",
                Map.of(), Integer.class);
    }

    public List<Origin> findOrigins(List<String> logins) {
        return jdbc.query("""
                SELECT normalized_login, bot_id, first_imported_at, source_file, source_row
                FROM bot_import_origins WHERE normalized_login IN (:logins)
                """, Map.of("logins", logins), (rs, row) -> new Origin(
                rs.getString("normalized_login"), rs.getObject("bot_id", Long.class),
                rs.getObject("first_imported_at", LocalDateTime.class), rs.getString("source_file"),
                rs.getObject("source_row", Integer.class)));
    }

    public List<Origin> findExistingAccounts(List<String> logins) {
        // Also covers accounts created manually after the migration. No credentials are read.
        return jdbc.query("""
                SELECT LOWER(TRIM(bot_login)) COLLATE utf8mb4_bin AS normalized_login, MIN(bot_id) AS bot_id
                FROM bots WHERE LOWER(TRIM(bot_login)) COLLATE utf8mb4_bin IN (:logins)
                GROUP BY LOWER(TRIM(bot_login)) COLLATE utf8mb4_bin
                """, Map.of("logins", logins), (rs, row) -> new Origin(
                rs.getString("normalized_login"), rs.getLong("bot_id"), null, null, null));
    }

    public void save(Origin origin) {
        jdbc.update("""
                INSERT INTO bot_import_origins
                    (normalized_login, bot_id, first_imported_at, source_file, source_row)
                VALUES (:login, :botId, :importedAt, :file, :row)
                """, new MapSqlParameterSource()
                .addValue("login", origin.login()).addValue("botId", origin.botId())
                .addValue("importedAt", origin.firstImportedAt()).addValue("file", origin.sourceFile())
                .addValue("row", origin.sourceRow()));
    }

    public record Origin(String login, Long botId, LocalDateTime firstImportedAt,
                         String sourceFile, Integer sourceRow) {}
}
