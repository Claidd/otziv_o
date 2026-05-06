package com.hunt.otziv.config.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestApiAuditActionResolverTest {

    private final RestApiAuditActionResolver resolver = new RestApiAuditActionResolver();

    @Test
    void resolvesStatusFromRequestAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/manager/orders/21022/status"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/manager/orders/{orderId}/status"
        );
        request.setAttribute("status", "Архив");

        String action = resolver.resolve(request, null);

        assertEquals("смена статуса заказа на \"Архив\"", action);
    }
}
