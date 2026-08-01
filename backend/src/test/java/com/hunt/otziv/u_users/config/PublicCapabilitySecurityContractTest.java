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
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.UserServiceImpl;
import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void anonymousPaymentLinkCanBeReadAndInitialized() throws Exception {
        mockMvc.perform(get("/api/payments/public/payment-token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/public/payment-token/init")
                        .contentType("application/json")
                        .header("User-Agent", "contract-test")
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
                anyString(),
                eq("contract-test")
        );
    }

    @Test
    void anonymousCommonInvoiceCanBeReadAndInitialized() throws Exception {
        mockMvc.perform(get("/api/payments/public/group/invoice-token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/public/group/invoice-token/init")
                        .contentType("application/json")
                        .content(PAYMENT_REQUEST))
                .andExpect(status().isOk());

        CommonBillingService commonBillingService = context.getBean(CommonBillingService.class);
        verify(commonBillingService).publicInvoice("invoice-token");
        verify(commonBillingService).initPublicPayment(
                "invoice-token",
                "client@example.com",
                true,
                true,
                true
        );
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
                PaymentProfileService paymentProfileService
        ) {
            return new PublicPaymentController(
                    paymentLinkService,
                    properties,
                    runtimeSettingsService,
                    paymentProfileService
            );
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
        Map<String, String> get(@PathVariable String orderDetailId) {
            return Map.of("orderDetailId", orderDetailId);
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
    }
}
