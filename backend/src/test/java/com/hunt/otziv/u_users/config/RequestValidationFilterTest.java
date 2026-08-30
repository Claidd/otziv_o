package com.hunt.otziv.u_users.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RequestValidationFilterTest {

    private final RequestValidationFilter filter = new RequestValidationFilter();
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void allowsExactMaximumTochkaWebhookBodyAndPreservesItForTheController() throws Exception {
        byte[] body = new byte[65_536];
        body[0] = 'a';
        body[body.length - 1] = 'z';
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/payments/tochka/webhook"
        );
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ArgumentCaptor<ServletRequest> forwardedRequest = ArgumentCaptor.forClass(ServletRequest.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(forwardedRequest.capture(), eq(response));
        assertThat(forwardedRequest.getValue()).isNotSameAs(request);
        assertThat(forwardedRequest.getValue().getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void rejectsTochkaWebhookBodyOverExactByteLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/payments/tochka/webhook"
        );
        request.setContent(new byte[65_537]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doesNotApplyTochkaWebhookBodyLimitToLookalikePath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/payments/tochka/webhook/extra"
        );
        request.setContent(new byte[65_537]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsChunkedLegacyMultipartBeforeServletMultipartParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/register;source=legacy");
        request.setContextPath("/app");
        request.setContentType("multipart/form-data; boundary=test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(411);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsBoundedLegacyMultipartWithKnownLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/register");
        request.setContentType("multipart/form-data; boundary=test");
        request.setContent(new byte[256]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsOversizedLegacyMultipartBeforeServletMultipartParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/register");
        request.setContentType("multipart/form-data; boundary=test");
        request.setContent(new byte[5_242_881]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(request, response);
    }
}
