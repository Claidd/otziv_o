package com.hunt.otziv.r_review.capability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
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
        assertThat(response.getHeader("Vary")).isEqualTo("X-Review-Capability");
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
