package com.hunt.otziv.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every other servlet filter. Several filters (integration signatures,
 * webhook limits, capability headers) decide by path; Spring Security and MVC match the
 * decoded path. Rejecting escapes that browsers and API clients never produce removes
 * the whole class of "%6D-instead-of-m" bypasses at one choke point.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EncodedPathGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (CanonicalRequestPath.containsDisallowedEncoding(request.getRequestURI())) {
            log.warn("REQUEST_PATH_ENCODING_REJECTED method={}", request.getMethod());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"Некорректный адрес запроса.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
