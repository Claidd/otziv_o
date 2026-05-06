package com.hunt.otziv.metric_snapshots.service;

import com.hunt.otziv.metric_snapshots.dto.MetricSnapshotSeenRequest;
import com.hunt.otziv.metric_snapshots.model.UserMetricSnapshot;
import com.hunt.otziv.metric_snapshots.repository.UserMetricSnapshotRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserMetricSnapshotService {

    public static final String PAGE_MANAGER = "manager";
    public static final String PAGE_WORKER = "worker";

    private final UserMetricSnapshotRepository snapshotRepository;
    private final UserService userService;

    @Transactional
    public Map<String, Integer> deltas(Principal principal, String pageCode, List<MetricValue> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Map.of();
        }

        User user = currentUser(principal);
        String page = normalizePart(pageCode, "page", 40);
        Map<String, UserMetricSnapshot> snapshots = snapshotRepository
                .findByUserIdAndPageCode(user.getId(), page)
                .stream()
                .collect(Collectors.toMap(
                        snapshot -> key(snapshot.getMetricSection(), snapshot.getMetricStatus()),
                        Function.identity(),
                        (left, right) -> left
                ));

        Instant now = Instant.now();
        Map<String, Integer> deltas = new LinkedHashMap<>();
        for (MetricValue metric : metrics) {
            String section = normalizePart(metric.section(), "section", 80);
            String status = normalizeStatus(metric.status(), section);
            int value = Math.max(0, metric.value());
            String key = key(section, status);
            UserMetricSnapshot snapshot = snapshots.get(key);

            if (snapshot == null) {
                snapshotRepository.insertBaselineIfAbsent(user.getId(), page, section, status, value, now);
                deltas.put(key, 0);
                continue;
            }

            deltas.put(key, Math.max(0, value - snapshot.getLastSeenValue()));
        }

        return deltas;
    }

    @Transactional
    public void markSeen(Principal principal, MetricSnapshotSeenRequest request) {
        User user = currentUser(principal);
        String page = normalizePart(request.page(), "page", 40);
        String section = normalizePart(request.section(), "section", 80);
        String status = normalizeStatus(request.status(), section);
        int value = Math.max(0, request.value() == null ? 0 : request.value());

        snapshotRepository.upsertSeenValue(user.getId(), page, section, status, value, Instant.now());
    }

    public static String key(String section, String status) {
        return normalizeKeyPart(section) + "\u001F" + normalizeKeyPart(status);
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private String normalizeStatus(String status, String fallback) {
        return normalizePart(status == null || status.isBlank() ? fallback : status, "status", 120);
    }

    private String normalizePart(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указан параметр " + field);
        }

        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Слишком длинный параметр " + field);
        }

        return normalized;
    }

    private static String normalizeKeyPart(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record MetricValue(String section, String status, int value) {
    }
}
