package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.keycloak.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.ImageService;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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

    @InjectMocks
    private KeycloakUserProvisioningService service;

    @Test
    void changePasswordLogsOutUserSessionsAfterPasswordReset() {
        User user = User.builder()
                .id(42L)
                .keycloakId("keycloak-user-42")
                .build();
        ChangeKeycloakPasswordRequest request = new ChangeKeycloakPasswordRequest();
        request.setPassword("NewPass123");

        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.changePassword(42L, request);

        InOrder order = inOrder(keycloakAdminClient);
        order.verify(keycloakAdminClient).resetPassword("keycloak-user-42", "NewPass123", false);
        order.verify(keycloakAdminClient).logoutUserSessions("keycloak-user-42");
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

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
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
        when(userRepository.findById(17L)).thenReturn(Optional.of(user));
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

        when(userRepository.findById(8L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_CLIENT")).thenReturn(Optional.of(clientRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-worker-history")).thenReturn(Set.of("WORKER"));

        service.updateUser(8L, request);

        verify(workerService, never()).deleteWorker(user);
        verify(keycloakAdminClient).removeRealmRoles("kc-worker-history", Set.of("WORKER"));
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

        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_WORKER")).thenReturn(Optional.of(workerRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-dismissed-worker"))
                .thenReturn(Set.of("CLIENT", "default-roles-otziv"));

        service.updateUser(9L, request);

        assertFalse(user.isActive());
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

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ROLE_WORKER")).thenReturn(Optional.of(workerRole));
        when(keycloakAdminClient.getAssignedRealmRoleNames("kc-returning-worker")).thenReturn(Set.of("WORKER"));

        service.updateUser(10L, request);

        assertTrue(user.isActive());
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
}
