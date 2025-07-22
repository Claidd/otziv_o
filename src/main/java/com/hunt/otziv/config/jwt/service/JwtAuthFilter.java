package com.hunt.otziv.config.jwt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secret;

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;

    private final Map<String, BiConsumer<HttpServletRequestContext, HttpServletResponse>> pathChecks = new HashMap<>();

    @PostConstruct
    public void init() {
        pathChecks.put("/api/leads/import", this::checkImportAuth);
        pathChecks.put("/api/leads/modified", this::checkSyncAuth);
        pathChecks.put("/api/leads/update", this::checkSyncAuth);
        pathChecks.put("/api/dispatch-settings/cron", this::checkSyncAuth);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        // 🔓 Пропускаем вебхуки и health без JWT и авторизации
        if (uri.startsWith("/webhook") || uri.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (pathChecks.containsKey(uri)) {
            HttpServletRequestContext context = new HttpServletRequestContext(request);
            try {
                pathChecks.get(uri).accept(context, response);
            } catch (AuthException e) {
                log.warn("🔒 Ошибка авторизации: {} ({}): {}", e.status, uri, e.getMessage());
                response.sendError(e.status, e.getMessage());
                return;
            }

            if (uri.equals("/api/leads/import")) {
                filterChain.doFilter(context.getWrappedRequest(), response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }


//    @Override
//    protected void doFilterInternal(HttpServletRequest request,
//                                    @NotNull HttpServletResponse response,
//                                    @NotNull FilterChain filterChain)
//            throws ServletException, IOException {
//
//        String uri = request.getRequestURI();
//
//        if (pathChecks.containsKey(uri)) {
//            HttpServletRequestContext context = new HttpServletRequestContext(request);
//            try {
//                pathChecks.get(uri).accept(context, response);
//            } catch (AuthException e) {
//                log.warn("🔒 Ошибка авторизации: {} ({}): {}", e.status, uri, e.getMessage());
//                response.sendError(e.status, e.getMessage());
//                return;
//            }
//
//            if (uri.equals("/api/leads/import")) {
//                filterChain.doFilter(context.getWrappedRequest(), response);
//                return;
//            }
//        }
//
//        filterChain.doFilter(request, response);
//    }

    private void checkImportAuth(HttpServletRequestContext context, HttpServletResponse response) {
        String token = extractTokenOrThrow(context.request);
        Claims claims = parseTokenOrThrow(token);

        String expectedChecksum = claims.get("checksum", String.class);

        try {
            CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(context.request);
            context.setWrappedRequest(wrapped);
            LeadDtoTransfer dto = objectMapper.readValue(wrapped.getInputStream(), LeadDtoTransfer.class);
            String actualChecksum = jwtService.generateChecksum(dto);
            if (!expectedChecksum.equals(actualChecksum)) {
                throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Checksum mismatch");
            }
        } catch (IOException e) {
            throw new AuthException(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
        }
    }

    private void checkSyncAuth(HttpServletRequestContext context, HttpServletResponse response) {
        String token = extractTokenOrThrow(context.request);
        Claims claims = parseTokenOrThrow(token);

        if (!"lead-sync".equals(claims.getSubject())) {
            throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Wrong token subject");
        }

        // ✅ Устанавливаем Authentication в SecurityContext
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null, List.of() // Можно добавить роли, если нужно
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.info("🛡 JWT авторизация прошла: subject = {}", claims.getSubject());
    }

    private String extractTokenOrThrow(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing token");
        }
        return authHeader.substring(7);
    }

    private Claims parseTokenOrThrow(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secret.getBytes(StandardCharsets.UTF_8))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Invalid token");
        }
    }

    @RequiredArgsConstructor
    public static class AuthException extends RuntimeException {
        private final int status;
        private final String message;

        @Override
        public String getMessage() {
            return message;
        }
    }

    @Getter
    private static class HttpServletRequestContext {
        private final HttpServletRequest request;
        private HttpServletRequest wrappedRequest;

        public HttpServletRequestContext(HttpServletRequest request) {
            this.request = request;
            this.wrappedRequest = request;
        }

        public void setWrappedRequest(HttpServletRequest wrappedRequest) {
            this.wrappedRequest = wrappedRequest;
        }
    }
}





