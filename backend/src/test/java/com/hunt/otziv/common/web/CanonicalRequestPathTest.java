package com.hunt.otziv.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CanonicalRequestPathTest {

    @Test
    void decodesSegmentsAndDropsMatrixParametersLikeSpringRouting() {
        assertThat(CanonicalRequestPath.of(request("/api/leads/%6Dodified"))).isEqualTo("/api/leads/modified");
        assertThat(CanonicalRequestPath.of(request("/api/leads/modified;jsessionid=1"))).isEqualTo("/api/leads/modified");
        assertThat(CanonicalRequestPath.of(request("/internal/outreach/v%31/leads"))).isEqualTo("/internal/outreach/v1/leads");
        assertThat(CanonicalRequestPath.of(request("/api/companies/%D0%9E"))).isEqualTo("/api/companies/О");
    }

    @Test
    void stripsContextPath() {
        MockHttpServletRequest request = request("/ctx/api/leads/sync");
        request.setContextPath("/ctx");
        assertThat(CanonicalRequestPath.of(request)).isEqualTo("/api/leads/sync");
    }

    @Test
    void flagsOnlyEscapesThatLegitimateClientsNeverSend() {
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/api/leads/%6Dodified")).isTrue();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/api/review%2Dcheck/x")).isTrue();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/a%2Fb")).isTrue();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/a%25b")).isTrue();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/a%zz")).isTrue();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/a%4")).isTrue();
        // Encoded non-ASCII text and reserved characters remain allowed.
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/api/companies/%D0%9E%D0%9E%D0%9E")).isFalse();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/files/a%20b%40c")).isFalse();
        assertThat(CanonicalRequestPath.containsDisallowedEncoding("/api/review-check/rc1_abc-DEF_123")).isFalse();
    }

    @Test
    void guardRejectsDisallowedEncodingBeforeAnyOtherFilter() throws Exception {
        EncodedPathGuardFilter guard = new EncodedPathGuardFilter();
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (req, res) -> passed.set(true);

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        guard.doFilter(request("/api/leads/%6Dodified"), rejected, chain);
        assertThat(rejected.getStatus()).isEqualTo(400);
        assertThat(passed).isFalse();

        MockHttpServletResponse accepted = new MockHttpServletResponse();
        guard.doFilter(request("/api/review-check/0b8a4f7e-1d2c-4f5e-9a8b-7c6d5e4f3a2b"), accepted, chain);
        assertThat(passed).isTrue();
        assertThat(accepted.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
