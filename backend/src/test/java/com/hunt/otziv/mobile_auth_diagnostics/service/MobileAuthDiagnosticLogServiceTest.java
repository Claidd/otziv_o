package com.hunt.otziv.mobile_auth_diagnostics.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticBatchRequest;
import com.hunt.otziv.mobile_auth_diagnostics.dto.MobileAuthDiagnosticEventRequest;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobileAuthDiagnosticLogServiceTest {

    @Test
    void logIsSearchableMaskedAndRedactsCredentialShapedDetails() {
        WebhookClientIpResolver resolver = mock(WebhookClientIpResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.resolve(request)).thenReturn("178.184.235.78");
        when(request.getHeader("X-Otziv-Installation-Id")).thenReturn("install-b6f6fa24");
        when(request.getHeader("X-Otziv-Device-Platform")).thenReturn("android");
        when(request.getHeader("X-Otziv-Device-Model")).thenReturn("RMX3472");
        MobileAuthDiagnosticLogService service = new MobileAuthDiagnosticLogService(resolver);

        Logger logger = (Logger) LoggerFactory.getLogger("MOBILE_AUTH_DIAGNOSTICS");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("source", "header_menu");
            details.put("password", "must-never-appear");
            details.put("authorization", "Bearer must-never-appear-either");
            service.logBatch(
                    () -> "alfia",
                    request,
                    new MobileAuthDiagnosticBatchRequest(
                            "batch-1",
                            "payload-install",
                            List.of(new MobileAuthDiagnosticEventRequest(
                                    "event-1",
                                    Instant.now(),
                                    "auth.logout_requested",
                                    "run-1",
                                    "1.0.66",
                                    "66",
                                    "cellular",
                                    true,
                                    details
                            ))
                    )
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.contains("user=alfia"));
        assertTrue(message.contains("installation=install-b6f6fa24"));
        assertTrue(message.contains("type=auth.logout_requested"));
        assertTrue(message.contains("source=header_menu"));
        assertTrue(message.contains("device=android/RMX3472"));
        assertTrue(message.contains("ipPrefix=178.184.235.0/24"));
        assertTrue(message.contains("password=[redacted]"));
        assertFalse(message.contains("must-never-appear"));
    }
}
