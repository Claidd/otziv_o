package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ManagerReportReviewRestrictionFilter extends OncePerRequestFilter {

    private final ManagerReportReviewAccessPolicy accessPolicy;
    private final ManagerReportReviewCheckInService checkInService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ManagerReportReviewRestrictionFilter(
            ManagerReportReviewAccessPolicy accessPolicy,
            ManagerReportReviewCheckInService checkInService,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.accessPolicy = accessPolicy;
        this.checkInService = checkInService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || allowed(path) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !manager(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        ManagerReportReviewAccessPolicy.AccessState state = accessPolicy.state(user);
        if (state.pending() && state.restrictedFrom() == null) {
            state = checkInService.checkIn(user);
        }
        if (!state.restricted()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(423);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "code", "MANAGER_REPORT_REVIEW_REQUIRED",
                "message", state.message(),
                "reviewId", state.reviewId(),
                "redirect", "/"
        )));
    }

    private boolean manager(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MANAGER".equals(authority.getAuthority()));
    }

    private boolean allowed(String path) {
        return path.equals("/api/me")
                || path.equals("/api/manager-report-review/access-state")
                || path.equals("/api/manager-report-review/check-in")
                || path.equals("/api/cabinet/profile")
                || path.equals("/api/manager-activity")
                || path.startsWith("/api/manager-activity/")
                || path.equals("/api/personal-reminders")
                || path.startsWith("/api/personal-reminders/")
                || path.equals("/api/gamification/me");
    }
}
