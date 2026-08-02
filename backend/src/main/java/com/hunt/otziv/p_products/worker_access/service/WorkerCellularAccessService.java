package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
@Slf4j
public class WorkerCellularAccessService {

    public static final Set<String> PROTECTED_SECTIONS = Set.of("nagul", "publish", "recovery", "bad");
    private static final String REASON_ALLOWED = "ALLOWED";
    private static final String REASON_NON_CELLULAR = "NON_CELLULAR_NETWORK";
    private static final String REASON_VPN = "VPN_PROXY_OR_DATACENTER";
    private static final String REASON_DESKTOP = "DESKTOP_OR_UNKNOWN_DEVICE";
    private static final String REASON_UNKNOWN = "UNKNOWN_NETWORK";
    private static final Set<String> ELEVATED_ROLES = Set.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER");

    private final WorkerCellularAccessProperties properties;
    private final IpCidrMatcher cidrMatcher;
    private final WorkerIpIntelligenceClient ipIntelligenceClient;
    private final WorkerNetworkViolationService networkViolationService;
    private final WorkerCellularAccessRuntimeSettingsService runtimeSettingsService;
    private final R0ObservabilityMetrics observabilityMetrics;

    @Autowired
    public WorkerCellularAccessService(
            WorkerCellularAccessProperties properties,
            WorkerIpIntelligenceClient ipIntelligenceClient,
            WorkerNetworkViolationService networkViolationService,
            WorkerCellularAccessRuntimeSettingsService runtimeSettingsService,
            R0ObservabilityMetrics observabilityMetrics
    ) {
        this.properties = properties;
        this.ipIntelligenceClient = ipIntelligenceClient;
        this.networkViolationService = networkViolationService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.observabilityMetrics = observabilityMetrics;
        this.cidrMatcher = new IpCidrMatcher(properties.getAllowedCidrs());
        if (properties.getMode() != WorkerCellularAccessProperties.Mode.OFF
                && cidrMatcher.isEmpty()
                && !properties.isIpIntelligenceEnabled()) {
            log.warn("Проверка мобильной сети специалистов включена без разрешенных CIDR: mode={}", properties.getMode());
        }
    }

    public void enforceSection(String section) {
        String normalized = normalizeSection(section);
        if (!PROTECTED_SECTIONS.contains(normalized)) {
            return;
        }
        enforceProtectedAccess(normalized);
    }

    public void enforceProtectedAccess(String scope) {
        WorkerCellularAccessRuntimeSettingsService.AccessPolicy policy = accessPolicy();
        WorkerCellularAccessProperties.Mode mode = policy.mode();
        if (mode == WorkerCellularAccessProperties.Mode.OFF) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isWorkerOnly(authentication)) {
            return;
        }

        HttpServletRequest request = currentRequest();
        String clientIp = request == null ? null : request.getRemoteAddr();
        ClientTelemetry telemetry = ClientTelemetry.from(request);
        boolean mobileDevice = mobileDevice(request, telemetry);
        boolean cidrMatch = cidrMatcher.matches(clientIp);
        WorkerIpIntelligenceClient.IpIntelligence intelligence = ipIntelligenceClient.lookup(clientIp);
        boolean serverCellularNetwork = !intelligence.risky() && (cidrMatch || intelligence.mobile());
        String reason = accessReason(mobileDevice, serverCellularNetwork, telemetry, intelligence);
        boolean allowed = REASON_ALLOWED.equals(reason);
        boolean wouldDeny = !allowed && shouldEnforce(reason, telemetry, policy);
        boolean blocked = wouldDeny && mode == WorkerCellularAccessProperties.Mode.ENFORCE;
        if (observabilityMetrics != null) {
            observabilityMetrics.recordWorkerAccessDecision(
                    mode.name(),
                    allowed,
                    wouldDeny,
                    blocked,
                    reason,
                    normalizeScope(scope)
            );
        }

        log.info(
                "Worker cellular access: user={}, scope={}, mode={}, result={}, reason={}, mobileDevice={}, cidrMatch={}, "
                        + "intelKnown={}, intelMobile={}, intelRisky={}, intelOrg={}, clientTelemetry={}, ipPrefix={}",
                authentication.getName(),
                normalizeScope(scope),
                mode,
                allowed ? "ALLOW" : blocked ? "DENY" : "AUDIT_ALLOW",
                reason,
                mobileDevice,
                cidrMatch,
                intelligence.known(),
                intelligence.mobile(),
                intelligence.risky(),
                logValue(intelligence.organization()),
                telemetry.evidence(),
                maskedAddress(clientIp)
        );

        if (!allowed) {
            networkViolationService.recordViolation(
                    authentication.getName(),
                    normalizeScope(scope),
                    mode,
                    reason,
                    intelligence.organization(),
                    maskedAddress(clientIp),
                    telemetry.evidence(),
                    blocked
            );
        }

        if (blocked) {
            throw new ResponseStatusException(FORBIDDEN, deniedMessage(reason));
        }
    }

    WorkerCellularAccessService(
            WorkerCellularAccessProperties properties,
            WorkerIpIntelligenceClient ipIntelligenceClient,
            WorkerNetworkViolationService networkViolationService,
            WorkerCellularAccessRuntimeSettingsService runtimeSettingsService
    ) {
        this(properties, ipIntelligenceClient, networkViolationService, runtimeSettingsService, null);
    }

    WorkerCellularAccessService(
            WorkerCellularAccessProperties properties,
            WorkerIpIntelligenceClient ipIntelligenceClient,
            WorkerNetworkViolationService networkViolationService
    ) {
        this(properties, ipIntelligenceClient, networkViolationService, null);
    }

    private String deniedMessage(String reason) {
        return switch (reason) {
            case REASON_NON_CELLULAR ->
                    "Доступ заблокирован: обнаружена домашняя сеть или Wi-Fi. "
                            + "Отключите Wi-Fi, включите мобильный интернет и повторите действие.";
            case REASON_VPN ->
                    "Доступ заблокирован: обнаружен VPN, прокси, Tor или сеть дата-центра. "
                            + "Отключите VPN или прокси и повторите действие через мобильный интернет.";
            case REASON_DESKTOP ->
                    "Доступ заблокирован: обнаружен компьютер или неподдерживаемое устройство. "
                            + "Откройте сайт на телефоне в обычном мобильном браузере.";
            case REASON_UNKNOWN ->
                    "Доступ заблокирован: не удалось подтвердить мобильную сеть. "
                            + "Отключите Wi-Fi и VPN, включите мобильный интернет и повторите действие.";
            default ->
                    "Этот подраздел доступен специалистам только с мобильного телефона через мобильный интернет.";
        };
    }

    private boolean mobileDevice(HttpServletRequest request, ClientTelemetry telemetry) {
        if (!properties.isRequireMobileDevice()) {
            return true;
        }
        if (telemetry.nativeApp()) {
            return telemetry.physicalSupportedDevice();
        }
        return isMobilePhone(request);
    }

    private String accessReason(
            boolean mobileDevice,
            boolean serverCellularNetwork,
            ClientTelemetry telemetry,
            WorkerIpIntelligenceClient.IpIntelligence intelligence
    ) {
        if (intelligence.risky()) {
            return REASON_VPN;
        }
        if (properties.isRequireMobileDevice() && telemetry.virtualDevice()) {
            return REASON_DESKTOP;
        }
        if (telemetry.nativeApp() && "wifi".equals(telemetry.networkType())) {
            return REASON_NON_CELLULAR;
        }
        if (telemetry.nativeApp() && !"cellular".equals(telemetry.networkType())) {
            return REASON_UNKNOWN;
        }
        if (!serverCellularNetwork) {
            return intelligence.known() ? REASON_NON_CELLULAR : REASON_UNKNOWN;
        }
        if (!mobileDevice) {
            return REASON_DESKTOP;
        }
        return REASON_ALLOWED;
    }

    private boolean shouldEnforce(
            String reason,
            ClientTelemetry telemetry,
            WorkerCellularAccessRuntimeSettingsService.AccessPolicy policy
    ) {
        if (REASON_DESKTOP.equals(reason)
                && telemetry.virtualDevice()
                && policy.enforceNativeVirtualDevice()) {
            return true;
        }
        Set<String> configured = policy.enforcedReasons();
        if (configured == null || configured.isEmpty()) {
            return false;
        }
        String normalizedReason = normalizeSection(reason).toUpperCase(Locale.ROOT);
        return configured.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(normalizedReason::equals);
    }

    private WorkerCellularAccessRuntimeSettingsService.AccessPolicy accessPolicy() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.currentPolicy();
        }
        return new WorkerCellularAccessRuntimeSettingsService.AccessPolicy(
                properties.getMode(),
                properties.getEnforcedReasons(),
                properties.isEnforceNativeVirtualDevice()
        );
    }

    private boolean isWorkerOnly(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return authorities.contains("ROLE_WORKER") && authorities.stream().noneMatch(ELEVATED_ROLES::contains);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private boolean isMobilePhone(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String clientHint = safe(request.getHeader("Sec-CH-UA-Mobile")).trim();
        if ("?1".equals(clientHint) || "1".equals(clientHint)) {
            return true;
        }
        String userAgent = safe(request.getHeader("User-Agent")).toLowerCase(Locale.ROOT);
        if (userAgent.contains("ipad") || userAgent.contains("tablet")) {
            return false;
        }
        boolean iphone = userAgent.contains("iphone") || userAgent.contains("ipod");
        boolean androidPhone = userAgent.contains("android") && userAgent.contains("mobile");
        return iphone
                || androidPhone
                || userAgent.contains("windows phone")
                || userAgent.contains("opera mini")
                || userAgent.contains("opera mobi");
    }

    private String maskedAddress(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.getByName(rawAddress);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + ".0/24";
            }
            return Integer.toHexString(((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF))
                    + ":" + Integer.toHexString(((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF))
                    + ":" + Integer.toHexString(((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF))
                    + ":" + Integer.toHexString(((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF))
                    + "::/64";
        } catch (UnknownHostException exception) {
            return "invalid";
        }
    }

    private String normalizeSection(String section) {
        return safe(section).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScope(String scope) {
        String normalized = normalizeSection(scope);
        return normalized.isEmpty() ? "protected-worker-action" : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String logValue(String value) {
        String normalized = safe(value).replace(',', ' ').replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private record ClientTelemetry(
            boolean nativeApp,
            String platform,
            String model,
            String virtual,
            String networkType,
            String appVersion,
            String appBuild,
            String installationId
    ) {
        private static ClientTelemetry from(HttpServletRequest request) {
            if (request == null || !"capacitor".equalsIgnoreCase(header(request, "X-Otziv-App-Client"))) {
                return new ClientTelemetry(false, "unknown", "unknown", "unknown", "unknown", "unknown", "unknown", "unknown");
            }
            return new ClientTelemetry(
                    true,
                    normalizedHeader(request, "X-Otziv-Device-Platform", 24),
                    normalizedHeader(request, "X-Otziv-Device-Model", 80),
                    normalizedHeader(request, "X-Otziv-Device-Virtual", 12),
                    normalizedHeader(request, "X-Otziv-Network-Type", 24).toLowerCase(Locale.ROOT),
                    normalizedHeader(request, "X-Otziv-App-Version", 32),
                    normalizedHeader(request, "X-Otziv-App-Build", 32),
                    normalizedHeader(request, "X-Otziv-Installation-Id", 80)
            );
        }

        private boolean virtualDevice() {
            return nativeApp && "true".equalsIgnoreCase(virtual);
        }

        private boolean physicalSupportedDevice() {
            if (!nativeApp || !"false".equalsIgnoreCase(virtual)) {
                return false;
            }
            return "android".equalsIgnoreCase(platform) || "ios".equalsIgnoreCase(platform);
        }

        private String evidence() {
            if (!nativeApp) {
                return "client=web-or-legacy";
            }
            return "client=capacitor;platform=" + platform
                    + ";model=" + model
                    + ";virtual=" + virtual
                    + ";network=" + networkType
                    + ";app=" + appVersion + "(" + appBuild + ")"
                    + ";install=" + shortInstallationId(installationId);
        }

        private static String shortInstallationId(String value) {
            if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value)) {
                return "unknown";
            }
            return value.length() <= 12 ? value : value.substring(0, 12);
        }

        private static String normalizedHeader(HttpServletRequest request, String name, int maxLength) {
            String value = header(request, name).replace(';', ' ').replaceAll("[\\r\\n]", " ").trim();
            if (value.isEmpty()) {
                return "unknown";
            }
            return value.length() <= maxLength ? value : value.substring(0, maxLength);
        }

        private static String header(HttpServletRequest request, String name) {
            String value = request.getHeader(name);
            return value == null ? "" : value;
        }
    }
}
