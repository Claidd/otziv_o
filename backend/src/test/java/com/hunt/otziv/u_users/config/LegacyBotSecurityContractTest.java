package com.hunt.otziv.u_users.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAccessPolicy;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCheckInService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewRestrictionFilter;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.UserServiceImpl;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyBotSecurityContractTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "legacy-bot-security-contract",
                Map.of(
                        "otziv.legacy.enabled", "true",
                        "jwt.secret", "test-only-legacy-bot-secret-with-32-bytes"
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
    void workerCanReachGuardedBrowserPageButNotLegacyBotAdministration() throws Exception {
        MockHttpSession session = authenticatedSession("worker", "WORKER");

        mockMvc.perform(get("/bots/42/browser").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/bots").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
        mockMvc.perform(get("/bots/edit/42").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
        mockMvc.perform(get("/bots/bot_add").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    void adminAndOwnerRetainLegacyAdministrationAndBrowserAccess() throws Exception {
        for (String role : List.of("ADMIN", "OWNER")) {
            MockHttpSession session = authenticatedSession(role.toLowerCase(), role);

            mockMvc.perform(get("/bots").session(session))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/bots/edit/42").session(session))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/bots/42/browser").session(session))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void managerRetainsGuardedBrowserPageWithoutReceivingLegacyBotAdministration() throws Exception {
        MockHttpSession session = authenticatedSession("manager", "MANAGER");

        mockMvc.perform(get("/bots/42/browser").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/bots").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
        mockMvc.perform(get("/bots/edit/42").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    void unrelatedAuthenticatedRoleCannotReachBrowserOrBotAdministration() throws Exception {
        MockHttpSession session = authenticatedSession("operator", "OPERATOR");

        mockMvc.perform(get("/bots/42/browser").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
        mockMvc.perform(get("/bots").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    private MockHttpSession authenticatedSession(String username, String role) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication)
        );
        return session;
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
        LegacyBotProbe legacyBotProbe() {
            return new LegacyBotProbe();
        }
    }

    @RestController
    static class LegacyBotProbe {

        @GetMapping({"/bots", "/bots/bot_add"})
        String collection() {
            return "ok";
        }

        @GetMapping("/bots/edit/{botId}")
        String edit(@PathVariable long botId) {
            return "ok:" + botId;
        }

        @GetMapping("/bots/{botId}/browser")
        String browser(@PathVariable long botId) {
            return "ok:" + botId;
        }
    }
}
