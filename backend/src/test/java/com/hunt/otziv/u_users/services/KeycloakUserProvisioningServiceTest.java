package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.keycloak.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
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
}
