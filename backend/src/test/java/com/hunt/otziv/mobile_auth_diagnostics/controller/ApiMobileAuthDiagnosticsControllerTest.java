package com.hunt.otziv.mobile_auth_diagnostics.controller;

import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticBatchRequest;
import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticEventRequest;
import com.hunt.otziv.mobile_auth_diagnostics.service.MobileAuthDiagnosticLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiMobileAuthDiagnosticsControllerTest {

    @Test
    void authenticatedEndpointDelegatesBoundedBatchAndReturnsNoContent() throws Exception {
        MobileAuthDiagnosticLogService service = mock(MobileAuthDiagnosticLogService.class);
        ApiMobileAuthDiagnosticsController controller = new ApiMobileAuthDiagnosticsController(service);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        Principal principal = () -> "alfia";
        MobileAuthDiagnosticBatchRequest batch = batch();

        controller.ingest(servletRequest, principal, batch);

        verify(service).logBatch(principal, servletRequest, batch);
        assertEquals(1, servletRequest.getAttribute("mobileDiagnosticEventCount"));

        PreAuthorize authorization = ApiMobileAuthDiagnosticsController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(authorization);
        assertEquals("isAuthenticated()", authorization.value());
        assertEquals("/api/mobile/auth-diagnostics", ApiMobileAuthDiagnosticsController.class
                .getAnnotation(RequestMapping.class).value()[0]);

        Method ingest = ApiMobileAuthDiagnosticsController.class.getMethod(
                "ingest",
                HttpServletRequest.class,
                Principal.class,
                MobileAuthDiagnosticBatchRequest.class
        );
        assertNotNull(ingest.getAnnotation(ResponseStatus.class));
        assertNotNull(ingest.getParameters()[2].getAnnotation(Valid.class));
        assertNotNull(ingest.getParameters()[2].getAnnotation(RequestBody.class));
    }

    @Test
    void batchAndDetailsHaveStrictSizeLimits() throws Exception {
        Method events = MobileAuthDiagnosticBatchRequest.class.getDeclaredMethod("events");
        assertNotNull(events.getAnnotation(NotEmpty.class));
        assertEquals(80, events.getAnnotation(Size.class).max());

        Method details = MobileAuthDiagnosticEventRequest.class.getDeclaredMethod("details");
        assertEquals(20, details.getAnnotation(Size.class).max());
    }

    private MobileAuthDiagnosticBatchRequest batch() {
        return new MobileAuthDiagnosticBatchRequest(
                "batch-1",
                "install-1",
                List.of(new MobileAuthDiagnosticEventRequest(
                        "event-1",
                        Instant.parse("2026-08-09T04:31:20Z"),
                        "auth.logout_requested",
                        "run-1",
                        "1.0.66",
                        "66",
                        "cellular",
                        true,
                        Map.of("source", "header_menu")
                ))
        );
    }
}
