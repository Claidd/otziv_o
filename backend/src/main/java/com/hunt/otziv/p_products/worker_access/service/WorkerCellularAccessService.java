package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
    private static final Set<String> ELEVATED_ROLES = Set.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER");
    private static final String DENIED_MESSAGE =
            "Этот подраздел доступен специалистам только с мобильного телефона через мобильный интернет. "
                    + "Отключите Wi-Fi и VPN, затем повторите попытку.";

    private final WorkerCellularAccessProperties properties;
    private final IpCidrMatcher cidrMatcher;
    private final WorkerIpIntelligenceClient ipIntelligenceClient;
    private final WorkerNetworkViolationService networkViolationService;

    public WorkerCellularAccessService(
            WorkerCellularAccessProperties properties,
            WorkerIpIntelligenceClient ipIntelligenceClient,
            WorkerNetworkViolationService networkViolationService
    ) {
        this.properties = properties;
        this.ipIntelligenceClient = ipIntelligenceClient;
        this.networkViolationService = networkViolationService;
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
        WorkerCellularAccessProperties.Mode mode = properties.getMode();
        if (mode == WorkerCellularAccessProperties.Mode.OFF) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isWorkerOnly(authentication)) {
            return;
        }

        HttpServletRequest request = currentRequest();
        String clientIp = request == null ? null : request.getRemoteAddr();
        boolean mobileDevice = !properties.isRequireMobileDevice() || isMobilePhone(request);
        boolean cidrMatch = cidrMatcher.matches(clientIp);
        WorkerIpIntelligenceClient.IpIntelligence intelligence = ipIntelligenceClient.lookup(clientIp);
        boolean cellularNetwork = !intelligence.risky() && (cidrMatch || intelligence.mobile());
        boolean allowed = mobileDevice && cellularNetwork;
        String reason = accessReason(mobileDevice, cellularNetwork, intelligence);

        log.info(
                "Worker cellular access: user={}, scope={}, mode={}, result={}, reason={}, mobileDevice={}, cidrMatch={}, "
                        + "intelKnown={}, intelMobile={}, intelRisky={}, intelOrg={}, ipPrefix={}",
                authentication.getName(),
                normalizeScope(scope),
                mode,
                allowed ? "ALLOW" : mode == WorkerCellularAccessProperties.Mode.AUDIT ? "AUDIT_ALLOW" : "DENY",
                reason,
                mobileDevice,
                cidrMatch,
                intelligence.known(),
                intelligence.mobile(),
                intelligence.risky(),
                logValue(intelligence.organization()),
                maskedAddress(clientIp)
        );

        if (!allowed) {
            networkViolationService.recordViolation(
                    authentication.getName(),
                    normalizeScope(scope),
                    mode,
                    reason,
                    intelligence.organization(),
                    maskedAddress(clientIp)
            );
        }

        if (!allowed && mode == WorkerCellularAccessProperties.Mode.ENFORCE) {
            throw new ResponseStatusException(FORBIDDEN, DENIED_MESSAGE);
        }
    }

    private String accessReason(
            boolean mobileDevice,
            boolean cellularNetwork,
            WorkerIpIntelligenceClient.IpIntelligence intelligence
    ) {
        if (!mobileDevice) {
            return "DESKTOP_OR_UNKNOWN_DEVICE";
        }
        if (intelligence.risky()) {
            return "VPN_PROXY_OR_DATACENTER";
        }
        if (!cellularNetwork) {
            return intelligence.known() ? "NON_CELLULAR_NETWORK" : "UNKNOWN_NETWORK";
        }
        return "ALLOWED";
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
}
