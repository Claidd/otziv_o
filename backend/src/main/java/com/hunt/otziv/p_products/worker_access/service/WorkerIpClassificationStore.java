package com.hunt.otziv.p_products.worker_access.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Worker-access-owned cache of observations, never a cached authorization decision. */
@Repository
public class WorkerIpClassificationStore {
    private final JdbcTemplate jdbc;
    public WorkerIpClassificationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Entry(WorkerIpIntelligenceClient.IpIntelligence intelligence, Instant observedAt, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public Optional<Entry> find(String key, Duration maximumAge) {
        return jdbc.query("""
                SELECT mobile, risky, organization, source, observed_epoch_ms, expires_epoch_ms
                FROM worker_ip_intelligence_cache WHERE cache_key = ?
                  AND expires_epoch_ms > UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))*1000
                  AND observed_epoch_ms > UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))*1000 - ?
                """, (row, index) -> new Entry(new WorkerIpIntelligenceClient.IpIntelligence(
                        true, row.getBoolean("mobile"), row.getBoolean("risky"), row.getString("organization"), row.getString("source")),
                        Instant.ofEpochMilli(row.getLong("observed_epoch_ms")), Instant.ofEpochMilli(row.getLong("expires_epoch_ms"))),
                key, maximumAge.toMillis()).stream().findFirst();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, timeout = 5)
    public void save(String key, Entry entry) {
        if (!entry.intelligence().known()) return;
        jdbc.update("""
                INSERT INTO worker_ip_intelligence_cache(cache_key,mobile,risky,organization,source,observed_epoch_ms,expires_epoch_ms)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  mobile=IF(VALUES(observed_epoch_ms)>=observed_epoch_ms,VALUES(mobile),mobile),
                  risky=IF(VALUES(observed_epoch_ms)>=observed_epoch_ms,VALUES(risky),risky),
                  organization=IF(VALUES(observed_epoch_ms)>=observed_epoch_ms,VALUES(organization),organization),
                  source=IF(VALUES(observed_epoch_ms)>=observed_epoch_ms,VALUES(source),source),
                  expires_epoch_ms=IF(VALUES(observed_epoch_ms)>=observed_epoch_ms,VALUES(expires_epoch_ms),expires_epoch_ms),
                  observed_epoch_ms=GREATEST(observed_epoch_ms,VALUES(observed_epoch_ms))
                """, key, entry.intelligence().mobile(), entry.intelligence().risky(),
                entry.intelligence().organization(), entry.intelligence().source(),
                entry.observedAt().toEpochMilli(), entry.expiresAt().toEpochMilli());
    }

    @Scheduled(fixedDelayString="${otziv.worker.cellular-access.cache-cleanup-ms:60000}",
            initialDelayString="${otziv.projection.initial-delay-ms:60000}",scheduler="interactiveProjectionScheduler")
    @Transactional(timeout = 5)
    public void cleanup() {
        jdbc.update("DELETE FROM worker_ip_intelligence_cache WHERE expires_epoch_ms <= UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))*1000 ORDER BY expires_epoch_ms LIMIT 1000");
        // Evict at most 1,000 excess entries per tick toward the 10,000-entry target.
        Long excess = jdbc.queryForObject("SELECT GREATEST(COUNT(*)-10000,0) FROM worker_ip_intelligence_cache", Long.class);
        if (excess != null && excess > 0) jdbc.update("DELETE FROM worker_ip_intelligence_cache ORDER BY expires_epoch_ms LIMIT ?", Math.min(1000, excess));
    }
}
