package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.LegacyUserMigrationRequest;
import com.hunt.otziv.u_users.dto.RegisterClientRequest;
import com.hunt.otziv.u_users.security.LegacyMigrationRequestGuard;
import com.hunt.otziv.u_users.service.KeycloakUserProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiAuthControllerTest {

    private final KeycloakUserProvisioningService provisioningService =
            mock(KeycloakUserProvisioningService.class);
    private final LegacyMigrationRequestGuard migrationGuard = mock(LegacyMigrationRequestGuard.class);
    private final ApiAuthController controller = new ApiAuthController(provisioningService, migrationGuard);

    @Test
    void ordinaryRegistrationDoesNotUseLegacyMigrationGuard() {
        RegisterClientRequest request = new RegisterClientRequest();

        controller.registerClient(request);

        verify(provisioningService).registerClient(request);
        verifyNoInteractions(migrationGuard);
    }

    @Test
    void migrationGuardRunsBeforeProvisioningService() {
        LegacyUserMigrationRequest request = new LegacyUserMigrationRequest();
        request.setUsername("legacy-user");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS))
                .when(migrationGuard)
                .enforce(servletRequest, "legacy-user");

        assertThrows(
                ResponseStatusException.class,
                () -> controller.migrateLegacyUser(request, servletRequest)
        );

        verify(provisioningService, never()).migrateLegacyUser(request);
    }
}
