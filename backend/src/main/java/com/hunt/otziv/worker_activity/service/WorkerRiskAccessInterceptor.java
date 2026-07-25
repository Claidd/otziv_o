package com.hunt.otziv.worker_activity.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class WorkerRiskAccessInterceptor implements HandlerInterceptor {

    private final WorkerRiskAccessPolicy accessPolicy;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (HttpMethod.GET.matches(request.getMethod()) && "/api/worker/board".equals(uri)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return true;
        }
        WorkerRiskAccessPolicy.Status status = accessPolicy.status(authentication.getName());
        if (!status.restricted()) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.LOCKED, status.message());
    }
}
