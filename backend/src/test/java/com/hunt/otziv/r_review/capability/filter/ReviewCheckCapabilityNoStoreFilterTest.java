package com.hunt.otziv.r_review.capability.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReviewCheckCapabilityNoStoreFilterTest {

    @Test
    void setsNoStoreAndVaryBeforeCapabilityErrorsOrResponsesAreWritten() throws Exception {
        ReviewCheckCapabilityNoStoreFilter filter = new ReviewCheckCapabilityNoStoreFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/review-capability");
        request.setServletPath("/api/review-capability");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> response.setStatus(404);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow, noarchive");
        assertThat(response.getHeader("Vary")).isEqualTo("X-Review-Capability");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void setsNoStoreForEveryPublicReviewAndPaymentContract() throws Exception {
        ReviewCheckCapabilityNoStoreFilter filter = new ReviewCheckCapabilityNoStoreFilter();

        for (String path : List.of(
                "/api/review-check/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/review/editReviews/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/review/editReviewses/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/api/payments/public/payment-token"
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (ignoredRequest, ignoredResponse) -> response.setStatus(200);

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader("Cache-Control")).as(path).isEqualTo("no-store");
            assertThat(response.getHeader("Pragma")).as(path).isEqualTo("no-cache");
            assertThat(response.getHeader("Vary")).as(path).isNull();
        }
    }

    @Test
    void doesNotApplyCapabilityHeadersToLookalikePaths() throws Exception {
        ReviewCheckCapabilityNoStoreFilter filter = new ReviewCheckCapabilityNoStoreFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/review-check-admin");
        request.setServletPath("/api/review-check-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> response.setStatus(200);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Cache-Control")).isNull();
        assertThat(response.getHeader("X-Robots-Tag")).isNull();
    }

    @Test
    void appliesHeadersBehindContextPathAndWithMatrixParameters() throws Exception {
        ReviewCheckCapabilityNoStoreFilter filter = new ReviewCheckCapabilityNoStoreFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/ctx/api/review-check;source=link/2ca03e9b-8768-4c84-b222-d718c15c80c9"
        );
        request.setContextPath("/ctx");
        request.setServletPath("/api/review-check;source=link/2ca03e9b-8768-4c84-b222-d718c15c80c9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> response.setStatus(404));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
