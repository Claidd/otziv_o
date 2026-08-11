package com.hunt.otziv.u_users.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.LegacyUserMigrationRequest;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.UpdateUserAssignmentsRequest;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.ImageService;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.MarketologService;
import com.hunt.otziv.u_users.service.OperatorService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class KeycloakUserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private KeycloakAdminClient keycloakAdminClient;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OperatorService operatorService;
    @Mock
    private ManagerService managerService;
    @Mock
    private WorkerService workerService;
    @Mock
    private MarketologService marketologService;
    @Mock
    private ImageService imageService;
    @Mock
    private TelegramGroupLinkService telegramGroupLinkService;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private UserAuthEpochService authEpochService;
    @Mock
    private ContractorPaymentProfileService contractorPaymentProfileService;

    @InjectMocks
    private KeycloakUserProvisioningService service;

    @Test
    void adminListUsesBulkManagersAndShadowImageIdWithoutPerUserLookups() {
        User managerUser = User.builder()
                .id(11L)
                .username("manager")
                .roles(new HashSet<>(Set.of(role("ROLE_MANAGER"))))
                .imageId(99L)
                .active(true)
                .build();
        User workerUser = User.builder()
                .id(12L)
                .username("worker")
                .roles(new HashSet<>(Set.of(role("ROLE_WORKER"))))
                .active(true)
                .build();
        Manager manager = Manager.builder()
                .id(7L)
                .user(managerUser)
                .auditTelegramGroupUrl("https://t.me/audit_group")
                .build();
        when(userRepository.findAllForAdminList()).thenReturn(List.of(managerUser, workerUser));
        when(managerService.getManagersByUserIdsForAdminList(Set.of(11L))).thenReturn(List.of(manager));

        var response = service.getUsers();

        assertEquals(2, response.size());
        assertEquals(99L, response.getFirst().imageId());
        assertEquals("https://t.me/audit_group", response.getFirst().managerAuditChatUrl());
        verify(managerService).getManagersByUserIdsForAdminList(Set.of(11L));
        verify(managerService, never()).getManagerByUserId(any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyMigrationRejectsAlreadyLinkedAccountWithoutTouchingKeycloak() {
        User user = User.builder()
                .id(1L)
                .username("already-linked")
                .password("legacy-hash")
                .keycloakId("kc-existing")
                .active(true)
                .roles(new HashSet<>(Set.of(role("ROLE_CLIENT"))))
                .build();
        LegacyUserMigrationRequest request = legacyMigrationRequest("already-linked", "OldPass123");
        when(userRepository.lockByUsername("already-linked")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "legacy-hash")).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertEquals(401, error.getStatusCode().value());
        assertEquals("Invalid legacy username or password", error.getReason());
        assertEquals("legacy-hash", user.getPassword());
        verify(passwordEncoder).matches("OldPass123", "legacy-hash");
        verify(keycloakAdminClient, never()).resetPassword(anyString(), anyString(), anyBoolean());
        verify(keycloakAdminClient, never()).assignRealmRoles(anyString(), any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void legacyMigrationUsesGenericUnauthorizedAndDummyHashForUnknownUser() {
        LegacyUserMigrationRequest request = legacyMigrationRequest("missing-user", "OldPass123");
        when(userRepository.lockByUsername("missing-user")).thenReturn(Optional.empty());
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertGenericLegacyUnauthorized(error);
        verify(passwordEncoder).matches(eq("OldPass123"), hashCaptor.capture());
        assertTrue(hashCaptor.getValue().startsWith("$2a$10$"));
        verify(keycloakAdminClient, never()).createUser(any());
    }

    @Test
    void legacyMigrationUsesGenericUnauthorizedForInactiveUserAfterPasswordWork() {
        User user = legacyUser(9L, "inactive-user", "legacy-hash", false, null);
        LegacyUserMigrationRequest request = legacyMigrationRequest("inactive-user", "OldPass123");
        when(userRepository.lockByUsername("inactive-user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "legacy-hash")).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertGenericLegacyUnauthorized(error);
        verify(passwordEncoder).matches("OldPass123", "legacy-hash");
        verify(keycloakAdminClient, never()).createUser(any());
    }

    @Test
    void legacyMigrationUsesDummyHashWhenLegacyHashWasCleared() {
        User user = legacyUser(10L, "cleared-user", null, true, "kc-existing");
        LegacyUserMigrationRequest request = legacyMigrationRequest("cleared-user", "OldPass123");
        when(userRepository.lockByUsername("cleared-user")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertGenericLegacyUnauthorized(error);
        verify(passwordEncoder).matches(eq("OldPass123"), hashCaptor.capture());
        assertTrue(hashCaptor.getValue().startsWith("$2a$10$"));
        verify(keycloakAdminClient, never()).resetPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void legacyMigrationUsesGenericUnauthorizedForWrongPassword() {
        User user = legacyUser(11L, "legacy-user", "legacy-hash", true, null);
        LegacyUserMigrationRequest request = legacyMigrationRequest("legacy-user", "WrongPass123");
        when(userRepository.lockByUsername("legacy-user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass123", "legacy-hash")).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertGenericLegacyUnauthorized(error);
        verify(keycloakAdminClient, never()).createUser(any());
    }

    @Test
    void successfulLegacyMigrationClearsLegacyPasswordHash() {
        User user = User.builder()
                .id(2L)
                .username("legacy-user")
                .password("legacy-hash")
                .email("legacy@example.com")
                .active(true)
                .roles(new HashSet<>(Set.of(role("ROLE_CLIENT"))))
                .build();
        LegacyUserMigrationRequest request = legacyMigrationRequest("legacy-user", "OldPass123");
        when(userRepository.lockByUsername("legacy-user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "legacy-hash")).thenReturn(true);
        when(keycloakAdminClient.createUser(any(CreateKeycloakUserRequest.class))).thenReturn("kc-new");
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        service.migrateLegacyUser(request);

        assertNull(user.getPassword());
        assertEquals("kc-new", user.getKeycloakId());
        verify(keycloakAdminClient).resetPassword("kc-new", "OldPass123", false);
        verify(keycloakAdminClient).assignRealmRoles("kc-new", Set.of("CLIENT"));
        verify(authEpochService).passwordChanged(user);
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void legacyMigrationConflictNeverTakesOverExistingKeycloakAccount() {
        User user = legacyUser(12L, "legacy-conflict", "legacy-hash", true, null);
        LegacyUserMigrationRequest request = legacyMigrationRequest("legacy-conflict", "OldPass123");
        when(userRepository.lockByUsername("legacy-conflict")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123", "legacy-hash")).thenReturn(true);
        when(keycloakAdminClient.createUser(any(CreateKeycloakUserRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "remote detail"));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyUser(request)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(
                "Legacy migration conflicts with an existing identity; manual repair is required.",
                error.getReason()
        );
        assertEquals("legacy-hash", user.getPassword());
        assertNull(user.getKeycloakId());
        verify(keycloakAdminClient, never()).findUserIdByUsername(anyString());
        verify(keycloakAdminClient, never()).resetPassword(anyString(), anyString(), anyBoolean());
        verify(keycloakAdminClient, never()).assignRealmRoles(anyString(), any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(authEpochService, never()).passwordChanged(any(User.class));
    }

    @Test
    void ownerCannotCreateUserWithAdminRole() {
        authenticateAs("OWNER");
        CreateKeycloakUserRequest request = createRequest("new-admin", "aDmIn");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createUser(request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(keycloakAdminClient, never()).createUser(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void ownerCannotCreateAnotherOwnerByDefault() {
        authenticateAs("OWNER");
        CreateKeycloakUserRequest request = createRequest("new-owner", "OWNER");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createUser(request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(keycloakAdminClient, never()).createUser(any());
    }

    @Test
    void adminCanCreateUserWithAdminRole() {
        authenticateAs("ADMIN");
        Role adminRole = role("ROLE_ADMIN");
        CreateKeycloakUserRequest request = createRequest("new-admin", "role_admin");
        when(userRepository.findByUsername("new-admin")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(keycloakAdminClient.createUser(request)).thenReturn("kc-new-admin");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createUser(request);

        verify(keycloakAdminClient).assignRealmRoles("kc-new-admin", Set.of("ADMIN"));
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void ownerCannotAssignAdminRole() {
        authenticateAs("OWNER");
        User user = userWithRole(3L, "staff", "ROLE_CLIENT", null);
        UpdateKeycloakUserRequest request = updateRequest("staff", true, "AdMiN");
        when(userRepository.lockById(3L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUser(3L, request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(roleRepository, never()).findByName("ROLE_ADMIN");
        verify(userRepository, never()).flush();
    }

    @Test
    void ownerCannotModifyExistingAdmin() {
        authenticateAs("OWNER");
        User user = userWithRole(4L, "admin-user", "ROLE_ADMIN", "kc-admin");
        UpdateKeycloakUserRequest request = updateRequest("admin-user", true, "ADMIN");
        request.setFio("Changed by owner");
        when(userRepository.lockById(4L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUser(4L, request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(keycloakAdminClient, never()).updateUser(anyString(), anyString(), any());
        verify(userRepository, never()).flush();
    }

    @Test
    void ownerCannotModifyExistingOwnerByDefault() {
        authenticateAs("OWNER");
        User user = userWithRole(44L, "other-owner", "ROLE_OWNER", "kc-owner");
        UpdateKeycloakUserRequest request = updateRequest("other-owner", true, "OWNER");
        when(userRepository.lockById(44L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUser(44L, request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(keycloakAdminClient, never()).updateUser(anyString(), anyString(), any());
        verify(userRepository, never()).flush();
    }

    @Test
    void adminCanModifyExistingAdmin() {
        authenticateAs("ADMIN");
        Role adminRole = role("ROLE_ADMIN");
        User user = userWithRole(5L, "admin-user", "ROLE_ADMIN", null);
        UpdateKeycloakUserRequest request = updateRequest("admin-user", true, "ADMIN");
        request.setFio("Changed by admin");
        when(userRepository.lockById(5L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));

        service.updateUser(5L, request);

        assertEquals("Changed by admin", user.getFio());
        verify(userRepository).flush();
    }

    @Test
    void ownerCannotResetAdminPassword() {
        authenticateAs("OWNER");
        User user = userWithRole(6L, "admin-user", "ROLE_ADMIN", "kc-admin");
        ChangeKeycloakPasswordRequest request = passwordRequest("NewPass123");
        when(userRepository.lockById(6L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.changePassword(6L, request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(keycloakAdminClient, never()).resetPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void ownerCannotResetAdminPersonalTelegramLink() {
        authenticateAs("OWNER");
        User user = userWithRole(12L, "admin-user", "ROLE_ADMIN", "kc-admin");
        user.setTelegramChatId(12345L);
        when(userRepository.findById(12L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.resetPersonalTelegramLink(12L)
        );

        assertEquals(403, error.getStatusCode().value());
        assertEquals(12345L, user.getTelegramChatId());
        verify(userRepository, never()).flush();
    }

    @Test
    void ownerCannotUpdateAdminPhoto() throws IOException {
        authenticateAs("OWNER");
        User user = userWithRole(13L, "admin-user", "ROLE_ADMIN", "kc-admin");
        MultipartFile photo = mock(MultipartFile.class);
        when(userRepository.findById(13L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUserPhoto(13L, photo)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(imageService, never()).saveCompressedProfileImage(any());
        verify(userRepository, never()).flush();
    }

    @Test
    void ownerCannotUpdateAdminAssignments() {
        authenticateAs("OWNER");
        User user = userWithRole(14L, "admin-user", "ROLE_ADMIN", "kc-admin");
        UpdateUserAssignmentsRequest request = new UpdateUserAssignmentsRequest();
        request.setManagerIds(Set.of(99L));
        when(userRepository.lockById(14L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdWithAssignments(14L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUserAssignments(14L, request)
        );

        assertEquals(403, error.getStatusCode().value());
        verify(managerService, never()).getManagerById(any());
        verify(userRepository, never()).flush();
    }

    @Test
    void adminCanResetAdminPassword() {
        authenticateAs("ADMIN");
        User user = userWithRole(7L, "admin-user", "ROLE_ADMIN", "kc-admin");
        ChangeKeycloakPasswordRequest request = passwordRequest("NewPass123");
        when(userRepository.lockById(7L)).thenReturn(Optional.of(user));

        service.changePassword(7L, request);

        verify(keycloakAdminClient).resetPassword("kc-admin", "NewPass123", false);
        verify(keycloakAdminClient).logoutUserSessions("kc-admin");
        verify(authEpochService).passwordChanged(user);
    }

    @Test
    void deleteDeactivatesUserWithoutDeletingBusinessAssignments() {
        User user = userWithRole(8L, "staff-user", "ROLE_CLIENT", "kc-staff");
        when(userRepository.lockById(8L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdWithAssignments(8L)).thenReturn(Optional.of(user));

        service.deleteUser(8L);

        assertFalse(user.isActive());
        InOrder repositoryLoadOrder = inOrder(userRepository);
        repositoryLoadOrder.verify(userRepository).lockById(8L);
        repositoryLoadOrder.verify(userRepository).findByIdWithAssignments(8L);
        verify(authEpochService).deactivated(user);
        ArgumentCaptor<UpdateKeycloakUserRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateKeycloakUserRequest.class);
        verify(keycloakAdminClient).updateUser(eq("kc-staff"), eq("staff-user"), requestCaptor.capture());
        assertFalse(requestCaptor.getValue().isEnabled());
        verify(keycloakAdminClient).logoutUserSessions("kc-staff");
        verify(keycloakAdminClient, never()).deleteUserStrict(anyString());
        verify(userRepository, never()).delete(any(User.class));
        verify(managerService, never()).deleteManager(user);
        verify(workerService, never()).deleteWorker(user);
        verify(operatorService, never()).deleteOperator(user);
        verify(marketologService, never()).deleteMarketolog(user);
        verify(userRepository, times(2)).flush();
    }

    @Test
    void repeatedSoftDeleteIsSideEffectFree() {
        User user = userWithRole(81L, "inactive-staff", "ROLE_CLIENT", "kc-inactive");
        user.setActive(false);
        user.setAuthEpoch(7L);
        LocalDateTime deactivatedAt = LocalDateTime.of(2026, 7, 1, 12, 0);
        user.setDeactivatedAt(deactivatedAt);
        user.setDeactivatedByUserId(5L);
        user.setDeactivationReason("USER_DEACTIVATED");
        when(userRepository.lockById(81L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdWithAssignments(81L)).thenReturn(Optional.of(user));

        service.deleteUser(81L);

        assertEquals(7L, user.getAuthEpoch());
        assertEquals(deactivatedAt, user.getDeactivatedAt());
        assertEquals(5L, user.getDeactivatedByUserId());
        assertEquals("USER_DEACTIVATED", user.getDeactivationReason());
        verify(authEpochService, never()).deactivated(user);
        verify(keycloakAdminClient, never()).updateUser(anyString(), anyString(), any());
        verify(keycloakAdminClient, never()).logoutUserSessions(anyString());
        verify(userRepository, never()).flush();
    }

    @Test
    void inactiveAdminStillCannotBeDeleted() {
        User user = userWithRole(82L, "inactive-admin", "ROLE_ADMIN", "kc-admin");
        user.setActive(false);
        when(userRepository.lockById(82L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdWithAssignments(82L)).thenReturn(Optional.of(user));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteUser(82L)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(authEpochService, never()).deactivated(user);
        verify(keycloakAdminClient, never()).updateUser(anyString(), anyString(), any());
        verify(userRepository, never()).flush();
    }

    @Test
    void changePasswordLogsOutUserSessionsAfterPasswordReset() {
        User user = User.builder()
                .id(42L)
                .keycloakId("keycloak-user-42")
                .build();
        ChangeKeycloakPasswordRequest request = new ChangeKeycloakPasswordRequest();
        request.setPassword("NewPass123");

        when(userRepository.lockById(42L)).thenReturn(Optional.of(user));

        service.changePassword(42L, request);

        verify(userRepository).lockById(42L);
        InOrder order = inOrder(keycloakAdminClient);
        order.verify(keycloakAdminClient).resetPassword("keycloak-user-42", "NewPass123", false);
        order.verify(keycloakAdminClient).logoutUserSessions("keycloak-user-42");
        verify(authEpochService).passwordChanged(user);
    }

    @Test
    void updateUserAllowsLocalAccountWithoutKeycloak() {
        Role clientRole = new Role();
        clientRole.setName("ROLE_CLIENT");
        User user = User.builder()
                .id(7L)
                .username("old-login")
                .email("old@example.com")
                .active(true)
                .roles(new HashSet<>(Set.of(clientRole)))
                .build();
        UpdateKeycloakUserRequest request = new UpdateKeycloakUserRequest();
        request.setUsername("new-login");
        request.setEmail("new@example.com");
        request.setFio("Новое имя");
        request.setEnabled(true);
        request.setRoles(Set.of("CLIENT"));

        when(userRepository.lockById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("new-login")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_CLIENT")).thenReturn(Optional.of(clientRole));

        service.updateUser(7L, request);

        assertEquals("new-login", user.getUsername());
        assertEquals("new@example.com", user.getEmail());
        assertEquals("Новое имя", user.getFio());
        verify(keycloakAdminClient, never()).updateUser(anyString(), anyString(), any());
        verify(userRepository).flush();
    }

    @Test
    void staleKeycloakBindingFailsClosedWhenOldUsernameCannotRepairIt() {
        Role clientRole = role("ROLE_CLIENT");
        User user = userWithRole(72L, "old-login", "ROLE_CLIENT", "stale-keycloak-id");
        user.setAuthProvider("KEYCLOAK");
        UpdateKeycloakUserRequest request = updateRequest("new-login", true, "CLIENT");
        request.setEmail("new@example.com");
        when(userRepository.lockById(72L)).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("new-login")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_CLIENT")).thenReturn(Optional.of(clientRole));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "remote detail"))
                .when(keycloakAdminClient)
                .updateUser("stale-keycloak-id", "new-login", request);
        when(keycloakAdminClient.findUserIdByUsername("old-login")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateUser(72L, request)
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(
                "Keycloak account binding is stale; manual repair is required.",
                error.getReason()
        );
        assertEquals("stale-keycloak-id", user.getKeycloakId());
        assertEquals("KEYCLOAK", user.getAuthProvider());
        verify(keycloakAdminClient, never()).getAssignedRealmRoleNames(anyString());
        verify(keycloakAdminClient, never()).logoutUserSessions(anyString());
    }

    @Test
    void sessionLogoutFailureDoesNotRollbackSynchronizedRoleMutation() {
        Role workerRole = role("ROLE_WORKER");
        Role clientRole = role("ROLE_CLIENT");
        User user = User.builder()
                .id(73L)
                .username("session-user")
                .keycloakId("kc-session-user")
                .active(true)
                .roles(new HashSet<>(Set.of(workerRole)))
                .build();
        UpdateKeycloakUserRequest request = updateRequest("session-user", true, "CLIENT");
        when(userRepository.lockById(73L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_CLIENT")).thenReturn(Optional.of(clientRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-session-user"))
                .thenReturn(Set.of("WORKER"));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sensitive upstream detail"))
                .when(keycloakAdminClient)
                .logoutUserSessions("kc-session-user");

        assertDoesNotThrow(() -> service.updateUser(73L, request));

        assertEquals(Set.of(clientRole), new HashSet<>(user.getRoles()));
        verify(authEpochService).securityRolesChanged(user);
        verify(keycloakAdminClient).removeRealmRoles("kc-session-user", Set.of("WORKER"));
        verify(keycloakAdminClient).assignRealmRoles("kc-session-user", Set.of("CLIENT"));
        verify(keycloakAdminClient).logoutUserSessions("kc-session-user");
        verify(userRepository, times(2)).flush();
    }

    @Test
    void simultaneousDeactivationAndRoleChangeRotatesEpochOnlyOnce() {
        Role clientRole = role("ROLE_CLIENT");
        Role workerRole = role("ROLE_WORKER");
        User user = User.builder()
                .id(71L)
                .username("local-client")
                .active(true)
                .roles(new HashSet<>(Set.of(clientRole)))
                .build();
        UpdateKeycloakUserRequest request = updateRequest("local-client", false, "WORKER");
        when(userRepository.lockById(71L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_WORKER")).thenReturn(Optional.of(workerRole));

        service.updateUser(71L, request);

        verify(authEpochService).deactivated(user);
        verify(authEpochService, never()).securityRolesChanged(user);
        verify(authEpochService, never()).reactivated(user);
    }

    @Test
    void resetPersonalTelegramLinkKeepsWorkerGroupBinding() {
        User user = User.builder()
                .id(7L)
                .username("transferred-worker")
                .telegramChatId(123456L)
                .workerTelegramGroupChatId(-100777L)
                .active(true)
                .roles(new HashSet<>())
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        var response = service.resetPersonalTelegramLink(7L);

        assertEquals(null, user.getTelegramChatId());
        assertEquals(-100777L, user.getWorkerTelegramGroupChatId());
        assertFalse(response.personalTelegramLinked());
        assertEquals(-100777L, response.workerTelegramGroupChatId());
        verify(userRepository).flush();
    }

    @Test
    void changingManagerAuditGroupUrlResetsChatIdAndReturnsInviteUrl() {
        Role managerRole = role("ROLE_MANAGER");
        User user = User.builder()
                .id(17L)
                .username("lika")
                .active(true)
                .roles(new HashSet<>(Set.of(managerRole)))
                .build();
        Manager manager = Manager.builder()
                .id(9L)
                .user(user)
                .auditTelegramGroupUrl("https://t.me/old_group")
                .auditTelegramGroupChatId(-100111L)
                .build();
        UpdateKeycloakUserRequest request = updateRequest("lika", true, "MANAGER");
        request.setManagerAuditChatUrl("https://t.me/new_group");
        when(userRepository.lockById(17L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_MANAGER")).thenReturn(Optional.of(managerRole));
        when(managerService.getManagerByUserId(17L)).thenReturn(manager);
        when(telegramGroupLinkService.buildManagerAuditInviteUrl(manager))
                .thenReturn("https://t.me/O_Company_Bot?startgroup=m9_signed");

        var response = service.updateUser(17L, request);

        assertEquals("https://t.me/new_group", manager.getAuditTelegramGroupUrl());
        assertEquals(null, manager.getAuditTelegramGroupChatId());
        assertEquals("https://t.me/O_Company_Bot?startgroup=m9_signed",
                response.managerAuditTelegramBotInviteUrl());
        verify(managerService).save(manager);
    }

    @Test
    void changingWorkerRolePreservesHistoricalWorkerProfile() {
        Role workerRole = role("ROLE_WORKER");
        Role clientRole = role("ROLE_CLIENT");
        User user = User.builder()
                .id(8L)
                .username("worker-history")
                .keycloakId("kc-worker-history")
                .active(true)
                .roles(new HashSet<>(Set.of(workerRole)))
                .build();
        UpdateKeycloakUserRequest request = updateRequest("worker-history", true, "CLIENT");

        when(userRepository.lockById(8L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_CLIENT")).thenReturn(Optional.of(clientRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-worker-history")).thenReturn(Set.of("worker"));

        service.updateUser(8L, request);

        verify(userRepository).lockById(8L);
        verify(workerService, never()).deleteWorker(user);
        verify(authEpochService).securityRolesChanged(user);
        verify(keycloakAdminClient).removeRealmRoles("kc-worker-history", Set.of("worker"));
        verify(keycloakAdminClient).assignRealmRoles("kc-worker-history", Set.of("CLIENT"));
    }

    @Test
    void dismissingWorkerRepairsMismatchedKeycloakRolesAfterLocalFlush() {
        Role workerRole = role("ROLE_WORKER");
        User user = User.builder()
                .id(9L)
                .username("dismissed-worker")
                .keycloakId("kc-dismissed-worker")
                .active(true)
                .roles(new HashSet<>(Set.of(workerRole)))
                .build();
        UpdateKeycloakUserRequest request = updateRequest("dismissed-worker", false, "WORKER");

        when(userRepository.lockById(9L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_WORKER")).thenReturn(Optional.of(workerRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-dismissed-worker"))
                .thenReturn(Set.of("CLIENT", "default-roles-otziv"));

        service.updateUser(9L, request);

        assertFalse(user.isActive());
        verify(authEpochService).deactivated(user);
        verify(authEpochService, never()).securityRolesChanged(user);
        InOrder order = inOrder(userRepository, keycloakAdminClient);
        order.verify(userRepository).flush();
        order.verify(keycloakAdminClient).updateUser("kc-dismissed-worker", "dismissed-worker", request);
        order.verify(keycloakAdminClient).getAssignedRealmRoleNames("kc-dismissed-worker");
        order.verify(keycloakAdminClient).removeRealmRoles("kc-dismissed-worker", Set.of("CLIENT"));
        order.verify(keycloakAdminClient).assignRealmRoles("kc-dismissed-worker", Set.of("WORKER"));
        order.verify(userRepository).flush();
    }

    @Test
    void inactiveWorkerCanBeReturnedToStaff() {
        Role workerRole = role("ROLE_WORKER");
        User user = User.builder()
                .id(10L)
                .username("returning-worker")
                .keycloakId("kc-returning-worker")
                .active(false)
                .roles(new HashSet<>(Set.of(workerRole)))
                .build();
        UpdateKeycloakUserRequest request = updateRequest("returning-worker", true, "WORKER");

        when(userRepository.lockById(10L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_WORKER")).thenReturn(Optional.of(workerRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-returning-worker")).thenReturn(Set.of("WORKER"));

        service.updateUser(10L, request);

        assertTrue(user.isActive());
        verify(authEpochService).reactivated(user);
        verify(keycloakAdminClient).updateUser("kc-returning-worker", "returning-worker", request);
        verify(workerService, never()).saveNewWorker(user);
    }

    private Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private UpdateKeycloakUserRequest updateRequest(String username, boolean enabled, String role) {
        UpdateKeycloakUserRequest request = new UpdateKeycloakUserRequest();
        request.setUsername(username);
        request.setEnabled(enabled);
        request.setRoles(Set.of(role));
        return request;
    }

    private CreateKeycloakUserRequest createRequest(String username, String role) {
        CreateKeycloakUserRequest request = new CreateKeycloakUserRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("TempPass123");
        request.setEnabled(true);
        request.setRoles(Set.of(role));
        return request;
    }

    private LegacyUserMigrationRequest legacyMigrationRequest(String username, String password) {
        LegacyUserMigrationRequest request = new LegacyUserMigrationRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private ChangeKeycloakPasswordRequest passwordRequest(String password) {
        ChangeKeycloakPasswordRequest request = new ChangeKeycloakPasswordRequest();
        request.setPassword(password);
        return request;
    }

    private User userWithRole(Long id, String username, String roleName, String keycloakId) {
        return User.builder()
                .id(id)
                .username(username)
                .keycloakId(keycloakId)
                .active(true)
                .roles(new HashSet<>(Set.of(role(roleName))))
                .build();
    }

    private User legacyUser(Long id, String username, String password, boolean active, String keycloakId) {
        return User.builder()
                .id(id)
                .username(username)
                .password(password)
                .keycloakId(keycloakId)
                .active(active)
                .roles(new HashSet<>(Set.of(role("ROLE_CLIENT"))))
                .build();
    }

    private void assertGenericLegacyUnauthorized(ResponseStatusException error) {
        assertEquals(401, error.getStatusCode().value());
        assertEquals("Invalid legacy username or password", error.getReason());
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
