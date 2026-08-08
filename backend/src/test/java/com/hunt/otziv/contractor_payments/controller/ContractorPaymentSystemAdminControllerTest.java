package com.hunt.otziv.contractor_payments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemStatusResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentSystemAdminService;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ContractorPaymentSystemAdminControllerTest {

    @Test
    void statusIsAvailableToAdminAndOwnerButNotManager() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {
            ContractorPaymentSystemAdminController controller =
                    context.getBean(ContractorPaymentSystemAdminController.class);
            ContractorPaymentSystemAdminService service = context.getBean(ContractorPaymentSystemAdminService.class);
            ContractorPaymentSystemStatusResponse expected = status();
            when(service.status()).thenReturn(expected);

            authenticate("ADMIN");
            assertSame(expected, controller.status());
            authenticate("OWNER");
            assertSame(expected, controller.status());
            authenticate("MANAGER");
            assertThrows(AccessDeniedException.class, controller::status);

            verify(service, times(2)).status();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void onlyOwnerCanActivateAccountingOrChangeRouting() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {
            ContractorPaymentSystemAdminController controller =
                    context.getBean(ContractorPaymentSystemAdminController.class);
            ContractorPaymentSystemAdminService service = context.getBean(ContractorPaymentSystemAdminService.class);
            ContractorPaymentSystemStatusResponse expected = status();
            when(service.status()).thenReturn(expected);
            ContractorPaymentSystemActivationRequest activation = activation();
            ContractorPaymentRoutingCommandRequest routing = routing();

            authenticate("ADMIN");
            assertThrows(AccessDeniedException.class, () -> controller.activate(activation));
            assertThrows(AccessDeniedException.class, () -> controller.updateRouting(routing));

            authenticate("OWNER");
            assertSame(expected, controller.activate(activation));
            assertSame(expected, controller.updateRouting(routing));

            verify(service).activate(activation);
            verify(service).updateRouting(routing);
            verify(service, times(2)).status();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void endpointAnnotationsRetainReadAndMutationRoleBoundary() throws Exception {
        Method status = ContractorPaymentSystemAdminController.class.getMethod("status");
        Method activate = ContractorPaymentSystemAdminController.class.getMethod(
                "activate",
                ContractorPaymentSystemActivationRequest.class
        );
        Method routing = ContractorPaymentSystemAdminController.class.getMethod(
                "updateRouting",
                ContractorPaymentRoutingCommandRequest.class
        );

        assertThat(status.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'OWNER')");
        assertThat(activate.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('OWNER')");
        assertThat(routing.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('OWNER')");
        assertThat(activate.getParameterAnnotations()[0])
                .anyMatch(annotation -> annotation.annotationType() == Valid.class);
        assertThat(routing.getParameterAnnotations()[0])
                .anyMatch(annotation -> annotation.annotationType() == Valid.class);
    }

    @Test
    void activationAndRoutingRequestsRejectMissingBlankAndOversizedFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            String tooLongConfirmation = "x".repeat(81);
            String tooLongReason = "x".repeat(501);

            Set<String> activationViolations = validator.validate(
                    new ContractorPaymentSystemActivationRequest(
                            null,
                            tooLongConfirmation,
                            " ",
                            null
                    )
            ).stream().map(value -> value.getPropertyPath().toString()).collect(Collectors.toSet());
            Set<String> routingViolations = validator.validate(
                    new ContractorPaymentRoutingCommandRequest(
                            null,
                            " ",
                            tooLongReason,
                            null
                    )
            ).stream().map(value -> value.getPropertyPath().toString()).collect(Collectors.toSet());

            assertThat(activationViolations)
                    .contains("attributionStartDate", "confirmation", "reason", "expectedRevision");
            assertThat(routingViolations)
                    .contains("enabled", "confirmation", "reason", "expectedRevision");
            assertThat(validator.validate(activation())).isEmpty();
            assertThat(validator.validate(routing())).isEmpty();
        }
    }

    private ContractorPaymentSystemActivationRequest activation() {
        return new ContractorPaymentSystemActivationRequest(
                LocalDate.of(2026, 8, 1),
                ContractorPaymentSystemAdminService.ACTIVATE_CONFIRMATION,
                "Сверка выполнена",
                0L
        );
    }

    private ContractorPaymentRoutingCommandRequest routing() {
        return new ContractorPaymentRoutingCommandRequest(
                true,
                ContractorPaymentSystemAdminService.ENABLE_ROUTING_CONFIRMATION,
                "Окно запуска согласовано",
                0L
        );
    }

    private ContractorPaymentSystemStatusResponse status() {
        return new ContractorPaymentSystemStatusResponse(
                "LEGACY",
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                true,
                List.of(),
                null,
                0L,
                true,
                true
        );
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        role.toLowerCase(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {

        @Bean
        ContractorPaymentSystemAdminService contractorPaymentSystemAdminService() {
            return mock(ContractorPaymentSystemAdminService.class);
        }

        @Bean
        ContractorPaymentSystemAdminController contractorPaymentSystemAdminController(
                ContractorPaymentSystemAdminService service
        ) {
            return new ContractorPaymentSystemAdminController(service);
        }
    }
}
