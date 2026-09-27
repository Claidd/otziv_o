package com.hunt.otziv.personal_reminders.controller;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiPersonalReminderControllerTest {
    @Test
    void bulkDeleteAllowsOnlyAdminAndOwner() {
        try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
            var controller = context.getBean(ApiPersonalReminderController.class);
            var service = context.getBean(PersonalReminderService.class);
            for (String role : List.of("MANAGER", "WORKER", "OPERATOR", "USER")) {
                var auth = new UsernamePasswordAuthenticationToken("user", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
                assertThrows(AccessDeniedException.class, () -> controller.deleteAll(auth));
            }
            var anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            SecurityContextHolder.getContext().setAuthentication(anonymous);
            assertThrows(AccessDeniedException.class, () -> controller.deleteAll(anonymous));
            verifyNoInteractions(service);

            for (String role : List.of("ADMIN", "OWNER")) {
                var auth = new UsernamePasswordAuthenticationToken(role.toLowerCase(), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
                controller.deleteAll(auth);
                verify(service).deleteAll(auth);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration {
        @Bean
        PersonalReminderService personalReminderService() { return mock(PersonalReminderService.class); }

        @Bean
        ApiPersonalReminderController personalReminderController(PersonalReminderService service) {
            return new ApiPersonalReminderController(service);
        }
    }
}
