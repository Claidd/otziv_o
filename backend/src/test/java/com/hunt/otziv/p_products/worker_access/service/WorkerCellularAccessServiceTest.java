package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkerCellularAccessServiceTest {

    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void allowsWorkerOnMobilePhoneAndAllowedCellularNetwork() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER");
        request("100.64.10.20", MOBILE_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));
    }

    @Test
    void doesNotBlockUnknownNetworkOrLegacyDesktopByDefault() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER");
        request("192.168.1.20", MOBILE_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("nagul"));

        request("100.64.10.20", DESKTOP_USER_AGENT);
        assertDoesNotThrow(() -> service.enforceProtectedAccess("bad"));
    }

    @Test
    void auditModeRecordsButDoesNotBlock() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerCellularAccessService service = service(
                WorkerCellularAccessProperties.Mode.AUDIT,
                new R0ObservabilityMetrics(registry)
        );
        authenticate("ROLE_WORKER");
        MockHttpServletRequest auditRequest = request("100.64.10.20", MOBILE_USER_AGENT);
        nativeTelemetry(auditRequest, "wifi", "false");

        assertDoesNotThrow(() -> service.enforceSection("recovery"));
        assertEquals(1.0, registry.get("otziv.worker.cellular.access.decision")
                .tags(
                        "mode", "audit",
                        "decision", "would_deny",
                        "reason", "non_cellular_network",
                        "scope", "recovery"
                )
                .counter()
                .count());
    }

    @Test
    void elevatedRoleAndUnprotectedSectionBypassRestriction() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER", "ROLE_MANAGER");
        request("192.168.1.20", DESKTOP_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));

        authenticate("ROLE_WORKER");
        assertDoesNotThrow(() -> service.enforceSection("new"));
    }

    @Test
    void allowsOnlyMobileAndNonRiskyIpIntelligenceResult() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of());
        properties.setIpIntelligenceEnabled(true);
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup("203.0.113.10")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, true, false, "T2 Mobile LLC", "ipquery")
        );
        WorkerCellularAccessService service = new WorkerCellularAccessService(
                properties,
                client,
                mock(WorkerNetworkViolationService.class)
        );
        authenticate("ROLE_WORKER");
        request("203.0.113.10", MOBILE_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));

        when(client.lookup("203.0.113.10")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, false, "Home ISP", "ipquery")
        );
        ResponseStatusException fixedNetwork = assertThrows(
                ResponseStatusException.class,
                () -> service.enforceSection("publish")
        );
        assertEquals(
                "Доступ заблокирован: обнаружена домашняя сеть или Wi-Fi. "
                        + "Отключите Wi-Fi, включите мобильный интернет и повторите действие.",
                fixedNetwork.getReason()
        );

        when(client.lookup("203.0.113.10")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, true, true, "VPN provider", "ipquery")
        );
        ResponseStatusException vpn = assertThrows(
                ResponseStatusException.class,
                () -> service.enforceSection("publish")
        );
        assertEquals(
                "Доступ заблокирован: обнаружен VPN, прокси, Tor или сеть дата-центра. "
                        + "Отключите VPN или прокси и повторите действие через мобильный интернет.",
                vpn.getReason()
        );
    }

    @Test
    void nativeAppRequiresCellularConnectionReportedByPhysicalDevice() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER");

        MockHttpServletRequest wifi = request("100.64.10.20", MOBILE_USER_AGENT);
        nativeTelemetry(wifi, "wifi", "false");
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));

        MockHttpServletRequest emulator = request("100.64.10.20", MOBILE_USER_AGENT);
        nativeTelemetry(emulator, "cellular", "true");
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));

        MockHttpServletRequest phone = request("100.64.10.20", MOBILE_USER_AGENT);
        nativeTelemetry(phone, "cellular", "false");
        assertDoesNotThrow(() -> service.enforceSection("publish"));

        MockHttpServletRequest nativeRequestWithDesktopUserAgent = request("100.64.10.20", DESKTOP_USER_AGENT);
        nativeTelemetry(nativeRequestWithDesktopUserAgent, "cellular", "false");
        assertDoesNotThrow(() -> service.enforceSection("publish"));

        MockHttpServletRequest nativeRequestWithoutUserAgent = request("100.64.10.20", "");
        nativeTelemetry(nativeRequestWithoutUserAgent, "cellular", "false");
        assertDoesNotThrow(() -> service.enforceSection("publish"));
    }

    @Test
    void nativeAppWithUnknownNetworkIsRecordedButNotBlockedByDefault() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER");
        MockHttpServletRequest request = request("100.64.10.20", MOBILE_USER_AGENT);
        nativeTelemetry(request, "unknown", "false");

        assertDoesNotThrow(() -> service.enforceSection("bad"));
    }

    @Test
    void canExplicitlyEnforceLegacyDesktopReason() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of("100.64.0.0/10"));
        properties.setEnforcedReasons(java.util.Set.of("DESKTOP_OR_UNKNOWN_DEVICE"));
        WorkerCellularAccessService service = new WorkerCellularAccessService(
                properties,
                unavailableIntelligenceClient(),
                mock(WorkerNetworkViolationService.class)
        );
        authenticate("ROLE_WORKER");
        request("100.64.10.20", DESKTOP_USER_AGENT);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.enforceSection("publish")
        );
        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void selectiveEnforcementStoresWhetherViolationWasActuallyBlocked() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of("100.64.0.0/10"));
        WorkerNetworkViolationService violations = mock(WorkerNetworkViolationService.class);
        WorkerCellularAccessService service = new WorkerCellularAccessService(
                properties,
                unavailableIntelligenceClient(),
                violations
        );
        authenticate("ROLE_WORKER");
        request("100.64.10.20", DESKTOP_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));
        verify(violations).recordViolation(
                eq("worker"),
                eq("publish"),
                eq(WorkerCellularAccessProperties.Mode.ENFORCE),
                eq("DESKTOP_OR_UNKNOWN_DEVICE"),
                anyString(),
                anyString(),
                eq("client=web-or-legacy"),
                eq(false)
        );
    }

    @Test
    void verifiedMegafonNatRangesAllowMobileBrowserButDoNotConfirmDesktopUserAgent() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of("178.177.216.0/22", "178.177.220.0/22"));
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup(anyString())).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, false, "PJSC MegaFon", "ipquery")
        );
        WorkerNetworkViolationService violations = mock(WorkerNetworkViolationService.class);
        WorkerCellularAccessService service = new WorkerCellularAccessService(properties, client, violations);
        authenticate("ROLE_WORKER");

        request("178.177.216.42", MOBILE_USER_AGENT);
        assertDoesNotThrow(() -> service.enforceSection("publish"));
        verifyNoInteractions(violations);

        request("178.177.223.42", DESKTOP_USER_AGENT);
        assertDoesNotThrow(() -> service.enforceSection("nagul"));
        verify(violations).recordViolation(
                eq("worker"),
                eq("nagul"),
                eq(WorkerCellularAccessProperties.Mode.ENFORCE),
                eq("DESKTOP_OR_UNKNOWN_DEVICE"),
                eq("PJSC MegaFon"),
                eq("178.177.223.0/24"),
                eq("client=web-or-legacy"),
                eq(false)
        );
    }

    @Test
    void verifiedMtsMobileBroadbandRangesOverrideFalseFixedNetworkClassificationButNotVpnRisk() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of(
                "91.78.236.0/22",
                "91.78.216.0/21",
                "91.78.224.0/21",
                "91.79.216.0/21",
                "91.79.224.0/21",
                "91.79.232.0/22"
        ));
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        WorkerNetworkViolationService violations = mock(WorkerNetworkViolationService.class);
        WorkerCellularAccessService service = new WorkerCellularAccessService(properties, client, violations);
        authenticate("ROLE_WORKER");

        when(client.lookup(anyString())).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, false, "MTS PJSC", "ipquery")
        );
        for (String address : List.of(
                "91.78.236.152",
                "91.78.223.254",
                "91.78.231.254",
                "91.79.223.254",
                "91.79.231.254",
                "91.79.235.254"
        )) {
            request(address, MOBILE_USER_AGENT);
            assertDoesNotThrow(() -> service.enforceSection("publish"));
        }
        verifyNoInteractions(violations);

        request("91.79.235.254", MOBILE_USER_AGENT);
        when(client.lookup("91.79.235.254")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, true, "MTS PJSC", "ipquery")
        );
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));
        verify(violations).recordViolation(
                eq("worker"),
                eq("publish"),
                eq(WorkerCellularAccessProperties.Mode.ENFORCE),
                eq("VPN_PROXY_OR_DATACENTER"),
                eq("MTS PJSC"),
                eq("91.79.235.0/24"),
                eq("client=web-or-legacy"),
                eq(true)
        );
    }

    @Test
    void verifiedBeelineTetheringRangeOverridesFalseFixedNetworkClassification() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of("89.113.30.0/23"));
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup(anyString())).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(
                        true,
                        false,
                        false,
                        "PJSC \"Vimpelcom\"",
                        "ipquery"
                )
        );
        WorkerNetworkViolationService violations = mock(WorkerNetworkViolationService.class);
        WorkerCellularAccessService service = new WorkerCellularAccessService(properties, client, violations);
        authenticate("ROLE_WORKER");

        request("89.113.31.42", MOBILE_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));
        verifyNoInteractions(violations);
    }

    @Test
    void appliesRuntimeModeWithoutRestartingTheService() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of());
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup("203.0.113.10")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, false, "Home ISP", "ipquery")
        );
        WorkerCellularAccessRuntimeSettingsService runtime = mock(WorkerCellularAccessRuntimeSettingsService.class);
        when(runtime.currentPolicy()).thenReturn(
                new WorkerCellularAccessRuntimeSettingsService.AccessPolicy(
                        WorkerCellularAccessProperties.Mode.AUDIT,
                        java.util.Set.of("NON_CELLULAR_NETWORK"),
                        true
                ),
                new WorkerCellularAccessRuntimeSettingsService.AccessPolicy(
                        WorkerCellularAccessProperties.Mode.ENFORCE,
                        java.util.Set.of("NON_CELLULAR_NETWORK"),
                        true
                )
        );
        WorkerCellularAccessService service = new WorkerCellularAccessService(
                properties,
                client,
                mock(WorkerNetworkViolationService.class),
                runtime
        );
        authenticate("ROLE_WORKER");
        request("203.0.113.10", MOBILE_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("publish"));
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));
    }

    private WorkerCellularAccessService service(WorkerCellularAccessProperties.Mode mode) {
        WorkerCellularAccessProperties properties = properties(mode);
        properties.setAllowedCidrs(List.of("100.64.0.0/10", "2a00:1fa0::/32"));
        return new WorkerCellularAccessService(
                properties,
                unavailableIntelligenceClient(),
                mock(WorkerNetworkViolationService.class)
        );
    }

    private WorkerCellularAccessService service(
            WorkerCellularAccessProperties.Mode mode,
            R0ObservabilityMetrics observabilityMetrics
    ) {
        WorkerCellularAccessProperties properties = properties(mode);
        properties.setAllowedCidrs(List.of("100.64.0.0/10", "2a00:1fa0::/32"));
        return new WorkerCellularAccessService(
                properties,
                unavailableIntelligenceClient(),
                mock(WorkerNetworkViolationService.class),
                null,
                observabilityMetrics
        );
    }

    private WorkerIpIntelligenceClient unavailableIntelligenceClient() {
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup(anyString())).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(false, false, false, "", "unavailable")
        );
        return client;
    }

    private WorkerCellularAccessProperties properties(WorkerCellularAccessProperties.Mode mode) {
        WorkerCellularAccessProperties properties = new WorkerCellularAccessProperties();
        properties.setMode(mode);
        return properties;
    }

    private void authenticate(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("worker", "password", authorities)
        );
    }

    private MockHttpServletRequest request(String remoteAddress, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private void nativeTelemetry(MockHttpServletRequest request, String network, String virtual) {
        request.addHeader("X-Otziv-App-Client", "capacitor");
        request.addHeader("X-Otziv-Device-Platform", "android");
        request.addHeader("X-Otziv-Device-Model", "Pixel 8");
        request.addHeader("X-Otziv-Device-Virtual", virtual);
        request.addHeader("X-Otziv-Network-Type", network);
        request.addHeader("X-Otziv-App-Version", "1.0.54");
        request.addHeader("X-Otziv-App-Build", "54");
        request.addHeader("X-Otziv-Installation-Id", "install-1234567890");
    }
}
