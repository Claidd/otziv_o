package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.CreatedKeycloakUserResponse;
import com.hunt.otziv.u_users.dto.LegacyUserMigrationRequest;
import com.hunt.otziv.u_users.dto.RegisterClientRequest;
import com.hunt.otziv.u_users.security.LegacyMigrationRequestGuard;
import com.hunt.otziv.u_users.service.KeycloakUserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class ApiAuthController {

    private final KeycloakUserProvisioningService userProvisioningService;
    private final LegacyMigrationRequestGuard legacyMigrationRequestGuard;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedKeycloakUserResponse registerClient(@Valid @RequestBody RegisterClientRequest request) {
        return userProvisioningService.registerClient(request);
    }

    @PostMapping("/legacy-migration")
    public CreatedKeycloakUserResponse migrateLegacyUser(
            @Valid @RequestBody LegacyUserMigrationRequest request,
            HttpServletRequest servletRequest
    ) {
        legacyMigrationRequestGuard.enforce(servletRequest, request.getUsername());
        return userProvisioningService.migrateLegacyUser(request);
    }
}
