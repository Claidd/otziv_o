package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.dto.AdminUserResponse;
import com.hunt.otziv.u_users.dto.AssignmentOptionsResponse;
import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.CreatedKeycloakUserResponse;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.UpdateUserAssignmentsRequest;
import com.hunt.otziv.u_users.dto.UserAssignmentsResponse;
import com.hunt.otziv.u_users.services.KeycloakUserProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class ApiAdminUserController {

    private final KeycloakUserProvisioningService userProvisioningService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public List<AdminUserResponse> getUsers() {
        return userProvisioningService.getUsers();
    }

    @GetMapping("/assignment-options")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public AssignmentOptionsResponse getAssignmentOptions() {
        return userProvisioningService.getAssignmentOptions();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public CreatedKeycloakUserResponse createUser(@Valid @RequestBody CreateKeycloakUserRequest request) {
        return userProvisioningService.createUser(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public AdminUserResponse updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateKeycloakUserRequest request
    ) {
        return userProvisioningService.updateUser(id, request);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void changePassword(
            @PathVariable Long id,
            @Valid @RequestBody ChangeKeycloakPasswordRequest request
    ) {
        userProvisioningService.changePassword(id, request);
    }

    @GetMapping("/{id}/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public UserAssignmentsResponse getUserAssignments(@PathVariable Long id) {
        return userProvisioningService.getUserAssignments(id);
    }

    @PutMapping("/{id}/assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public UserAssignmentsResponse updateUserAssignments(
            @PathVariable Long id,
            @RequestBody UpdateUserAssignmentsRequest request
    ) {
        return userProvisioningService.updateUserAssignments(id, request);
    }
}
