package com.hunt.otziv.workload_shadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveActivationRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveSettingsRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadLiveSettingsService {

    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_CANARY = "CANARY";
    public static final String MODE_LIVE = "LIVE";
    public static final String ACTIVATION_CONFIRMATION = "ВКЛЮЧИТЬ БОЕВОЙ РЕЖИМ";
    static final String PREFIX = "workload.live.";
    static final String REVISION_KEY = PREFIX + "settings-revision";

    private final WorkloadLiveSettingsRepository repository;
    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final BusinessAuditService businessAuditService;
    private final WorkloadLiveActivationGate activationGate;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final WorkloadTransferOfferRepository offerRepository;
    private final WorkloadTransferWorkflowRepository workflowRepository;
    private final WorkloadLiveRuntimeSafetyService runtimeSafetyService;

    @Transactional(readOnly = true)
    public WorkloadLiveSettingsResponse current() {
        Map<String, String> values = values();
        return new WorkloadLiveSettingsResponse(
                string(values, "mode", MODE_SHADOW).toUpperCase(),
                bool(values, "apply-enabled", false),
                string(values, "history-start-date", "2026-08-01"),
                integer(values, "min-finalized-days", 14),
                integer(values, "stable-hours", 168),
                integer(values, "min-candidates-per-manager", 2),
                managerIds(stringAllowEmpty(values, "canary-manager-ids", "")),
                integer(values, "offer-timeout-minutes", 180),
                string(values, "offer-start-time", "10:00"),
                string(values, "offer-end-time", "21:00"),
                integer(values, "max-transfers-per-manager-day", 5),
                integer(values, "max-transfers-global-day", 10),
                integer(values, "rollback-window-minutes", 30),
                integer(values, "first-live-owner-confirmations", 5),
                bool(values, "emergency-fallback-enabled", false),
                longValue(values, "settings-revision", 1),
                integer(values, "retention-days", 400)
        );
    }

    @Transactional
    public WorkloadLiveSettingsResponse updateOperationalSettings(
            WorkloadLiveSettingsRequest request
    ) {
        if (request == null) {
            throw badRequest("Настройки боевого контура не переданы");
        }
        validate(request);
        WorkloadLiveSettingsResponse before = current();
        if (before.applyEnabled()
                && (MODE_CANARY.equals(before.mode()) || MODE_LIVE.equals(before.mode()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сначала остановите боевой контур, затем изменяйте его настройки"
            );
        }
        long expectedRevision = expectedRevision(request.revision(), before.revision());
        long nextRevision = Math.addExact(expectedRevision, 1);
        Map<String, String> requested = operationalValues(request, before, nextRevision);
        update(requested, expectedRevision);
        WorkloadLiveSettingsResponse after = current();
        audit("UPDATE_WORKLOAD_LIVE_SETTINGS", before, after);
        return after;
    }

    @Transactional
    public WorkloadLiveSettingsResponse activate(WorkloadLiveActivationRequest request) {
        if (request == null || !ACTIVATION_CONFIRMATION.equals(request.confirmation())) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "Для включения введите точную фразу: " + ACTIVATION_CONFIRMATION
            );
        }
        String targetMode = normalizeMode(request.mode());
        if (MODE_SHADOW.equals(targetMode)) {
            throw badRequest("Для остановки боевого контура используйте аварийный выключатель");
        }
        WorkloadLiveSettingsResponse before = current();
        long expectedRevision = expectedRevision(request.revision(), before.revision());
        activationGate.assertReady(targetMode, before);
        long nextRevision = Math.addExact(expectedRevision, 1);
        Map<String, String> requested = allValues(before, targetMode, true, nextRevision);
        update(requested, expectedRevision);
        WorkloadLiveSettingsResponse after = current();
        audit("ACTIVATE_WORKLOAD_LIVE", before, after);
        return after;
    }

    @Transactional
    public WorkloadLiveSettingsResponse emergencyStop(Long revision) {
        WorkloadLiveSettingsResponse before = current();
        long expectedRevision = expectedRevision(revision, before.revision());
        long nextRevision = Math.addExact(expectedRevision, 1);
        Map<String, String> requested = allValues(
                before,
                MODE_SHADOW,
                false,
                nextRevision
        );
        update(requested, expectedRevision);
        var shadowSettings = shadowSettingsService.current();
        java.time.LocalDateTime stoppedAt = java.time.LocalDateTime.now(
                shadowSettingsService.zone(shadowSettings)
        );
        offerRepository.cancelOpenOffers(
                stoppedAt,
                "Аварийная остановка боевого контура"
        );
        workflowRepository.cancelOpenWorkflows(stoppedAt);
        WorkloadLiveSettingsResponse after = current();
        audit("STOP_WORKLOAD_LIVE", before, after);
        return after;
    }

    public boolean applicationAllowed(WorkloadLiveSettingsResponse settings) {
        return settings != null
                && settings.applyEnabled()
                && (MODE_CANARY.equals(settings.mode()) || MODE_LIVE.equals(settings.mode()))
                && runtimeSafetyService.evaluate().allowed();
    }

    public boolean managerAllowed(
            WorkloadLiveSettingsResponse settings,
            Long managerId
    ) {
        if (!applicationAllowed(settings) || managerId == null) {
            return false;
        }
        return MODE_LIVE.equals(settings.mode())
                || settings.canaryManagerIds().contains(managerId);
    }

    private Map<String, String> operationalValues(
            WorkloadLiveSettingsRequest request,
            WorkloadLiveSettingsResponse current,
            long nextRevision
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "mode", current.mode());
        put(values, "apply-enabled", current.applyEnabled());
        put(values, "history-start-date", request.historyStartDate().trim());
        put(values, "min-finalized-days", request.minFinalizedDays());
        put(values, "stable-hours", request.stableHours());
        put(values, "min-candidates-per-manager", request.minCandidatesPerManager());
        put(values, "canary-manager-ids", csv(request.canaryManagerIds()));
        put(values, "offer-timeout-minutes", request.offerTimeoutMinutes());
        put(values, "offer-start-time", LocalTime.parse(request.offerStartTime()));
        put(values, "offer-end-time", LocalTime.parse(request.offerEndTime()));
        put(values, "max-transfers-per-manager-day", request.maxTransfersPerManagerDay());
        put(values, "max-transfers-global-day", request.maxTransfersGlobalDay());
        put(values, "rollback-window-minutes", request.rollbackWindowMinutes());
        put(
                values,
                "first-live-owner-confirmations",
                request.firstLiveOwnerConfirmations()
        );
        put(values, "emergency-fallback-enabled", request.emergencyFallbackEnabled());
        put(
                values,
                "retention-days",
                request.retentionDays() == null
                        ? current.retentionDays()
                        : request.retentionDays()
        );
        put(values, "settings-revision", nextRevision);
        return values;
    }

    private Map<String, String> allValues(
            WorkloadLiveSettingsResponse source,
            String mode,
            boolean applyEnabled,
            long nextRevision
    ) {
        return operationalValues(
                new WorkloadLiveSettingsRequest(
                        source.historyStartDate(),
                        source.minFinalizedDays(),
                        source.stableHours(),
                        source.minCandidatesPerManager(),
                        source.canaryManagerIds(),
                        source.offerTimeoutMinutes(),
                        source.offerStartTime(),
                        source.offerEndTime(),
                        source.maxTransfersPerManagerDay(),
                        source.maxTransfersGlobalDay(),
                        source.rollbackWindowMinutes(),
                        source.firstLiveOwnerConfirmations(),
                        source.emergencyFallbackEnabled(),
                        source.revision(),
                        source.retentionDays()
                ),
                new WorkloadLiveSettingsResponse(
                        mode,
                        applyEnabled,
                        source.historyStartDate(),
                        source.minFinalizedDays(),
                        source.stableHours(),
                        source.minCandidatesPerManager(),
                        source.canaryManagerIds(),
                        source.offerTimeoutMinutes(),
                        source.offerStartTime(),
                        source.offerEndTime(),
                        source.maxTransfersPerManagerDay(),
                        source.maxTransfersGlobalDay(),
                        source.rollbackWindowMinutes(),
                        source.firstLiveOwnerConfirmations(),
                        source.emergencyFallbackEnabled(),
                        source.revision(),
                        source.retentionDays()
                ),
                nextRevision
        );
    }

    private void update(Map<String, String> values, long expectedRevision) {
        int updated = repository.updateAllWithRevision(
                json(values),
                PREFIX,
                REVISION_KEY,
                expectedRevision
        );
        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройки уже изменены. Обновите страницу."
            );
        }
        if (updated != values.size()) {
            throw new IllegalStateException(
                    "Неполный набор workload live настроек: ожидалось "
                            + values.size() + ", обновлено " + updated
            );
        }
        appSettingService.invalidateByPrefix(PREFIX);
    }

    private void validate(WorkloadLiveSettingsRequest request) {
        try {
            LocalDate.parse(request.historyStartDate());
            LocalTime start = LocalTime.parse(request.offerStartTime());
            LocalTime end = LocalTime.parse(request.offerEndTime());
            if (!end.isAfter(start)) {
                throw badRequest("Конец окна предложений должен быть позже начала");
            }
        } catch (DateTimeException exception) {
            throw badRequest("Проверьте дату начала истории и время предложений");
        }
        bounded(request.minFinalizedDays(), 1, 400, "Минимум завершённых дней");
        bounded(request.stableHours(), 1, 720, "Период стабильности");
        bounded(request.minCandidatesPerManager(), 1, 20, "Минимум кандидатов");
        bounded(request.offerTimeoutMinutes(), 1, 240, "Тайм-аут предложения");
        bounded(
                request.maxTransfersPerManagerDay(),
                1,
                100,
                "Лимит передач менеджера"
        );
        bounded(request.maxTransfersGlobalDay(), 1, 500, "Общий лимит передач");
        if (request.maxTransfersGlobalDay() < request.maxTransfersPerManagerDay()) {
            throw badRequest("Общий лимит не может быть меньше лимита одного менеджера");
        }
        bounded(request.rollbackWindowMinutes(), 1, 1440, "Окно отката");
        bounded(
                request.firstLiveOwnerConfirmations(),
                0,
                100,
                "Количество первых подтверждений"
        );
        if (request.retentionDays() != null) {
            bounded(request.retentionDays(), 31, 3650, "Хранение LIVE-истории");
        }
        managerIds(csv(request.canaryManagerIds()));
    }

    private Map<String, String> values() {
        Map<String, String> result = new LinkedHashMap<>();
        for (WorkloadLiveSettingsRepository.SettingProjection row
                : repository.findAllByPrefix(PREFIX)) {
            if (row != null && row.getSettingKey() != null && row.getSettingValue() != null) {
                result.put(row.getSettingKey(), row.getSettingValue());
            }
        }
        return result;
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!MODE_CANARY.equals(normalized) && !MODE_LIVE.equals(normalized)) {
            throw badRequest("Разрешены только режимы CANARY и LIVE");
        }
        return normalized;
    }

    private long expectedRevision(Long requested, long current) {
        if (requested != null && requested != current) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройки уже изменены. Обновите страницу."
            );
        }
        return current;
    }

    private void audit(
            String action,
            WorkloadLiveSettingsResponse before,
            WorkloadLiveSettingsResponse after
    ) {
        businessAuditService.recordSafely(
                action,
                "WORKLOAD_LIVE_SETTINGS",
                "global",
                null,
                null,
                before,
                after,
                "Изменены параметры защищённого контура передачи нагрузки"
        );
    }

    private String json(Map<String, String> values) {
        List<SettingWrite> writes = values.entrySet().stream()
                .map(entry -> new SettingWrite(entry.getKey(), entry.getValue()))
                .toList();
        try {
            return objectMapper.writeValueAsString(writes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать live-настройки", exception);
        }
    }

    private void put(Map<String, String> values, String suffix, Object value) {
        values.put(PREFIX + suffix, String.valueOf(value));
    }

    private String string(Map<String, String> values, String suffix, String fallback) {
        String value = values.get(PREFIX + suffix);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String stringAllowEmpty(
            Map<String, String> values,
            String suffix,
            String fallback
    ) {
        String value = values.get(PREFIX + suffix);
        return value == null ? fallback : value.trim();
    }

    private int integer(Map<String, String> values, String suffix, int fallback) {
        try {
            return Integer.parseInt(string(values, suffix, String.valueOf(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private long longValue(Map<String, String> values, String suffix, long fallback) {
        try {
            return Long.parseLong(string(values, suffix, String.valueOf(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean bool(Map<String, String> values, String suffix, boolean fallback) {
        String value = string(values, suffix, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value);
    }

    private List<Long> managerIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            try {
                long id = Long.parseLong(token.trim());
                if (id <= 0) {
                    throw new NumberFormatException();
                }
                result.add(id);
            } catch (NumberFormatException exception) {
                throw badRequest("ID пилотных менеджеров должны быть положительными числами");
            }
        }
        return List.copyOf(result);
    }

    private String csv(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw badRequest("ID пилотных менеджеров должны быть положительными числами");
            }
            values.add(String.valueOf(id));
        }
        return String.join(",", new LinkedHashSet<>(values));
    }

    private void bounded(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw badRequest(label + ": допустимо от " + minimum + " до " + maximum);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record SettingWrite(String settingKey, String settingValue) {
    }
}

