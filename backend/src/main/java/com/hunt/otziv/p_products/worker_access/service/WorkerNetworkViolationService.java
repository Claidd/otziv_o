package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse.ViolationDetail;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerNetworkViolationService {
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final int DETAIL_LIMIT = 20;

    private final WorkerCellularAccessProperties properties;
    private final UserRepository userRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public boolean statisticsVisibleForRole(String role) {
        if (!properties.isViolationStatisticsEnabled()) {
            return false;
        }
        return !"MANAGER".equalsIgnoreCase(role) || properties.isViolationStatisticsVisibleToManagers();
    }

    public void recordViolation(
            String username,
            String scope,
            WorkerCellularAccessProperties.Mode mode,
            String reason,
            String provider,
            String ipPrefix,
            String clientEvidence,
            boolean blocked
    ) {
        if (!properties.isViolationStatisticsEnabled()
                || (!properties.isCountUnknownNetworkViolations() && "UNKNOWN_NETWORK".equals(reason))) {
            return;
        }

        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                LocalDateTime now = LocalDateTime.now(WORKER_ZONE);
                LocalDateTime episodeSlot = episodeSlot(now, properties.getViolationEpisodeWindow());
                MapSqlParameterSource parameters = new MapSqlParameterSource()
                        .addValue("userId", user.getId())
                        .addValue("username", trim(username, 150))
                        .addValue("reason", trim(reason, 64))
                        .addValue("scope", trim(scope, 64))
                        .addValue("mode", mode.name())
                        .addValue("result", blocked ? "BLOCKED" : "AUDIT_ALLOWED")
                        .addValue("episodeSlot", episodeSlot)
                        .addValue("now", now)
                        .addValue("provider", nullableTrim(provider, 180))
                        .addValue("ipPrefix", nullableTrim(ipPrefix, 80))
                        .addValue("clientEvidence", nullableTrim(clientEvidence, 500));
                jdbcTemplate.update("""
                        INSERT INTO worker_network_violation_episodes (
                            worker_user_id, worker_username, reason_code, scope_code,
                            access_mode, access_result, episode_slot, first_seen_at, last_seen_at,
                            attempt_count, provider, ip_prefix, client_evidence
                        ) VALUES (
                            :userId, :username, :reason, :scope,
                            :mode, :result, :episodeSlot, :now, :now,
                            1, :provider, :ipPrefix, :clientEvidence
                        )
                        ON DUPLICATE KEY UPDATE
                            last_seen_at = VALUES(last_seen_at),
                            attempt_count = attempt_count + 1,
                            access_mode = VALUES(access_mode),
                            access_result = VALUES(access_result),
                            provider = VALUES(provider),
                            ip_prefix = VALUES(ip_prefix),
                            client_evidence = VALUES(client_evidence)
                        """, parameters);
            });
        } catch (RuntimeException exception) {
            // Статистика не должна ломать рабочий запрос специалиста или саму блокировку.
            log.warn("Не удалось сохранить эпизод нарушения сети специалиста: {}", exception.getClass().getSimpleName());
        }
    }

    public Map<Long, WorkerNetworkViolationStatsResponse> statsForPeriod(
            Collection<Long> userIds,
            LocalDate fromInclusive,
            LocalDate toExclusive
    ) {
        if (!properties.isViolationStatisticsEnabled() || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> safeUserIds = new LinkedHashSet<>(userIds.stream().filter(java.util.Objects::nonNull).toList());
        if (safeUserIds.isEmpty()) {
            return Map.of();
        }

        try {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("userIds", safeUserIds)
                    .addValue("from", fromInclusive.atStartOfDay())
                    .addValue("to", toExclusive.atStartOfDay());
            List<ViolationRow> rows = jdbcTemplate.query("""
                    SELECT worker_user_id, first_seen_at, last_seen_at, reason_code, scope_code,
                           attempt_count, provider, client_evidence, access_result
                    FROM worker_network_violation_episodes
                    WHERE worker_user_id IN (:userIds)
                      AND last_seen_at >= :from
                      AND first_seen_at < :to
                      AND access_result <> 'INVALIDATED'
                    ORDER BY last_seen_at DESC
                    """, parameters, (resultSet, rowNumber) -> new ViolationRow(
                    resultSet.getLong("worker_user_id"),
                    resultSet.getTimestamp("first_seen_at").toLocalDateTime(),
                    resultSet.getTimestamp("last_seen_at").toLocalDateTime(),
                    resultSet.getString("reason_code"),
                    resultSet.getString("scope_code"),
                    resultSet.getLong("attempt_count"),
                    resultSet.getString("provider"),
                    resultSet.getString("client_evidence"),
                    "BLOCKED".equals(resultSet.getString("access_result"))
            ));
            return aggregate(safeUserIds, rows);
        } catch (RuntimeException exception) {
            log.warn("Не удалось загрузить статистику нарушений сети специалистов: {}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<Long, WorkerNetworkViolationStatsResponse> aggregate(Set<Long> userIds, List<ViolationRow> rows) {
        Map<Long, List<ViolationRow>> byUser = new HashMap<>();
        rows.forEach(row -> byUser.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row));

        Map<Long, WorkerNetworkViolationStatsResponse> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            List<ViolationRow> userRows = byUser.getOrDefault(userId, List.of()).stream()
                    .sorted(Comparator.comparing(ViolationRow::lastSeenAt).reversed())
                    .toList();
            if (userRows.isEmpty()) {
                result.put(userId, WorkerNetworkViolationStatsResponse.empty());
                continue;
            }
            long attempts = userRows.stream().mapToLong(ViolationRow::attemptCount).sum();
            int days = (int) userRows.stream().map(row -> row.firstSeenAt().toLocalDate()).distinct().count();
            String severity = userRows.stream().anyMatch(row -> "VPN_PROXY_OR_DATACENTER".equals(row.reason()))
                    ? "CRITICAL"
                    : "WARNING";
            List<ViolationDetail> details = userRows.stream().limit(DETAIL_LIMIT)
                    .map(row -> new ViolationDetail(
                            row.firstSeenAt(),
                            row.lastSeenAt(),
                            row.reason(),
                            row.scope(),
                            row.attemptCount(),
                            row.provider(),
                            row.clientEvidence(),
                            row.blocked()
                    ))
                    .toList();
            result.put(userId, new WorkerNetworkViolationStatsResponse(
                    true,
                    userRows.size(),
                    attempts,
                    days,
                    severity,
                    details
            ));
        }
        return result;
    }

    private LocalDateTime episodeSlot(LocalDateTime now, Duration configuredWindow) {
        long windowSeconds = Math.max(60L, configuredWindow == null ? 1_800L : configuredWindow.toSeconds());
        ZonedDateTime zoned = now.atZone(WORKER_ZONE);
        long slotEpoch = Math.floorDiv(zoned.toEpochSecond(), windowSeconds) * windowSeconds;
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(slotEpoch), WORKER_ZONE);
    }

    private String nullableTrim(String value, int maxLength) {
        String trimmed = trim(value, maxLength);
        return trimmed.isBlank() || "unknown".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private String trim(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private record ViolationRow(
            Long userId,
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            String reason,
            String scope,
            long attemptCount,
            String provider,
            String clientEvidence,
            boolean blocked
    ) {
    }
}
