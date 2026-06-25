package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.keycloak.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.repository.RoleRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.ImageService;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

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
}
