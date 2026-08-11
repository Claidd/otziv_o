package com.hunt.otziv.u_users.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.common_billing.controller.PublicCommonInvoiceController;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAccessPolicy;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCheckInService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewRestrictionFilter;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.controller.PublicPaymentController;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserServiceImpl;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class PublicCapabilitySecurityContractTest {

    private static final String PAYMENT_REQUEST = """
            {
              "email": "client@example.com",
              "offerConsent": true,
              "privacyConsent": true,
              "receiptConsent": true
            }
            """;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "public-capability-contract",
                Map.of(
                        "otziv.legacy.enabled", "false",
                        "jwt.secret", "test-only-public-capability-secret-with-32-bytes"
                )
        ));
        context.register(TestConfiguration.class);
        context.refresh();

        Filter securityFilter = context.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(securityFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousReviewCheckCapabilityRoutesRemainPublic() throws Exception {
        mockMvc.perform(get("/api/review-check/contract-id"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/review-check/contract-id"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/review-check/contract-id/reviews/501/text"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/review-check/contract-id/reviews/501/answer"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/review-check/contract-id/correction"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/review-check/contract-id/approve"))
                .andExpect(status().isOk());
    }

    @Test
    void reviewCheckUsesValidBearerButIgnoresExpiredBearer() throws Exception {
        stubJwt("owner-review-token", "OWNER");
        mockMvc.perform(get("/api/review-check/contract-id")
                        .header("Authorization", "Bearer owner-review-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("owner"));

        when(context.getBean(JwtDecoder.class).decode("expired-review-token"))
                .thenThrow(new JwtException("expired"));
        mockMvc.perform(get("/api/review-check/contract-id")
                .header("Authorization", "Bearer expired-review-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actor").value("anonymous"));
    }

    @Test
    void anonymousOpaqueReviewCapabilityUsesHeaderOnlyPublicApi() throws Exception {
        mockMvc.perform(get("/api/review-capability")
                        .header("X-Review-Capability", "opaque-contract-token"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/review-capability/reviews/501/text")
                        .header("X-Review-Capability", "opaque-contract-token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/review-capability/approve")
                        .header("X-Review-Capability", "opaque-contract-token"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotIssueOrRotateOpaqueReviewCapabilities() throws Exception {
        mockMvc.perform(post("/api/manager/orders/11/review-check-capabilities"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/manager/orders/11/review-check-capabilities/5/rotate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void liveLogsRequireAdminOrOwnerEvenForAuthenticatedUsers() throws Exception {
        stubJwt("worker-token", "WORKER");
        stubJwt("owner-token", "OWNER");

        mockMvc.perform(get("/ws/logs")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ws/logs")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousPaymentLinkCanBeReadAndInitialized() throws Exception {
        mockMvc.perform(get("/api/payments/public/payment-token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/public/payment-token/init")
                        .contentType("application/json")
                        .header("User-Agent", "contract-test")
                        .header("X-Forwarded-For", "198.51.100.99")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .content(PAYMENT_REQUEST))
                .andExpect(status().isOk());

        PaymentLinkService paymentLinkService = context.getBean(PaymentLinkService.class);
        verify(paymentLinkService).publicLink("payment-token");
        verify(paymentLinkService).init(
                eq("payment-token"),
                eq("client@example.com"),
                eq(true),
                eq(true),
                eq(true),
                eq("203.0.113.7"),
                eq("contract-test")
        );
    }

    @Test
    void internalTbankStatusRequiresAdminOrOwnerAuthentication() throws Exception {
        mockMvc.perform(get("/api/payments/public/tbank-status"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/payments/tbank-status"))
                .andExpect(status().isUnauthorized());

        stubJwt("worker-token", "WORKER");
        mockMvc.perform(get("/api/admin/payments/tbank-status")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isForbidden());

        stubJwt("owner-token", "OWNER");
        TbankRuntimeSettingsService runtimeSettingsService = context.getBean(TbankRuntimeSettingsService.class);
        PaymentProfileService paymentProfileService = context.getBean(PaymentProfileService.class);
        when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.TEST);
        when(paymentProfileService.defaultEntityProfile()).thenThrow(new IllegalStateException("use env fallback"));

        mockMvc.perform(get("/api/admin/payments/tbank-status")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCommonInvoiceCanBeReadAndInitialized() throws Exception {
        mockMvc.perform(get("/api/payments/public/group/invoice-token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/public/group/invoice-token/init")
                        .contentType("application/json")
                        .content(PAYMENT_REQUEST))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/public/group/invoice-token/reported-paid"))
                .andExpect(status().isOk());

        CommonBillingService commonBillingService = context.getBean(CommonBillingService.class);
        verify(commonBillingService).publicInvoice("invoice-token");
        verify(commonBillingService).reportPublicCommonPayment("invoice-token");
        verify(commonBillingService).initPublicPayment(
                "invoice-token",
                "client@example.com",
                true,
                true,
                true
        );
    }

    private void stubJwt(String tokenValue, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(role.toLowerCase() + "-subject")
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .claim("preferred_username", role.toLowerCase())
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        when(context.getBean(JwtDecoder.class).decode(tokenValue)).thenReturn(jwt);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfiguration {

        @Bean
        UserServiceImpl userService() {
            return mock(UserServiceImpl.class);
        }

        @Bean
        BCryptPasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        RequestValidationFilter requestValidationFilter() {
            return new RequestValidationFilter();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JwtService jwtService() {
            return mock(JwtService.class);
        }

        @Bean
        JwtAuthFilter jwtAuthFilter(ObjectMapper objectMapper, JwtService jwtService) {
            return new JwtAuthFilter(objectMapper, jwtService);
        }

        @Bean
        ManagerReportReviewRestrictionFilter managerReportReviewRestrictionFilter(ObjectMapper objectMapper) {
            return new ManagerReportReviewRestrictionFilter(
                    mock(ManagerReportReviewAccessPolicy.class),
                    mock(ManagerReportReviewCheckInService.class),
                    mock(UserRepository.class),
                    objectMapper
            );
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        PaymentLinkService paymentLinkService() {
            return mock(PaymentLinkService.class);
        }

        @Bean
        TbankPaymentProperties tbankPaymentProperties() {
            return new TbankPaymentProperties();
        }

        @Bean
        TbankRuntimeSettingsService tbankRuntimeSettingsService() {
            return mock(TbankRuntimeSettingsService.class);
        }

        @Bean
        PaymentProfileService paymentProfileService() {
            return mock(PaymentProfileService.class);
        }

        @Bean
        PublicPaymentController publicPaymentController(
                PaymentLinkService paymentLinkService,
                TbankPaymentProperties properties,
                TbankRuntimeSettingsService runtimeSettingsService,
                PaymentProfileService paymentProfileService,
                WebhookClientIpResolver clientIpResolver
        ) {
            return new PublicPaymentController(
                    paymentLinkService,
                    properties,
                    runtimeSettingsService,
                    paymentProfileService,
                    clientIpResolver
            );
        }

        @Bean
        WebhookClientIpResolver webhookClientIpResolver() {
            return new WebhookClientIpResolver("", 16, 2_048);
        }

        @Bean
        CommonBillingService commonBillingService() {
            return mock(CommonBillingService.class);
        }

        @Bean
        PublicCommonInvoiceController publicCommonInvoiceController(CommonBillingService commonBillingService) {
            return new PublicCommonInvoiceController(commonBillingService);
        }

        @Bean
        ReviewCheckPublicProbe reviewCheckPublicProbe() {
            return new ReviewCheckPublicProbe();
        }
    }

    @RestController
    static class ReviewCheckPublicProbe {

        @GetMapping("/api/review-check/{orderDetailId}")
        Map<String, String> get(@PathVariable String orderDetailId, Authentication authentication) {
            return Map.of(
                    "orderDetailId", orderDetailId,
                    "actor", authentication == null ? "anonymous" : authentication.getName()
            );
        }

        @PutMapping({
                "/api/review-check/{orderDetailId}",
                "/api/review-check/{orderDetailId}/reviews/{reviewId}/text",
                "/api/review-check/{orderDetailId}/reviews/{reviewId}/answer"
        })
        void save() {
        }

        @PostMapping({
                "/api/review-check/{orderDetailId}/correction",
                "/api/review-check/{orderDetailId}/approve"
        })
        void act() {
        }

        @GetMapping("/api/review-capability")
        Map<String, String> secureGet() {
            return Map.of("status", "ok");
        }

        @PutMapping("/api/review-capability/reviews/{reviewId}/text")
        void secureSave() {
        }

        @PostMapping("/api/review-capability/approve")
        void secureAct() {
        }

        @PostMapping({
                "/api/manager/orders/{orderId}/review-check-capabilities",
                "/api/manager/orders/{orderId}/review-check-capabilities/{capabilityId}/rotate"
        })
        void manageSecureLink() {
        }

        @GetMapping("/ws/logs")
        Map<String, String> liveLogsHandshake() {
            return Map.of("status", "ok");
        }
    }
}
