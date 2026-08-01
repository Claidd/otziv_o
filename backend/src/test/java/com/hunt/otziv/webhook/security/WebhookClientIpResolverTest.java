package com.hunt.otziv.webhook.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebhookClientIpResolverTest {

    @Test
    void ignoresSpoofedForwardedHeadersFromUntrustedPeer() {
        WebhookClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest request = requestFrom("198.51.100.25");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.10");

        assertEquals("198.51.100.25", resolver.resolve(request));
    }

    @Test
    void usesContainerPeerWhenFrameworkForwardedWrapperRewritesRemoteAddress() {
        WebhookClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest containerRequest = requestFrom("198.51.100.25");
        containerRequest.addHeader("X-Forwarded-For", "203.0.113.9");
        HttpServletRequest forwardedWrapper = new HttpServletRequestWrapper(containerRequest) {
            @Override
            public String getRemoteAddr() {
                return "203.0.113.9";
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return Collections.emptyEnumeration();
            }
        };

        assertEquals("198.51.100.25", resolver.resolve(forwardedWrapper));
    }

    @Test
    void walksTrustedProxyChainFromRightToLeft() {
        WebhookClientIpResolver resolver = resolver("10.0.0.0/8,192.168.0.0/16");
        MockHttpServletRequest request = requestFrom("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 192.168.1.10");

        assertEquals("198.51.100.20", resolver.resolve(request));
    }

    @Test
    void stopsAtFirstUntrustedHopAndIgnoresSpoofedPrefix() {
        WebhookClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest request = requestFrom("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void rejectsForwardedChainsOverConfiguredAddressLimit() {
        WebhookClientIpResolver resolver = new WebhookClientIpResolver("10.0.0.0/8", 2, 256);
        MockHttpServletRequest request = requestFrom("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 198.51.100.2, 198.51.100.3");

        assertEquals("10.0.0.5", resolver.resolve(request));
    }

    private static WebhookClientIpResolver resolver(String trustedProxies) {
        return new WebhookClientIpResolver(trustedProxies, 16, 2_048);
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
