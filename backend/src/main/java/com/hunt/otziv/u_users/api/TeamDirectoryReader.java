package com.hunt.otziv.u_users.api;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned display projection. Callers must supply their authorized manager scope. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamDirectoryReader {
    public enum Role {
        MANAGER("managers", "manager_id"), MARKETOLOG("marketologs", "marketolog_id"),
        WORKER("workers", "worker_id"), OPERATOR("operators", "operator_id");
        private final String table;
        private final String id;
        Role(String table, String id) { this.table = table; this.id = id; }
    }

    public record Member(Long id, Long userId, String fio, String login, Long imageId, boolean acceptsCompanyTransfers) {}

    private final NamedParameterJdbcTemplate jdbc;

    public List<Member> allActive(Role role) { return load(role, null); }

    /** Empty scope fails closed; null is never treated as an administrator here. */
    public List<Member> forManagers(Role role, Collection<Long> managerIds) {
        if (managerIds == null) return List.of();
        var ids = managerIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? List.of() : load(role, ids);
    }

    private List<Member> load(Role role, Collection<Long> managerIds) {
        Objects.requireNonNull(role);
        // Identifiers come exclusively from the enum, never from request text.
        String sql = "SELECT member." + role.id + " AS member_id, u.id AS user_id, u.fio, u.username, "
                + "COALESCE(u.image, 1) AS image_id, "
                + (role == Role.WORKER ? "member.accepts_company_transfers" : "FALSE") + " AS accepts_transfers "
                + "FROM " + role.table + " member JOIN users u ON u.id = member.user_id "
                + "WHERE u.active = TRUE AND EXISTS (SELECT 1 FROM users_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.user_id = u.id AND r.name = :role) "
                + (managerIds == null ? "" : "AND EXISTS (SELECT 1 FROM managers_users mu WHERE mu.user_id = u.id AND mu.manager_id IN (:managers)) ")
                + "ORDER BY member_id";
        var params = new MapSqlParameterSource("role", "ROLE_" + role.name()).addValue("managers", managerIds);
        return jdbc.query(sql, params, (row, index) -> new Member(row.getLong("member_id"), row.getLong("user_id"),
                row.getString("fio"), row.getString("username"), row.getLong("image_id"), row.getBoolean("accepts_transfers")));
    }
}
