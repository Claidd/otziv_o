package com.hunt.otziv.u_users.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAccessPolicy;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCheckInService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewRestrictionFilter;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserServiceImpl;
import jakarta.servlet.Filter;
import java.time.Instant;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production URL rules for the category dictionary: managers read, add and rename categories and subcategories;
 * deleting them stays with the owner and administrators. A stub controller stands behind the real filter chain, so
 * only the rules are exercised (method-level {@code @PreAuthorize} is covered by ApiAdminDictionaryCategoryRightsTest).
 */
class CategoryDictionarySecurityContractTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("category-security",
                Map.of("otziv.legacy.enabled", "false", "jwt.secret", "test-only-category-rights-secret-32-bytes")));
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER", "MANAGER"})
    void readAddAndRenameAreOpenToManagersOwnerAndAdministrators(String role) throws Exception {
        String bearer = bearer(role);
        mvc.perform(get("/api/admin/categories").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/subcategories").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/categories").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/categories/7").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/subcategories").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/subcategories/5").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER"})
    void ownerAndAdministratorsDelete(String role) throws Exception {
        String bearer = bearer(role);
        mvc.perform(delete("/api/admin/categories/7").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/subcategories/5").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void managerCannotDelete() throws Exception {
        String bearer = bearer("MANAGER");
        mvc.perform(delete("/api/admin/categories/7").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/subcategories/5").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKER", "OPERATOR", "MARKETOLOG"})
    void otherRolesNeitherReadNorChange(String role) throws Exception {
        String bearer = bearer(role);
        mvc.perform(get("/api/admin/categories").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/categories").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/subcategories/5").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/categories/7").header("Authorization", bearer)).andExpect(status().isForbidden());
    }

    private String bearer(String role) {
        String token = role.toLowerCase() + "-category-rights-token";
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(role.toLowerCase())
                .issuedAt(now.minusSeconds(10)).expiresAt(now.plusSeconds(300))
                .claim("preferred_username", role.toLowerCase())
                .claim("realm_access", Map.of("roles", List.of(role))).build();
        when(context.getBean(JwtDecoder.class).decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }

    @RestController
    static class CategoryEndpoints {
        @GetMapping({"/api/admin/categories", "/api/admin/subcategories"})
        String read() { return "[]"; }

        @PostMapping({"/api/admin/categories", "/api/admin/subcategories"})
        String create() { return "{}"; }

        @PutMapping({"/api/admin/categories/{id}", "/api/admin/subcategories/{id}"})
        String update() { return "{}"; }

        @DeleteMapping({"/api/admin/categories/{id}", "/api/admin/subcategories/{id}"})
        String remove() { return ""; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, CategoryEndpoints.class})
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
    }
}
