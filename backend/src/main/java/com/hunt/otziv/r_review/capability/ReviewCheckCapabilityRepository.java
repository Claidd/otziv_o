package com.hunt.otziv.r_review.capability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReviewCheckCapabilityRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void recordLegacyUse(UUID orderDetailId, byte[] tokenHash) {
        jdbc.update("""
                INSERT INTO review_check_capabilities (
                    order_detail_id,
                    token_hash,
                    token_type,
                    scope_mask,
                    last_used_at,
                    issued_at,
                    updated_at
                ) VALUES (
                    UUID_TO_BIN(:orderDetailId),
                    :tokenHash,
                    'LEGACY_UUID',
                    :scopeMask,
                    CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6)
                )
                ON DUPLICATE KEY UPDATE
                    order_detail_id = IF(token_type = 'LEGACY_UUID', VALUES(order_detail_id), order_detail_id),
                    scope_mask = IF(token_type = 'LEGACY_UUID', VALUES(scope_mask), scope_mask),
                    updated_at = IF(
                        token_type = 'LEGACY_UUID'
                            AND (last_used_at IS NULL
                                OR last_used_at <= TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6))),
                        CURRENT_TIMESTAMP(6),
                        updated_at
                    ),
                    last_used_at = IF(
                        token_type = 'LEGACY_UUID'
                            AND (last_used_at IS NULL
                                OR last_used_at <= TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6))),
                        CURRENT_TIMESTAMP(6),
                        last_used_at
                    )
                """, new MapSqlParameterSource()
                .addValue("orderDetailId", orderDetailId.toString())
                .addValue("tokenHash", tokenHash)
                .addValue("scopeMask", ReviewCheckCapabilityScope.ALL_PUBLIC_MASK));
    }

    public Optional<CapabilityRow> findByTokenHashForUpdate(byte[] tokenHash) {
        return jdbc.query("""
                SELECT
                    review_check_capability_id,
                    BIN_TO_UUID(order_detail_id) AS order_detail_uuid,
                    token_hash,
                    token_type,
                    scope_mask,
                    issued_by_user_id,
                    expires_at,
                    revoked_at,
                    revoked_by_user_id,
                    revocation_reason,
                    last_used_at,
                    issued_at,
                    updated_at
                FROM review_check_capabilities
                WHERE token_hash = :tokenHash
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("tokenHash", tokenHash), this::row)
                .stream()
                .findFirst();
    }

    public Optional<CapabilityRow> findByIdForUpdate(Long capabilityId) {
        if (capabilityId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT
                    review_check_capability_id,
                    BIN_TO_UUID(order_detail_id) AS order_detail_uuid,
                    token_hash,
                    token_type,
                    scope_mask,
                    issued_by_user_id,
                    expires_at,
                    revoked_at,
                    revoked_by_user_id,
                    revocation_reason,
                    last_used_at,
                    issued_at,
                    updated_at
                FROM review_check_capabilities
                WHERE review_check_capability_id = :capabilityId
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("capabilityId", capabilityId), this::row)
                .stream()
                .findFirst();
    }

    public CapabilityRow insertOpaque(
            UUID orderDetailId,
            byte[] tokenHash,
            long scopeMask,
            Long issuedByUserId,
            int expiresInDays
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderDetailId", orderDetailId.toString())
                .addValue("tokenHash", tokenHash)
                .addValue("scopeMask", scopeMask)
                .addValue("issuedByUserId", issuedByUserId)
                .addValue("expiresInDays", expiresInDays);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO review_check_capabilities (
                    order_detail_id,
                    token_hash,
                    token_type,
                    scope_mask,
                    issued_by_user_id,
                    expires_at,
                    issued_at,
                    updated_at
                ) VALUES (
                    UUID_TO_BIN(:orderDetailId),
                    :tokenHash,
                    'OPAQUE',
                    :scopeMask,
                    :issuedByUserId,
                    TIMESTAMPADD(DAY, :expiresInDays, CURRENT_TIMESTAMP(6)),
                    CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6)
                )
                """, params, keyHolder, new String[]{"review_check_capability_id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Capability id was not generated");
        }
        return findByIdForUpdate(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Generated capability was not found"));
    }

    public boolean isActiveByDatabaseClock(long capabilityId) {
        Integer active = jdbc.queryForObject("""
                SELECT CASE
                    WHEN token_type = 'OPAQUE'
                      AND revoked_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP(6)
                    THEN 1
                    ELSE 0
                END
                FROM review_check_capabilities
                WHERE review_check_capability_id = :capabilityId
                """, new MapSqlParameterSource("capabilityId", capabilityId), Integer.class);
        return Integer.valueOf(1).equals(active);
    }

    public int touchIfActiveAndDue(long capabilityId) {
        return jdbc.update("""
                UPDATE review_check_capabilities
                SET last_used_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE review_check_capability_id = :capabilityId
                  AND token_type = 'OPAQUE'
                  AND revoked_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP(6)
                  AND (last_used_at IS NULL
                       OR last_used_at <= TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP(6)))
                """, new MapSqlParameterSource("capabilityId", capabilityId));
    }

    public int revoke(
            long capabilityId,
            UUID orderDetailId,
            Long revokedByUserId,
            String reason
    ) {
        return jdbc.update("""
                UPDATE review_check_capabilities
                SET revoked_at = CURRENT_TIMESTAMP(6),
                    revoked_by_user_id = :revokedByUserId,
                    revocation_reason = :reason,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE review_check_capability_id = :capabilityId
                  AND order_detail_id = UUID_TO_BIN(:orderDetailId)
                  AND token_type = 'OPAQUE'
                  AND revoked_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("capabilityId", capabilityId)
                .addValue("orderDetailId", orderDetailId.toString())
                .addValue("revokedByUserId", revokedByUserId)
                .addValue("reason", reason));
    }

    public List<CapabilityRow> findOpaqueByOrderDetailId(UUID orderDetailId) {
        return jdbc.query("""
                SELECT
                    review_check_capability_id,
                    BIN_TO_UUID(order_detail_id) AS order_detail_uuid,
                    token_hash,
                    token_type,
                    scope_mask,
                    issued_by_user_id,
                    expires_at,
                    revoked_at,
                    revoked_by_user_id,
                    revocation_reason,
                    last_used_at,
                    issued_at,
                    updated_at
                FROM review_check_capabilities
                WHERE order_detail_id = UUID_TO_BIN(:orderDetailId)
                  AND token_type = 'OPAQUE'
                ORDER BY review_check_capability_id DESC
                """, new MapSqlParameterSource("orderDetailId", orderDetailId.toString()), this::row);
    }

    private CapabilityRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new CapabilityRow(
                rs.getLong("review_check_capability_id"),
                UUID.fromString(rs.getString("order_detail_uuid")),
                rs.getBytes("token_hash"),
                rs.getString("token_type"),
                rs.getLong("scope_mask"),
                nullableLong(rs, "issued_by_user_id"),
                localDateTime(rs, "expires_at"),
                localDateTime(rs, "revoked_at"),
                nullableLong(rs, "revoked_by_user_id"),
                rs.getString("revocation_reason"),
                localDateTime(rs, "last_used_at"),
                localDateTime(rs, "issued_at"),
                localDateTime(rs, "updated_at")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record CapabilityRow(
            long id,
            UUID orderDetailId,
            byte[] tokenHash,
            String tokenType,
            long scopeMask,
            Long issuedByUserId,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            Long revokedByUserId,
            String revocationReason,
            LocalDateTime lastUsedAt,
            LocalDateTime issuedAt,
            LocalDateTime updatedAt
    ) {
    }
}
