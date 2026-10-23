package com.hunt.otziv.u_users.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAccessPolicy;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCheckInService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewRestrictionFilter;
import com.hunt.otziv.payments.controller.ManagerManualPaymentReviewController;
import com.hunt.otziv.payments.dto.ManualPaymentReviewView;
import com.hunt.otziv.payments.service.SpecialistManualPaymentReviewService;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserServiceImpl;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real production filter chain and controller; financial service is never executed. */
class ManualPaymentReviewSecurityContractTest {
    private static final String PATH = "/api/orders/27127/manual-payment-review";
    private static final String NOTE = "Recipient reported missing transfer";
    private static final String DISPUTE = "{\"expectedReviewId\":5,\"recipientReportedMissing\":true,\"note\":\"" + NOTE + "\"}";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private SpecialistManualPaymentReviewService reviews;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-review-security",
                Map.of("otziv.legacy.enabled", "false", "jwt.secret", "test-only-manual-review-secret-with-32-bytes")));
        context.register(TestConfiguration.class);
        context.refresh();
        reviews = context.getBean(SpecialistManualPaymentReviewService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER", "MANAGER"})
    void authorizedRolesReachExactReadAndDisputeController(String role) throws Exception {
        String bearer = bearer(role);
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 20, 43);
        var view = new ManualPaymentReviewView(5L, 27127L, "WAITING", now.plusDays(1), now,
                "MANAGER_REPORTED", true);
        when(reviews.readForOrder(eq(27127L), any())).thenReturn(view);
        when(reviews.disputeForOrder(eq(27127L), eq(5L), eq(true), eq(NOTE), any())).thenReturn(view);

        mvc.perform(get(PATH).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderId").value(27127)).andExpect(jsonPath("$.id").value(5));
        mvc.perform(post(PATH + "/dispute").header("Authorization", bearer)
                        .contentType("application/json").content(DISPUTE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(27127));
        verify(reviews).readForOrder(eq(27127L), argThat(auth -> auth.getName().equals(role.toLowerCase())));
        verify(reviews).disputeForOrder(eq(27127L), eq(5L), eq(true), eq(NOTE),
                argThat(auth -> auth.getName().equals(role.toLowerCase())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKER", "OPERATOR", "MARKETOLOG", "PERFORMER", "CLIENT"})
    void unrelatedRolesCannotReadOrDispute(String role) throws Exception {
        String bearer = bearer(role);
        mvc.perform(get(PATH).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/dispute").header("Authorization", bearer)
                        .contentType("application/json").content(DISPUTE)).andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    void anonymousRequestsRemainUnauthorized() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/dispute").contentType("application/json").content(DISPUTE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reviews);
    }

    @Test
    void allowanceDoesNotOpenOtherOrderPathsOrMethods() throws Exception {
        String bearer = bearer("ADMIN");
        mvc.perform(post(PATH).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/dispute").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/extra").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/27127").header("Authorization", bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    void orderOwnershipDenialStillReachesTheCaller() throws Exception {
        String bearer = bearer("MANAGER");
        when(reviews.readForOrder(eq(27127L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        when(reviews.disputeForOrder(eq(27127L), eq(5L), eq(true), eq(NOTE), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        mvc.perform(get(PATH).header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(post(PATH + "/dispute").header("Authorization", bearer)
                        .contentType("application/json").content(DISPUTE)).andExpect(status().isNotFound());
    }

    private String bearer(String role) {
        String token = role.toLowerCase() + "-manual-review-token";
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(role.toLowerCase())
                .issuedAt(now.minusSeconds(10)).expiresAt(now.plusSeconds(300))
                .claim("preferred_username", role.toLowerCase())
                .claim("realm_access", Map.of("roles", List.of(role))).build();
        when(context.getBean(JwtDecoder.class).decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, ManagerManualPaymentReviewController.class})
    static class TestConfiguration {
        @Bean UserServiceImpl userService() { return mock(UserServiceImpl.class); }
        @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean RequestValidationFilter requestValidationFilter() { return new RequestValidationFilter(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean JwtAuthFilter jwtAuthFilter(ObjectMapper mapper, JwtService service) {
            return new JwtAuthFilter(mapper, service);
        }
        @Bean ManagerReportReviewRestrictionFilter managerReportReviewRestrictionFilter(ObjectMapper mapper) {
            var policy = mock(ManagerReportReviewAccessPolicy.class);
            when(policy.state(any())).thenReturn(new ManagerReportReviewAccessPolicy.AccessState(
                    false, false, null, null, null, "", 0, 0, null, null, ""));
            return new ManagerReportReviewRestrictionFilter(policy,
                    mock(ManagerReportReviewCheckInService.class), mock(UserRepository.class), mapper);
        }
        @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
        @Bean SpecialistManualPaymentReviewService reviews() { return mock(SpecialistManualPaymentReviewService.class); }
    }
}
