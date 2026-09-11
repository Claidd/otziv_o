package com.hunt.otziv.u_users.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned cache namespace. This does not grant access or replace per-request authorization. */
@Service
public class CabinetCacheScope {
    private final JdbcTemplate jdbc;
    public CabinetCacheScope(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Committed role/membership changes invalidate old DTO keys, including on another replica. */
    @Transactional(readOnly = true)
    public String fingerprint() {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        // UNION avoids cartesian products; streaming avoids GROUP_CONCAT truncation.
        // No credentials, names or contact details are selected.
        jdbc.query("""
                SELECT kind, a, b, revision FROM (
                    SELECT 'user' AS kind, id AS a, active AS b,
                           CONCAT(row_version, ':', owner_control_view_mode) AS revision FROM users
                    UNION ALL SELECT 'role', user_id, role_id, 0 FROM users_roles
                    UNION ALL SELECT 'role-definition', id, 0, name FROM roles
                    UNION ALL SELECT 'manager', manager_id, user_id, 0 FROM managers
                    UNION ALL SELECT 'worker', worker_id, user_id, 0 FROM workers
                    UNION ALL SELECT 'operator', operator_id, user_id, 0 FROM operators
                    UNION ALL SELECT 'marketolog', marketolog_id, user_id, 0 FROM marketologs
                    UNION ALL SELECT 'owner-manager', user_id, manager_id, 0 FROM managers_users
                    UNION ALL SELECT 'manager-worker', user_id, worker_id, 0 FROM workers_users
                    UNION ALL SELECT 'user-operator', user_id, operator_id, 0 FROM operators_users
                    UNION ALL SELECT 'user-marketolog', user_id, marketolog_id, 0 FROM marketologs_users
                ) scope_rows ORDER BY kind, a, b, revision
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            String value = row.getString(1) + ':' + row.getString(2) + ':' + row.getString(3) + ':' + row.getString(4) + '\n';
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        });
        return HexFormat.of().formatHex(digest.digest());
    }
}
