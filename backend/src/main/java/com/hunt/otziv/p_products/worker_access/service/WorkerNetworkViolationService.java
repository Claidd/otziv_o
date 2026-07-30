package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse;
import com.hunt.otziv.p_products.worker_access.dto.WorkerNetworkViolationStatsResponse.ViolationDetail;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository;
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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerNetworkViolationService {
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final int DETAIL_LIMIT = 20;

    private final WorkerCellularAccessProperties properties;
    private final UserRepository userRepository;
    private final WorkerNetworkViolationRepository violationRepository;

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
                violationRepository.upsertEpisode(
                        user.getId(),
                        trim(username, 150),
                        trim(reason, 64),
                        trim(scope, 64),
                        mode.name(),
                        blocked ? "BLOCKED" : "AUDIT_ALLOWED",
                        episodeSlot,
                        now,
                        nullableTrim(provider, 180),
                        nullableTrim(ipPrefix, 80),
                        nullableTrim(clientEvidence, 500)
                );
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
            List<ViolationRow> rows = violationRepository.findActiveForUsers(
                            safeUserIds,
                            fromInclusive.atStartOfDay(),
                            toExclusive.atStartOfDay()
                    ).stream()
                    .map(row -> new ViolationRow(
                            row.getUserId(),
                            row.getFirstSeenAt(),
                            row.getLastSeenAt(),
                            row.getReason(),
                            row.getScope(),
                            row.getAttemptCount(),
                            row.getProvider(),
                            row.getClientEvidence(),
                            "BLOCKED".equals(row.getAccessResult())
                    ))
                    .toList();
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
