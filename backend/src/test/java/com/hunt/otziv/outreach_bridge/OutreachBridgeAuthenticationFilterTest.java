package com.hunt.otziv.outreach_bridge;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OutreachBridgeAuthenticationFilterTest {
    private static final String SECRET = "bridge-secret-at-least-thirty-two-characters";

    @Test
    void acceptsExactlyOneMatchingBridgeToken() throws Exception {
        OutreachBridgeAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/outreach/v1/leads/scan");
        request.addHeader(OutreachBridgeProperties.TOKEN_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMissingOrDuplicateTokenWithoutReflectingIt() throws Exception {
        OutreachBridgeAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/outreach/v1/notifications/failure");
        request.addHeader(OutreachBridgeProperties.TOKEN_HEADER, SECRET);
        request.addHeader(OutreachBridgeProperties.TOKEN_HEADER, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString());
    }

    @Test
    void ignoresUnrelatedManagerRoutes() throws Exception {
        OutreachBridgeAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/whatsapp-group-reply");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }

    private static OutreachBridgeAuthenticationFilter filter() {
        OutreachBridgeProperties properties = new OutreachBridgeProperties();
        properties.setEnabled(true);
        properties.setSharedSecret(SECRET);
        return new OutreachBridgeAuthenticationFilter(properties);
    }
}
