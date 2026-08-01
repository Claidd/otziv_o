package com.hunt.otziv.r_review.capability;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.LegacyAction;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class ReviewCheckLegacyTelemetryInterceptorTest {

    @Test
    void recordsLegacyUuidOnlyAfterSuccessfulRequest() {
        ReviewCheckCapabilityService service = mock(ReviewCheckCapabilityService.class);
        ReviewCheckLegacyTelemetryInterceptor interceptor = new ReviewCheckLegacyTelemetryInterceptor(service);
        UUID orderDetailId = UUID.randomUUID();
        MockHttpServletRequest request = request("GET", orderDetailId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(service).recordLegacyUse(orderDetailId, LegacyAction.VIEW);
    }

    @Test
    void doesNotRegisterFailedObjectLookup() {
        ReviewCheckCapabilityService service = mock(ReviewCheckCapabilityService.class);
        ReviewCheckLegacyTelemetryInterceptor interceptor = new ReviewCheckLegacyTelemetryInterceptor(service);
        UUID orderDetailId = UUID.randomUUID();
        MockHttpServletRequest request = request("GET", orderDetailId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(service, never()).recordLegacyUse(orderDetailId, LegacyAction.VIEW);
    }

    @Test
    void secureHeaderRouteIsNeverCountedAsLegacyTelemetry() {
        ReviewCheckCapabilityService service = mock(ReviewCheckCapabilityService.class);
        ReviewCheckLegacyTelemetryInterceptor interceptor = new ReviewCheckLegacyTelemetryInterceptor(service);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/review-capability");
        request.addHeader("X-Review-Capability", "must-not-be-read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(service, never()).recordLegacyUse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletRequest request(String method, UUID orderDetailId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method,
                "/api/review-check/" + orderDetailId
        );
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("orderDetailId", orderDetailId.toString())
        );
        return request;
    }
}
