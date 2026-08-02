package com.hunt.otziv.webhook.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebhookRateLimitFilterTest {

    private WebhookRateLimiter rateLimiter;
    private WebhookClientIpResolver clientIpResolver;
    private FilterChain chain;
    private WebhookRateLimitFilter filter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(WebhookRateLimiter.class);
        clientIpResolver = mock(WebhookClientIpResolver.class);
        chain = mock(FilterChain.class);
        meterRegistry = new SimpleMeterRegistry();
        filter = new WebhookRateLimitFilter(rateLimiter, clientIpResolver, meterRegistry);
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("203.0.113.7");
    }

    @Test
    void separatesReviewPaymentAndWebhookBucketsForTheSameClient() throws Exception {
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        invoke("GET", "/api/review-check/2ca03e9b-8768-4c84-b222-d718c15c80c9");
        invoke("GET", "/api/review-check;source=link/2ca03e9b-8768-4c84-b222-d718c15c80c9");
        invoke("PUT", "/api/review-capability/reviews/17/text");
        invoke("POST", "/review/editReviewses/2ca03e9b-8768-4c84-b222-d718c15c80c9");
        invoke("POST", "/api/payments/public/payment-token/init");
        invoke("POST", "/api/payments/tbank/webhook");

        verify(rateLimiter, org.mockito.Mockito.times(4)).tryAcquire("review-public|203.0.113.7");
        verify(rateLimiter).tryAcquire("payment-public|203.0.113.7");
        verify(rateLimiter).tryAcquire("webhook|203.0.113.7");
    }

    @Test
    void registrationPostsUseDedicatedBucketWithContextAndMatrixParamsWithoutRateLimitingFormsOrLogin()
            throws Exception {
        when(rateLimiter.tryAcquire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        )).thenReturn(true);

        invoke("POST", "/api/auth/register");
        invoke("POST", "/api/auth/register-performer");
        invokeWithContext("POST", "/app/api/auth/register;source=public", "/app");
        invoke("POST", "/register");
        invokeWithContext("POST", "/app/register;source=legacy", "/app");
        invoke("GET", "/register");
        invoke("POST", "/api/auth/login");

        verify(rateLimiter, org.mockito.Mockito.times(5)).tryAcquire(
                org.mockito.ArgumentMatchers.eq("registration-public|203.0.113.7"),
                org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10))
        );
        verifyNoMoreInteractions(rateLimiter);
    }

    @Test
    void returnsBoundedJsonAndRetryAfterWhenLimitIsExceeded() throws Exception {
        when(rateLimiter.tryAcquire("review-public|203.0.113.7")).thenReturn(false);
        when(rateLimiter.retryAfterSeconds()).thenReturn(60L);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/review-check/order-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        assertThat(meterRegistry.counter(
                "otziv.http.rate_limit.rejected",
                "group",
                "review-public"
        ).count()).isEqualTo(1.0);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doesNotTreatLookalikeOrOptionsPathAsRateLimitedTraffic() throws Exception {
        for (List<String> request : List.of(
                List.of("GET", "/api/review-checkevil/order-id"),
                List.of("POST", "/api/auth/registerevil"),
                List.of("POST", "/api/auth/register/extra"),
                List.of("POST", "/api/auth/register-performerevil"),
                List.of("POST", "/registerevil"),
                List.of("POST", "/register/extra"),
                List.of("OPTIONS", "/api/review-check/order-id")
        )) {
            MockHttpServletRequest servletRequest = new MockHttpServletRequest(request.get(0), request.get(1));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(servletRequest, response, chain);

            verify(chain).doFilter(servletRequest, response);
        }

        verify(rateLimiter, never()).tryAcquire(org.mockito.ArgumentMatchers.anyString());
    }

    private void invoke(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    private void invokeWithContext(String method, String path, String contextPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }
}
