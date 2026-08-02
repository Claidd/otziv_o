package com.hunt.otziv.integration.outbox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MySQL 8 outbox persistence. Every ownership-changing statement is fenced by
 * the opaque processing token installed while the row is locked.
 */
@Repository
public class IntegrationOutboxRepository {

    private static final int CLAIM_CANDIDATE_SCAN_LIMIT = 100;

    static final String FIND_STALE_HEAD_CANDIDATES_SQL = """
            SELECT candidate.integration_outbox_id
            FROM integration_outbox candidate
            WHERE candidate.status = 'PROCESSING'
              AND candidate.event_type IN (:allowedEventTypes)
              AND candidate.processing_lease_until <= CURRENT_TIMESTAMP(6)
              AND candidate.attempt_count < candidate.max_attempts
              AND NOT EXISTS (
                    SELECT 1
                    FROM integration_outbox earlier
                    WHERE earlier.aggregate_type = candidate.aggregate_type
                      AND earlier.aggregate_id = candidate.aggregate_id
                      AND earlier.integration_outbox_id < candidate.integration_outbox_id
                      AND earlier.status IN ('PENDING', 'PROCESSING', 'DEAD')
            )
            ORDER BY candidate.processing_lease_until, candidate.integration_outbox_id
            LIMIT :candidateScanLimit
            """;

    static final String FIND_PENDING_HEAD_CANDIDATES_SQL = """
            SELECT candidate.integration_outbox_id
            FROM integration_outbox candidate
            WHERE candidate.status = 'PENDING'
              AND candidate.event_type IN (:allowedEventTypes)
              AND candidate.available_at <= CURRENT_TIMESTAMP(6)
              AND candidate.attempt_count < candidate.max_attempts
              AND NOT EXISTS (
                    SELECT 1
                    FROM integration_outbox earlier
                    WHERE earlier.aggregate_type = candidate.aggregate_type
                      AND earlier.aggregate_id = candidate.aggregate_id
                      AND earlier.integration_outbox_id < candidate.integration_outbox_id
                      AND earlier.status IN ('PENDING', 'PROCESSING', 'DEAD')
            )
            ORDER BY candidate.available_at, candidate.integration_outbox_id
            LIMIT :candidateScanLimit
            """;

    static final String LOCK_CANDIDATE_SQL = """
            SELECT integration_outbox_id
            FROM integration_outbox
            WHERE integration_outbox_id = :outboxId
              AND status = :expectedStatus
              AND event_type IN (:allowedEventTypes)
              AND attempt_count < max_attempts
              AND (
                    (
                        :expectedStatus = 'PENDING'
                        AND available_at <= CURRENT_TIMESTAMP(6)
                    )
                    OR (
                        :expectedStatus = 'PROCESSING'
                        AND processing_lease_until <= CURRENT_TIMESTAMP(6)
                    )
              )
            FOR UPDATE SKIP LOCKED
            """;

    static final String HAS_EARLIER_NON_SUCCEEDED_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM integration_outbox candidate
                JOIN integration_outbox earlier
                  ON earlier.aggregate_type = candidate.aggregate_type
                 AND earlier.aggregate_id = candidate.aggregate_id
                 AND earlier.integration_outbox_id < candidate.integration_outbox_id
                 AND earlier.status IN ('PENDING', 'PROCESSING', 'DEAD')
                WHERE candidate.integration_outbox_id = :outboxId
            )
            """;

    static final String CLAIM_SQL = """
            UPDATE integration_outbox
            SET status = 'PROCESSING',
                attempt_count = attempt_count + 1,
                processing_token = :processingToken,
                processing_owner = :processingOwner,
                processing_started_at = CURRENT_TIMESTAMP(6),
                processing_lease_until = TIMESTAMPADD(
                    MICROSECOND,
                    :leaseMicros,
                    CURRENT_TIMESTAMP(6)
                ),
                last_error = :claimNote,
                completed_at = NULL
            WHERE integration_outbox_id = :outboxId
              AND status = :expectedStatus
              AND event_type IN (:allowedEventTypes)
              AND attempt_count < max_attempts
              AND (
                    (
                        :expectedStatus = 'PENDING'
                        AND available_at <= CURRENT_TIMESTAMP(6)
                    )
                    OR (
                        :expectedStatus = 'PROCESSING'
                        AND processing_lease_until <= CURRENT_TIMESTAMP(6)
                    )
              )
            """;

    static final String SUCCEEDED_SQL = """
            UPDATE integration_outbox
            SET status = 'SUCCEEDED',
                processing_token = NULL,
                processing_owner = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error = NULL,
                completed_at = CURRENT_TIMESTAMP(6)
            WHERE integration_outbox_id = :outboxId
              AND status = 'PROCESSING'
              AND processing_token = :processingToken
            """;

    static final String RETRY_SQL = """
            UPDATE integration_outbox
            SET status = 'PENDING',
                available_at = TIMESTAMPADD(
                    MICROSECOND,
                    :delayMicros,
                    CURRENT_TIMESTAMP(6)
                ),
                processing_token = NULL,
                processing_owner = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error = :lastError,
                completed_at = NULL
            WHERE integration_outbox_id = :outboxId
              AND status = 'PROCESSING'
              AND processing_token = :processingToken
              AND attempt_count < max_attempts
            """;

    static final String DEAD_SQL = """
            UPDATE integration_outbox
            SET status = 'DEAD',
                processing_token = NULL,
                processing_owner = NULL,
                processing_started_at = NULL,
                processing_lease_until = NULL,
                last_error = :lastError,
                completed_at = CURRENT_TIMESTAMP(6)
            WHERE integration_outbox_id = :outboxId
              AND status = 'PROCESSING'
              AND processing_token = :processingToken
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public IntegrationOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EnqueueResult enqueue(
            String eventId,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            String eventType,
            byte[] deduplicationKeyHash,
            String payloadJson,
            int maxAttempts
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", eventId, Types.CHAR)
                .addValue("aggregateType", aggregateType, Types.VARCHAR)
                .addValue("aggregateId", aggregateId, Types.VARCHAR)
                .addValue("aggregateVersion", aggregateVersion, Types.BIGINT)
                .addValue("eventType", eventType, Types.VARCHAR)
                .addValue("deduplicationKeyHash", deduplicationKeyHash, Types.BINARY)
                .addValue("payload", payloadJson, Types.VARCHAR)
                .addValue("maxAttempts", maxAttempts, Types.INTEGER);

        jdbc.update("""
                INSERT INTO integration_outbox (
                    event_id,
                    aggregate_type,
                    aggregate_id,
                    aggregate_version,
                    event_type,
                    deduplication_key_hash,
                    payload,
                    max_attempts
                ) VALUES (
                    :eventId,
                    :aggregateType,
                    :aggregateId,
                    :aggregateVersion,
                    :eventType,
                    :deduplicationKeyHash,
                    CAST(:payload AS JSON),
                    :maxAttempts
                )
                ON DUPLICATE KEY UPDATE
                    integration_outbox_id = integration_outbox_id
                """, parameters);

        List<StoredEnvelope> stored = jdbc.query("""
                SELECT
                    integration_outbox_id,
                    event_id,
                    aggregate_type,
                    aggregate_id,
                    aggregate_version,
                    event_type,
                    payload,
                    max_attempts
                FROM integration_outbox
                WHERE deduplication_key_hash = :deduplicationKeyHash
                """, parameters, this::mapStoredEnvelope);
        if (stored.size() != 1) {
            throw new IllegalStateException("Outbox deduplication identity was not persisted");
        }
        StoredEnvelope identity = stored.getFirst();
        return new EnqueueResult(
                identity.outboxId(),
                identity.eventId(),
                eventId.equals(identity.eventId()),
                identity.aggregateType(),
                identity.aggregateId(),
                identity.aggregateVersion(),
                identity.eventType(),
                identity.payloadJson(),
                identity.maxAttempts()
        );
    }

    public Optional<Claim> claimNext(
            String processingToken,
            String processingOwner,
            long leaseMicros,
            Collection<String> allowedEventTypes
    ) {
        if (allowedEventTypes == null || allowedEventTypes.isEmpty()) {
            return Optional.empty();
        }
        Optional<Claim> stale = claimFirstAvailableHead(
                FIND_STALE_HEAD_CANDIDATES_SQL,
                "PROCESSING",
                processingToken,
                processingOwner,
                leaseMicros,
                "STALE_PROCESSING_LEASE_RECLAIMED",
                allowedEventTypes
        );
        if (stale.isPresent()) {
            return stale;
        }

        return claimFirstAvailableHead(
                FIND_PENDING_HEAD_CANDIDATES_SQL,
                "PENDING",
                processingToken,
                processingOwner,
                leaseMicros,
                null,
                allowedEventTypes
        );
    }

    public boolean markSucceeded(long outboxId, String processingToken) {
        return jdbc.update(SUCCEEDED_SQL, fence(outboxId, processingToken)) == 1;
    }

    public boolean markRetry(
            long outboxId,
            String processingToken,
            long delayMicros,
            String lastError
    ) {
        MapSqlParameterSource parameters = fence(outboxId, processingToken)
                .addValue("delayMicros", delayMicros)
                .addValue("lastError", lastError, Types.VARCHAR);
        return jdbc.update(RETRY_SQL, parameters) == 1;
    }

    public boolean markDead(long outboxId, String processingToken, String lastError) {
        MapSqlParameterSource parameters = fence(outboxId, processingToken)
                .addValue("lastError", lastError, Types.VARCHAR);
        return jdbc.update(DEAD_SQL, parameters) == 1;
    }

    public int markExpiredExhaustedDead(
            int rowLimit,
            Collection<String> allowedEventTypes,
            String lastError
    ) {
        if (allowedEventTypes == null || allowedEventTypes.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource selectionParameters = new MapSqlParameterSource()
                .addValue("rowLimit", rowLimit)
                .addValue("allowedEventTypes", allowedEventTypes);
        List<ExpiredClaim> expiredClaims = jdbc.query("""
                SELECT integration_outbox_id, processing_token, event_type
                FROM integration_outbox
                WHERE status = 'PROCESSING'
                  AND event_type IN (:allowedEventTypes)
                  AND processing_lease_until <= CURRENT_TIMESTAMP(6)
                  AND attempt_count >= max_attempts
                ORDER BY processing_lease_until, integration_outbox_id
                LIMIT :rowLimit
                FOR UPDATE SKIP LOCKED
                """, selectionParameters, (resultSet, rowNumber) -> new ExpiredClaim(
                resultSet.getLong("integration_outbox_id"),
                resultSet.getString("processing_token"),
                resultSet.getString("event_type")
        ));

        int changed = 0;
        for (ExpiredClaim expired : expiredClaims) {
            MapSqlParameterSource parameters = fence(
                    expired.outboxId(),
                    expired.processingToken()
            ).addValue("lastError", lastError, Types.VARCHAR)
                    .addValue("eventType", expired.eventType(), Types.VARCHAR);
            changed += jdbc.update("""
                    UPDATE integration_outbox
                    SET status = 'DEAD',
                        processing_token = NULL,
                        processing_owner = NULL,
                        processing_started_at = NULL,
                        processing_lease_until = NULL,
                        last_error = :lastError,
                        completed_at = CURRENT_TIMESTAMP(6)
                    WHERE integration_outbox_id = :outboxId
                      AND status = 'PROCESSING'
                      AND processing_token = :processingToken
                      AND event_type = :eventType
                      AND processing_lease_until <= CURRENT_TIMESTAMP(6)
                      AND attempt_count >= max_attempts
                    """, parameters);
        }
        return changed;
    }

    public StatusSnapshot statusSnapshot(int countCap) {
        LocalDateTime databaseTime = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP(6)",
                Map.of(),
                LocalDateTime.class
        );
        CountSample due = countCapped("""
                status = 'PENDING'
                AND available_at <= CURRENT_TIMESTAMP(6)
                """, countCap);
        CountSample processing = countCapped("status = 'PROCESSING'", countCap);
        CountSample stale = countCapped("""
                status = 'PROCESSING'
                AND processing_lease_until <= CURRENT_TIMESTAMP(6)
                """, countCap);
        CountSample dead = countCapped("status = 'DEAD'", countCap);
        Timestamp oldestDueTimestamp = jdbc.queryForObject("""
                SELECT MIN(available_at) AS oldest_due_at
                FROM integration_outbox
                WHERE status = 'PENDING'
                  AND available_at <= CURRENT_TIMESTAMP(6)
                """, Map.of(), Timestamp.class);
        LocalDateTime oldestDueAt = oldestDueTimestamp == null
                ? null
                : oldestDueTimestamp.toLocalDateTime();

        return new StatusSnapshot(
                databaseTime,
                due,
                processing,
                stale,
                dead,
                oldestDueAt
        );
    }

    private Optional<Claim> claimFirstAvailableHead(
            String candidateSql,
            String expectedStatus,
            String processingToken,
            String processingOwner,
            long leaseMicros,
            String claimNote,
            Collection<String> allowedEventTypes
    ) {
        MapSqlParameterSource scanParameters = new MapSqlParameterSource()
                .addValue("allowedEventTypes", allowedEventTypes)
                .addValue("candidateScanLimit", CLAIM_CANDIDATE_SCAN_LIMIT);
        List<Long> candidateIds = jdbc.queryForList(
                candidateSql,
                scanParameters,
                Long.class
        );
        for (Long outboxId : candidateIds) {
            if (outboxId == null || !lockCandidate(
                    outboxId,
                    expectedStatus,
                    allowedEventTypes
            )) {
                continue;
            }
            if (hasEarlierNonSucceeded(outboxId)) {
                continue;
            }
            Optional<Claim> claimed = claimLocked(
                    outboxId,
                    expectedStatus,
                    processingToken,
                    processingOwner,
                    leaseMicros,
                    claimNote,
                    allowedEventTypes
            );
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    private boolean lockCandidate(
            long outboxId,
            String expectedStatus,
            Collection<String> allowedEventTypes
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("expectedStatus", expectedStatus)
                .addValue("allowedEventTypes", allowedEventTypes);
        return !jdbc.queryForList(LOCK_CANDIDATE_SQL, parameters, Long.class).isEmpty();
    }

    private boolean hasEarlierNonSucceeded(long outboxId) {
        Boolean result = jdbc.queryForObject(
                HAS_EARLIER_NON_SUCCEEDED_SQL,
                Map.of("outboxId", outboxId),
                Boolean.class
        );
        return Boolean.TRUE.equals(result);
    }

    private Optional<Claim> claimLocked(
            long outboxId,
            String expectedStatus,
            String processingToken,
            String processingOwner,
            long leaseMicros,
            String claimNote,
            Collection<String> allowedEventTypes
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("expectedStatus", expectedStatus)
                .addValue("processingToken", processingToken, Types.CHAR)
                .addValue("processingOwner", processingOwner, Types.VARCHAR)
                .addValue("leaseMicros", leaseMicros)
                .addValue("claimNote", claimNote, Types.VARCHAR)
                .addValue("allowedEventTypes", allowedEventTypes);
        if (jdbc.update(CLAIM_SQL, parameters) != 1) {
            return Optional.empty();
        }

        List<Claim> claimed = jdbc.query("""
                SELECT
                    integration_outbox_id,
                    event_id,
                    aggregate_type,
                    aggregate_id,
                    aggregate_version,
                    event_type,
                    payload,
                    attempt_count,
                    max_attempts,
                    processing_token,
                    processing_lease_until
                FROM integration_outbox
                WHERE integration_outbox_id = :outboxId
                  AND status = 'PROCESSING'
                  AND processing_token = :processingToken
                """, parameters, this::mapClaim);
        return claimed.stream().findFirst();
    }

    private Claim mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
        long aggregateVersionValue = resultSet.getLong("aggregate_version");
        Long aggregateVersion = resultSet.wasNull() ? null : aggregateVersionValue;
        return new Claim(
                resultSet.getLong("integration_outbox_id"),
                resultSet.getString("event_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                aggregateVersion,
                resultSet.getString("event_type"),
                resultSet.getString("payload"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("processing_token"),
                resultSet.getTimestamp("processing_lease_until").toLocalDateTime()
        );
    }

    private StoredEnvelope mapStoredEnvelope(ResultSet resultSet, int rowNumber)
            throws SQLException {
        long aggregateVersionValue = resultSet.getLong("aggregate_version");
        Long aggregateVersion = resultSet.wasNull() ? null : aggregateVersionValue;
        return new StoredEnvelope(
                resultSet.getLong("integration_outbox_id"),
                resultSet.getString("event_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                aggregateVersion,
                resultSet.getString("event_type"),
                resultSet.getString("payload"),
                resultSet.getInt("max_attempts")
        );
    }

    private CountSample countCapped(String predicate, int countCap) {
        long sampled = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT integration_outbox_id
                    FROM integration_outbox
                    WHERE %s
                    LIMIT :sampleLimit
                ) sampled_outbox
                """.formatted(predicate), Map.of("sampleLimit", countCap + 1), Long.class);
        return new CountSample(Math.min(sampled, countCap), sampled > countCap);
    }

    private MapSqlParameterSource fence(long outboxId, String processingToken) {
        return new MapSqlParameterSource()
                .addValue("outboxId", outboxId)
                .addValue("processingToken", processingToken, Types.CHAR);
    }

    public record EnqueueResult(
            long outboxId,
            String eventId,
            boolean created,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            String eventType,
            String payloadJson,
            int maxAttempts
    ) {
    }

    public record Claim(
            long outboxId,
            String eventId,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            String eventType,
            String payloadJson,
            int attemptCount,
            int maxAttempts,
            String processingToken,
            LocalDateTime leaseUntil
    ) {
    }

    public record CountSample(long value, boolean capped) {
    }

    public record StatusSnapshot(
            LocalDateTime databaseTime,
            CountSample due,
            CountSample processing,
            CountSample staleProcessing,
            CountSample dead,
            LocalDateTime oldestDueAt
    ) {
    }

    private record StoredEnvelope(
            long outboxId,
            String eventId,
            String aggregateType,
            String aggregateId,
            Long aggregateVersion,
            String eventType,
            String payloadJson,
            int maxAttempts
    ) {
    }

    private record ExpiredClaim(long outboxId, String processingToken, String eventType) {
    }
}
