package com.hunt.otziv.mobile_push.controller;

import com.hunt.otziv.mobile_push.dto.MobilePushTokenRevokeRequest;
import com.hunt.otziv.mobile_push.service.MobilePushTokenService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApiMobilePushTokenControllerTest {

    @Mock
    private MobilePushTokenService tokenService;

    @Test
    void revokeEndpointsDelegateOnlyTheAuthenticatedPrincipal() {
        ApiMobilePushTokenController controller = new ApiMobilePushTokenController(tokenService);
        Principal principal = () -> "worker";
        MobilePushTokenRevokeRequest request = new MobilePushTokenRevokeRequest("fcm-token");

        controller.revokeCurrent(principal, request);
        controller.revokeAll(principal);

        verify(tokenService).revokeCurrent(principal, request);
        verify(tokenService).revokeAll(principal);
    }

    @Test
    void controllerAndRoutesRetainAuthenticatedSecurityContract() throws Exception {
        PreAuthorize authorization = ApiMobilePushTokenController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(authorization);
        assertEquals("isAuthenticated()", authorization.value());

        assertEquals("/revoke", postPath("revokeCurrent", Principal.class, MobilePushTokenRevokeRequest.class));
        assertEquals("/revoke-all", postPath("revokeAll", Principal.class));
    }

    @Test
    void revokeTokenRequiresNonBlankValueWithinDatabaseColumnBound() throws Exception {
        Method accessor = MobilePushTokenRevokeRequest.class.getDeclaredMethod("token");
        assertNotNull(accessor.getAnnotation(NotBlank.class));
        Size size = accessor.getAnnotation(Size.class);
        assertNotNull(size);
        assertEquals(512, size.max());
    }

    @Test
    void methodSecurityRejectsAnonymousAndAllowsAuthenticatedRevoke() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {
            ApiMobilePushTokenController securedController = context.getBean(ApiMobilePushTokenController.class);
            MobilePushTokenService securedService = context.getBean(MobilePushTokenService.class);
            Principal principal = () -> "worker";

            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "anonymous-key",
                    "anonymous",
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
            ));
            MobilePushTokenRevokeRequest request = new MobilePushTokenRevokeRequest("fcm-token");
            assertThrows(AccessDeniedException.class, () -> securedController.revokeCurrent(principal, request));
            assertThrows(AccessDeniedException.class, () -> securedController.revokeAll(principal));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "worker",
                            "n/a",
                            java.util.List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
                    )
            );
            securedController.revokeCurrent(principal, request);
            securedController.revokeAll(principal);
            verify(securedService).revokeCurrent(principal, request);
            verify(securedService).revokeAll(principal);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String postPath(String methodName, Class<?>... argumentTypes) throws Exception {
        PostMapping mapping = ApiMobilePushTokenController.class
                .getMethod(methodName, argumentTypes)
                .getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        return Arrays.stream(mapping.value()).findFirst().orElse("");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {

        @Bean
        MobilePushTokenService mobilePushTokenService() {
            return mock(MobilePushTokenService.class);
        }

        @Bean
        ApiMobilePushTokenController apiMobilePushTokenController(MobilePushTokenService service) {
            return new ApiMobilePushTokenController(service);
        }
    }
}
