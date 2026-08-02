package com.hunt.otziv.r_review.capability.interceptor;

import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService.LegacyAction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewCheckLegacyTelemetryInterceptor implements HandlerInterceptor {

    private final ReviewCheckCapabilityService capabilityService;

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (exception != null || response.getStatus() >= 400) {
            return;
        }

        UUID orderDetailId = orderDetailId(request);
        if (orderDetailId == null) {
            return;
        }

        try {
            capabilityService.recordLegacyUse(orderDetailId, action(request));
        } catch (RuntimeException telemetryFailure) {
            // Telemetry must never make an already-successful legacy public link fail.
            log.warn("Review-check legacy capability telemetry update failed: {}",
                    telemetryFailure.getClass().getSimpleName());
        }
    }

    private UUID orderDetailId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get("orderDetailId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LegacyAction action(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/approve") || path.endsWith("/publish")) {
            return LegacyAction.APPROVE;
        }
        if (path.endsWith("/correction") || path.contains("/editReviewses/")) {
            return LegacyAction.CORRECTION;
        }
        if ("GET".equals(request.getMethod())) {
            return LegacyAction.VIEW;
        }
        if ("PUT".equals(request.getMethod()) || "POST".equals(request.getMethod())) {
            return LegacyAction.EDIT;
        }
        return LegacyAction.OTHER;
    }
}
