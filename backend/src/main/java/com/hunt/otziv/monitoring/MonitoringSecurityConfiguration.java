package com.hunt.otziv.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnProperty(name = "otziv.monitoring.enabled", havingValue = "true")
public class MonitoringSecurityConfiguration {
    public static final String PATH = "/api/internal/monitoring/runtime";
    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler monitoringTaskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("monitoring-sample-");scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
    }
    @Bean @Order(-1)
    SecurityFilterChain monitoringSecurity(HttpSecurity http,
            @Value("${otziv.monitoring.shared-secret:}") String secret) throws Exception {
        var authentication = new SecretFilter(secret);
        return http.securityMatcher(PATH).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(authentication, UsernamePasswordAuthenticationFilter.class).build();
    }
    static final class SecretFilter extends OncePerRequestFilter {
        private final byte[] expected;
        SecretFilter(String secret) {
            if (secret == null || secret.length() < 32 || secret.length() > 512 || secret.isBlank())
                throw new IllegalStateException("Monitoring shared secret must contain 32–512 characters");
            expected = digest(secret);
        }
        private static byte[] digest(String value) {
            try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            String provided = request.getHeader("X-Otziv-Monitor-Token");
            if (!"GET".equals(request.getMethod())) { response.setStatus(405); return; }
            if (provided == null || provided.length() > 512 || !MessageDigest.isEqual(expected, digest(provided))) {
                response.setStatus(401); return;
            }
            chain.doFilter(request, response);
        }
    }
}
