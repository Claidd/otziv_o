package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
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
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.AUDIT);
        authenticate("ROLE_WORKER");
        request("192.168.1.20", DESKTOP_USER_AGENT);

        assertDoesNotThrow(() -> service.enforceSection("recovery"));
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
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));

        when(client.lookup("203.0.113.10")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, true, true, "VPN provider", "ipquery")
        );
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));
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
    void verifiedMtsIrkutskMobileRangeOverridesFalseFixedNetworkClassificationButNotVpnRisk() {
        WorkerCellularAccessProperties properties = properties(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setAllowedCidrs(List.of("91.78.236.0/22"));
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        WorkerNetworkViolationService violations = mock(WorkerNetworkViolationService.class);
        WorkerCellularAccessService service = new WorkerCellularAccessService(properties, client, violations);
        authenticate("ROLE_WORKER");
        request("91.78.236.152", MOBILE_USER_AGENT);

        when(client.lookup("91.78.236.152")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, false, "MTS PJSC", "ipquery")
        );
        assertDoesNotThrow(() -> service.enforceSection("publish"));
        verifyNoInteractions(violations);

        when(client.lookup("91.78.236.152")).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, true, "MTS PJSC", "ipquery")
        );
        assertThrows(ResponseStatusException.class, () -> service.enforceSection("publish"));
        verify(violations).recordViolation(
                eq("worker"),
                eq("publish"),
                eq(WorkerCellularAccessProperties.Mode.ENFORCE),
                eq("VPN_PROXY_OR_DATACENTER"),
                eq("MTS PJSC"),
                eq("91.78.236.0/24"),
                eq("client=web-or-legacy"),
                eq(true)
        );
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
