package com.hunt.otziv.whatsapp.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rows of whatsapp_invite_link_checks: one per company whose stored invite looked or is stale. */
@Component
@RequiredArgsConstructor
class WhatsAppInviteLinkCheckStore {

    static final String SUSPECTED = "SUSPECTED";
    static final String STALE = "STALE";

    record Row(Long companyId, String groupId, String storedInviteCode, String currentInviteLink, String status,
               LocalDateTime firstSeenAt, String firstSnapshotAt, LocalDateTime confirmedAt) {
    }

    private final JdbcTemplate jdbc;

    Map<Long, Row> all() {
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.query("SELECT company_id, group_id, stored_invite_code, current_invite_link, status, first_seen_at,"
                        + " first_snapshot_at, confirmed_at FROM whatsapp_invite_link_checks",
                (rs, index) -> new Row(rs.getLong("company_id"), rs.getString("group_id"), rs.getString("stored_invite_code"),
                        rs.getString("current_invite_link"), rs.getString("status"), time(rs.getTimestamp("first_seen_at")),
                        rs.getString("first_snapshot_at"), time(rs.getTimestamp("confirmed_at"))))
                .forEach(row -> rows.put(row.companyId(), row));
        return rows;
    }

    List<Row> stale() {
        return all().values().stream().filter(row -> STALE.equals(row.status())).toList();
    }

    void suspect(Long companyId, String groupId, String storedInviteCode, String currentInviteLink,
                 String snapshotAt, LocalDateTime now) {
        jdbc.update("INSERT INTO whatsapp_invite_link_checks (company_id, group_id, stored_invite_code, current_invite_link,"
                        + " status, first_seen_at, first_snapshot_at, confirmed_at, checked_at) VALUES (?,?,?,?,?,?,?,NULL,?)"
                        + " ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), stored_invite_code=VALUES(stored_invite_code),"
                        + " current_invite_link=VALUES(current_invite_link), status=VALUES(status), first_seen_at=VALUES(first_seen_at),"
                        + " first_snapshot_at=VALUES(first_snapshot_at), confirmed_at=NULL, checked_at=VALUES(checked_at)",
                companyId, groupId, storedInviteCode, currentInviteLink, SUSPECTED, Timestamp.valueOf(now), snapshotAt,
                Timestamp.valueOf(now));
    }

    void confirm(Long companyId, String groupId, String storedInviteCode, String currentInviteLink, LocalDateTime now) {
        jdbc.update("INSERT INTO whatsapp_invite_link_checks (company_id, group_id, stored_invite_code, current_invite_link,"
                        + " status, first_seen_at, first_snapshot_at, confirmed_at, checked_at) VALUES (?,?,?,?,?,?,NULL,?,?)"
                        + " ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), stored_invite_code=VALUES(stored_invite_code),"
                        + " current_invite_link=COALESCE(VALUES(current_invite_link), current_invite_link), status=VALUES(status),"
                        + " confirmed_at=COALESCE(confirmed_at, VALUES(confirmed_at)), checked_at=VALUES(checked_at)",
                companyId, groupId, storedInviteCode, currentInviteLink, STALE, Timestamp.valueOf(now), Timestamp.valueOf(now),
                Timestamp.valueOf(now));
    }

    void observeAgain(Long companyId, String currentInviteLink, LocalDateTime now) {
        jdbc.update("UPDATE whatsapp_invite_link_checks SET current_invite_link=COALESCE(?, current_invite_link), checked_at=?"
                + " WHERE company_id=?", currentInviteLink, Timestamp.valueOf(now), companyId);
    }

    void delete(Long companyId) {
        jdbc.update("DELETE FROM whatsapp_invite_link_checks WHERE company_id=?", companyId);
    }

    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
