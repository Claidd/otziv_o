package com.hunt.otziv.manager_control.controller;

import com.hunt.otziv.manager_control.service.ManagerAutomationFailureService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.manager_control.service.ManagerControlAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDeliveryReviewAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean com.hunt.otziv.client_messages.api.ClientContactHealth contacts() {
            return mock(com.hunt.otziv.client_messages.api.ClientContactHealth.class);
        }
        @Bean ManagerControlAccessPolicy access() { return mock(ManagerControlAccessPolicy.class); }
        @Bean ManagerAutomationFailureService failures() { return mock(ManagerAutomationFailureService.class); }
        @Bean AdminDeliveryReviewController controller(ManagerControlAccessPolicy access, ManagerAutomationFailureService failures) {
            return new AdminDeliveryReviewController(access, failures);
        }
    }
    @Test void onlyAdminAndOwnerCanReadHistoricalReviewQueue() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(AdminDeliveryReviewController.class);
            var access = context.getBean(ManagerControlAccessPolicy.class);
            var failures = context.getBean(ManagerAutomationFailureService.class);
            var manager = Manager.builder().id(2L).build();
            when(access.visibleManagers(any(), any())).thenReturn(List.of(manager));
            var review = new ManagerAutomationFailureService.SystemDeliveryReview(1238696L,1732L,"Single",2L,"Предложение заказа",null,"/orders");
            when(failures.systemDeliveryReviews(manager)).thenReturn(List.of(review));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("manager","","ROLE_MANAGER"));
            assertThatThrownBy(() -> controller.list(() -> "fixture", SecurityContextHolder.getContext().getAuthentication()))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(access);
            verifyNoInteractions(failures);
            for (String role : new String[]{"ROLE_ADMIN","ROLE_OWNER"}) {
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("fixture","",role));
                assertThat(controller.list(() -> "fixture", SecurityContextHolder.getContext().getAuthentication())).containsExactly(review);
            }
            verify(failures,times(2)).systemDeliveryReviews(manager);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
