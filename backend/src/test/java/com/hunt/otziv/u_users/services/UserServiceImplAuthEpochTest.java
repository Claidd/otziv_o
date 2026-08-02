package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.MarketologDTO;
import com.hunt.otziv.u_users.dto.OperatorDTO;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.ImageService;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplAuthEpochTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleService roleService;
    @Mock private OperatorService operatorService;
    @Mock private ManagerService managerService;
    @Mock private WorkerService workerService;
    @Mock private MarketologService marketologService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ImageRepository imageRepository;
    @Mock private ImageService imageService;
    @Mock private UserAuthEpochService authEpochService;
    @Mock private KeycloakAdminClient keycloakAdminClient;
    @InjectMocks private UserServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyProfileDeactivationUsesSameEpochRotationPath() throws Exception {
        Role workerRole = new Role();
        workerRole.setName("ROLE_WORKER");
        User user = User.builder()
                .id(44L)
                .username("legacy-worker")
                .active(true)
                .roles(new ArrayList<>(java.util.List.of(workerRole)))
                .operators(new HashSet<>())
                .managers(new HashSet<>())
                .workers(new HashSet<>())
                .marketologs(new HashSet<>())
                .build();
        RegistrationUserDTO request = RegistrationUserDTO.builder()
                .username("legacy-worker")
                .active(false)
                .operators(new HashSet<>())
                .managers(new HashSet<>())
                .workers(new HashSet<>())
                .marketologs(new HashSet<>())
                .build();
        when(userRepository.lockByUsername("legacy-worker")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("legacy-worker")).thenReturn(Optional.of(user));

        service.updateProfile(
                request,
                "ROLE_WORKER",
                OperatorDTO.builder().operatorId(0L).build(),
                ManagerDTO.builder().managerId(0L).build(),
                WorkerDTO.builder().workerId(0L).build(),
                MarketologDTO.builder().marketologId(0L).build(),
                null
        );

        verify(authEpochService).deactivated(user);
        verify(authEpochService, never()).securityRolesChanged(user);
        var repositoryOrder = org.mockito.Mockito.inOrder(userRepository);
        repositoryOrder.verify(userRepository).lockByUsername("legacy-worker");
        repositoryOrder.verify(userRepository).findByUsernameWithAssignments("legacy-worker");
        verify(userRepository).save(user);
    }

    @Test
    void legacySecurityMutationKeepsUpdateWhenSessionLogoutFails() {
        Role workerRole = role("ROLE_WORKER");
        User user = legacyUser("linked-worker", true, workerRole);
        user.setKeycloakId("kc-linked-worker");
        RegistrationUserDTO request = requestFor(user);
        request.setActive(false);
        when(userRepository.lockByUsername("linked-worker")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("linked-worker")).thenReturn(Optional.of(user));
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "sensitive upstream detail"
        )).when(keycloakAdminClient).logoutUserSessions("kc-linked-worker");

        assertDoesNotThrow(() -> updateProfile(request, "ROLE_WORKER"));

        assertFalse(user.isActive());
        verify(authEpochService).deactivated(user);
        verify(userRepository).save(user);
        verify(keycloakAdminClient).logoutUserSessions("kc-linked-worker");
    }

    @Test
    void inactiveLegacyUserDetailsAreDisabled() {
        Role workerRole = role("ROLE_WORKER");
        User user = User.builder()
                .username("inactive-worker")
                .password("password-hash")
                .active(false)
                .roles(new ArrayList<>(List.of(workerRole)))
                .build();
        when(userRepository.findByUsername("inactive-worker")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("inactive-worker");

        assertFalse(details.isEnabled());
        assertTrue(details.isAccountNonExpired());
        assertTrue(details.isAccountNonLocked());
        assertTrue(details.isCredentialsNonExpired());
    }

    @Test
    void migratedLegacyUserWithoutLocalPasswordGetsStableDummyHash() {
        User user = User.builder()
                .username("migrated-user")
                .password(null)
                .active(true)
                .roles(new ArrayList<>(List.of(role("ROLE_CLIENT"))))
                .build();
        when(userRepository.findByUsername("migrated-user")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("migrated-user");

        assertTrue(details.isEnabled());
        assertEquals(
                "$2a$10$pAtWIeKHPxl4coXbwqB0pebfkpcgJ3QhKGXItwmaBYQiKbvSWII0y",
                details.getPassword()
        );
    }

    @Test
    void legacyOwnerCannotAssignAdminWithMixedCaseRole() {
        authenticateAs("OWNER");
        User user = legacyUser("legacy-worker", true, role("ROLE_WORKER"));
        RegistrationUserDTO request = requestFor(user);
        when(userRepository.lockByUsername("legacy-worker")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("legacy-worker")).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> updateProfile(request, "role_aDmIn")
        );

        assertEquals(403, error.getStatusCode().value());
        verify(roleService, never()).getUserRole("ROLE_ADMIN");
        verify(userRepository, never()).save(user);
    }

    @Test
    void legacyOwnerCannotModifyUserWithAnyAdminRole() {
        authenticateAs("OWNER");
        User user = legacyUser(
                "legacy-admin",
                true,
                role("ROLE_WORKER"),
                role("ROLE_ADMIN")
        );
        RegistrationUserDTO request = requestFor(user);
        request.setPhoneNumber("+79990000000");
        when(userRepository.lockByUsername("legacy-admin")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("legacy-admin")).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> updateProfile(request, "ROLE_WORKER")
        );

        assertEquals(403, error.getStatusCode().value());
        assertEquals(null, user.getPhoneNumber());
        verify(userRepository, never()).save(user);
    }

    @Test
    void legacyAdminCanChangeExistingAdminRole() throws Exception {
        authenticateAs("ADMIN");
        Role workerRole = role("ROLE_WORKER");
        User user = legacyUser("legacy-admin", true, role("ROLE_ADMIN"));
        RegistrationUserDTO request = requestFor(user);
        when(userRepository.lockByUsername("legacy-admin")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("legacy-admin")).thenReturn(Optional.of(user));
        when(roleService.getUserRole("ROLE_WORKER")).thenReturn(workerRole);

        updateProfile(request, "role_worker");

        assertEquals(List.of(workerRole), user.getRoles());
        verify(authEpochService).securityRolesChanged(user);
        verify(userRepository).save(user);
    }

    @Test
    void legacySingleRoleFormPreservesExistingMultiRoleAssignment() throws Exception {
        authenticateAs("OWNER");
        Role workerRole = role("ROLE_WORKER");
        Role marketologRole = role("ROLE_MARKETOLOG");
        User user = legacyUser("multi-role", true, workerRole, marketologRole);
        RegistrationUserDTO request = requestFor(user);
        when(userRepository.lockByUsername("multi-role")).thenReturn(Optional.of(user));
        when(userRepository.findByUsernameWithAssignments("multi-role")).thenReturn(Optional.of(user));

        updateProfile(request, "ROLE_WORKER");

        assertEquals(Set.of(workerRole, marketologRole), new HashSet<>(user.getRoles()));
        verify(roleService, never()).getUserRole("ROLE_WORKER");
        verify(authEpochService, never()).securityRolesChanged(user);
        verify(userRepository, never()).save(user);
    }

    private void updateProfile(RegistrationUserDTO request, String requestedRole) throws Exception {
        service.updateProfile(
                request,
                requestedRole,
                OperatorDTO.builder().operatorId(0L).build(),
                ManagerDTO.builder().managerId(0L).build(),
                WorkerDTO.builder().workerId(0L).build(),
                MarketologDTO.builder().marketologId(0L).build(),
                null
        );
    }

    private User legacyUser(String username, boolean active, Role... roles) {
        return User.builder()
                .username(username)
                .active(active)
                .roles(new ArrayList<>(List.of(roles)))
                .operators(new HashSet<>())
                .managers(new HashSet<>())
                .workers(new HashSet<>())
                .marketologs(new HashSet<>())
                .build();
    }

    private RegistrationUserDTO requestFor(User user) {
        return RegistrationUserDTO.builder()
                .username(user.getUsername())
                .active(user.isActive())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .coefficient(user.getCoefficient())
                .operators(new HashSet<>(user.getOperators()))
                .managers(new HashSet<>(user.getManagers()))
                .workers(new HashSet<>(user.getWorkers()))
                .marketologs(new HashSet<>(user.getMarketologs()))
                .build();
    }

    private Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "actor",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
        );
    }
}
