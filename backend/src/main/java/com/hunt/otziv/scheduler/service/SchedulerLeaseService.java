package com.hunt.otziv.scheduler.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchedulerLeaseService {

    private static final long MIN_LEASE_SECONDS = 5;
    private static final long MAX_LEASE_SECONDS = 3600;

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${otziv.instance-id:${HOSTNAME:local}}")
    private String instanceId = "local";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Lease> tryAcquire(String leaseName, Duration duration) {
        String normalizedName = requireLeaseName(leaseName);
        long seconds = boundedSeconds(duration);
        String ownerToken = UUID.randomUUID().toString();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("leaseName", normalizedName)
                .addValue("ownerToken", ownerToken)
                .addValue("ownerInstance", normalizedInstance())
                .addValue("leaseSeconds", seconds);

        jdbc.update("""
                INSERT INTO scheduler_leases (
                    lease_name, owner_token, owner_instance, fencing_token,
                    acquired_at, heartbeat_at, lease_until
                ) VALUES (
                    :leaseName, :ownerToken, :ownerInstance, 1,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),
                    TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP(6))
                )
                ON DUPLICATE KEY UPDATE
                    owner_token = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        VALUES(owner_token), owner_token
                    ),
                    owner_instance = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        VALUES(owner_instance), owner_instance
                    ),
                    fencing_token = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        fencing_token + 1, fencing_token
                    ),
                    acquired_at = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6), acquired_at
                    ),
                    heartbeat_at = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        CURRENT_TIMESTAMP(6), heartbeat_at
                    ),
                    lease_until = IF(
                        lease_until <= CURRENT_TIMESTAMP(6),
                        TIMESTAMPADD(SECOND, :leaseSeconds, CURRENT_TIMESTAMP(6)),
                        lease_until
                    )
                """, params);

        List<Lease> owned = jdbc.query("""
                SELECT lease_name, owner_token, fencing_token
                FROM scheduler_leases
                WHERE lease_name = :leaseName
                  AND owner_token = :ownerToken
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, params, (rs, rowNum) -> new Lease(
                rs.getString("lease_name"),
                rs.getString("owner_token"),
                rs.getLong("fencing_token")
        ));
        return owned.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Lease lease) {
        if (lease == null) {
            return;
        }
        jdbc.update("""
                UPDATE scheduler_leases
                SET heartbeat_at = CURRENT_TIMESTAMP(6),
                    lease_until = TIMESTAMPADD(MICROSECOND, 1, CURRENT_TIMESTAMP(6))
                WHERE lease_name = :leaseName
                  AND owner_token = :ownerToken
                  AND fencing_token = :fencingToken
                """, new MapSqlParameterSource()
                .addValue("leaseName", lease.leaseName())
                .addValue("ownerToken", lease.ownerToken())
                .addValue("fencingToken", lease.fencingToken()));
    }

    /** Locks the fence through the caller's write transaction, preventing an expired owner from publishing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForTransaction(Lease lease, Duration duration) {
        int renewed = jdbc.update("""
                UPDATE scheduler_leases
                SET heartbeat_at = CURRENT_TIMESTAMP(6),
                    lease_until = TIMESTAMPADD(SECOND, :seconds, CURRENT_TIMESTAMP(6))
                WHERE lease_name = :name AND owner_token = :owner AND fencing_token = :fence
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, new MapSqlParameterSource().addValue("seconds", boundedSeconds(duration))
                .addValue("name", lease.leaseName()).addValue("owner", lease.ownerToken())
                .addValue("fence", lease.fencingToken()));
        if (renewed != 1) throw new IllegalStateException("Projection refresh lease lost");
    }

    /** Caller holds the lease row until commit; the checkpoint commits with the derived rows. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long projectionCursor(Lease lease, java.time.LocalDate date) {
        return jdbc.query("""
                SELECT last_id FROM projection_refresh_checkpoints
                WHERE job_name=:name AND source_date=:date
                """, new MapSqlParameterSource("name", lease.leaseName()).addValue("date", date),
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElse(0L);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceProjectionCursor(Lease lease, java.time.LocalDate date, long lastId) {
        holdForTransaction(lease, Duration.ofMinutes(2));
        jdbc.update("""
                INSERT INTO projection_refresh_checkpoints(job_name, source_date, last_id)
                VALUES (:name,:date,:id)
                ON DUPLICATE KEY UPDATE source_date=VALUES(source_date), last_id=VALUES(last_id),
                    updated_at=CURRENT_TIMESTAMP(6)
                """, new MapSqlParameterSource("name", lease.leaseName()).addValue("date", date).addValue("id", lastId));
    }

    private String requireLeaseName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Invalid scheduler lease name");
        }
        return normalized;
    }

    private long boundedSeconds(Duration duration) {
        long seconds = duration == null ? 600 : duration.getSeconds();
        return Math.max(MIN_LEASE_SECONDS, Math.min(MAX_LEASE_SECONDS, seconds));
    }

    private String normalizedInstance() {
        String normalized = instanceId == null ? "local" : instanceId.trim();
        if (normalized.isEmpty()) {
            return "local";
        }
        return normalized.substring(0, Math.min(normalized.length(), 128));
    }

    public record Lease(String leaseName, String ownerToken, long fencingToken) {
    }
}
