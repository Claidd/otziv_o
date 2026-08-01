package com.hunt.otziv.r_review.capability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent a shared path cache from mixing responses belonging to different header tokens. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ReviewCheckCapabilityNoStoreFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/review-capability";
    private static final String CAPABILITY_HEADER = "X-Review-Capability";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || !(path.equals(PATH) || path.startsWith(PATH + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.addHeader("Vary", CAPABILITY_HEADER);
        filterChain.doFilter(request, response);
    }
}
