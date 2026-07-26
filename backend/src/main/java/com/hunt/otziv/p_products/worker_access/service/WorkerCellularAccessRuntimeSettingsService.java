package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkerCellularAccessRuntimeSettingsService {

    public static final List<String> SUPPORTED_REASONS = List.of(
            "NON_CELLULAR_NETWORK",
            "VPN_PROXY_OR_DATACENTER",
            "DESKTOP_OR_UNKNOWN_DEVICE",
            "UNKNOWN_NETWORK"
    );

    private final WorkerCellularAccessProperties properties;
    private final AppSettingService appSettingService;
    private final BusinessAuditService businessAuditService;

    private volatile AccessPolicy cachedPolicy;

    public AccessPolicy currentPolicy() {
        AccessPolicy current = cachedPolicy;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedPolicy == null) {
                cachedPolicy = loadPolicy();
            }
            return cachedPolicy;
        }
    }

    @Transactional
    public AccessPolicy update(
            WorkerCellularAccessProperties.Mode mode,
            Collection<String> enforcedReasons,
            boolean enforceNativeVirtualDevice
    ) {
        if (mode == null) {
            throw new IllegalArgumentException("Выберите режим контроля мобильного доступа");
        }
        Set<String> normalizedReasons = normalizeReasons(enforcedReasons);
        AccessPolicy previous = currentPolicy();

        appSettingService.setString(AppSettingService.WORKER_CELLULAR_ACCESS_MODE, mode.name());
        appSettingService.setString(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCED_REASONS,
                String.join(",", normalizedReasons)
        );
        appSettingService.setBoolean(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCE_NATIVE_VIRTUAL_DEVICE,
                enforceNativeVirtualDevice
        );

        AccessPolicy updated = new AccessPolicy(mode, normalizedReasons, enforceNativeVirtualDevice);
        cachedPolicy = updated;
        businessAuditService.recordSafely(
                "UPDATE_WORKER_CELLULAR_ACCESS",
                "WORKER_ACCESS_POLICY",
                "global",
                null,
                null,
                previous,
                updated,
                "Изменены настройки контроля мобильного доступа специалистов"
        );
        return updated;
    }

    public List<String> allowedCidrs() {
        return List.copyOf(properties.getAllowedCidrs());
    }

    public boolean requireMobileDevice() {
        return properties.isRequireMobileDevice();
    }

    public boolean ipIntelligenceEnabled() {
        return properties.isIpIntelligenceEnabled();
    }

    private AccessPolicy loadPolicy() {
        WorkerCellularAccessProperties.Mode fallbackMode = properties.getMode();
        String configuredMode = appSettingService.getString(
                AppSettingService.WORKER_CELLULAR_ACCESS_MODE,
                fallbackMode.name()
        );
        WorkerCellularAccessProperties.Mode mode = parseMode(configuredMode, fallbackMode);

        String fallbackReasons = String.join(",", orderedReasons(properties.getEnforcedReasons()));
        String configuredReasons = appSettingService.getStringAllowEmpty(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCED_REASONS,
                fallbackReasons
        );
        Set<String> reasons = normalizeReasons(splitReasons(configuredReasons));
        boolean enforceNativeVirtualDevice = appSettingService.getBoolean(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCE_NATIVE_VIRTUAL_DEVICE,
                properties.isEnforceNativeVirtualDevice()
        );
        return new AccessPolicy(mode, reasons, enforceNativeVirtualDevice);
    }

    private WorkerCellularAccessProperties.Mode parseMode(
            String value,
            WorkerCellularAccessProperties.Mode fallback
    ) {
        try {
            return WorkerCellularAccessProperties.Mode.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private Collection<String> splitReasons(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private Set<String> normalizeReasons(Collection<String> reasons) {
        Set<String> requested = reasons == null
                ? Set.of()
                : reasons.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unsupported = requested.stream()
                .filter(reason -> !SUPPORTED_REASONS.contains(reason))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("Неизвестные причины блокировки: " + String.join(", ", unsupported));
        }
        return orderedReasons(requested);
    }

    private Set<String> orderedReasons(Collection<String> reasons) {
        return SUPPORTED_REASONS.stream()
                .filter(reasons::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record AccessPolicy(
            WorkerCellularAccessProperties.Mode mode,
            Set<String> enforcedReasons,
            boolean enforceNativeVirtualDevice
    ) {
        public AccessPolicy {
            enforcedReasons = Collections.unmodifiableSet(new LinkedHashSet<>(enforcedReasons));
        }
    }
}
