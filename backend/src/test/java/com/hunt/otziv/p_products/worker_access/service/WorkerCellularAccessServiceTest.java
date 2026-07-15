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
import static org.mockito.Mockito.mock;
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
    void deniesWorkerOnWifiOrDesktop() {
        WorkerCellularAccessService service = service(WorkerCellularAccessProperties.Mode.ENFORCE);
        authenticate("ROLE_WORKER");
        request("192.168.1.20", MOBILE_USER_AGENT);

        ResponseStatusException wifi = assertThrows(
                ResponseStatusException.class,
                () -> service.enforceSection("nagul")
        );
        assertEquals(403, wifi.getStatusCode().value());

        request("100.64.10.20", DESKTOP_USER_AGENT);
        assertThrows(ResponseStatusException.class, () -> service.enforceProtectedAccess("bad"));
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

    private WorkerCellularAccessService service(WorkerCellularAccessProperties.Mode mode) {
        WorkerCellularAccessProperties properties = properties(mode);
        properties.setAllowedCidrs(List.of("100.64.0.0/10", "2a00:1fa0::/32"));
        WorkerIpIntelligenceClient client = mock(WorkerIpIntelligenceClient.class);
        when(client.lookup(anyString())).thenReturn(
                new WorkerIpIntelligenceClient.IpIntelligence(false, false, false, "", "unavailable")
        );
        return new WorkerCellularAccessService(
                properties,
                client,
                mock(WorkerNetworkViolationService.class)
        );
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

    private void request(String remoteAddress, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
