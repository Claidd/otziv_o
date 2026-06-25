package com.hunt.otziv.manager.services;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerAccessServiceTest {

    @Spy
    private ManagerPermissionService managerPermissionService = new ManagerPermissionService();

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private WorkerService workerService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private ManagerAccessService service;

    @Test
    void adminCanAccessExistingOrderAndCompany() {
        Authentication admin = authentication("admin", "ROLE_ADMIN");
        when(orderRepository.existsById(10L)).thenReturn(true);
        when(companyRepository.existsById(20L)).thenReturn(true);

        assertTrue(service.canAccessOrder(10L, admin));
        assertTrue(service.canAccessCompany(20L, admin));
    }

    @Test
    void ownerCanAccessOnlyEntitiesAssignedToOwnedManagers() {
        Authentication owner = authentication("owner", "ROLE_OWNER");
        when(userService.findManagersByUserName("owner")).thenReturn(Set.of(manager(7L), manager(8L)));
        when(orderRepository.existsByIdAndManager_IdIn(10L, Set.of(7L, 8L))).thenReturn(true);
        when(companyRepository.existsByIdAndManager_IdIn(20L, Set.of(7L, 8L))).thenReturn(false);

        assertTrue(service.canAccessOrder(10L, owner));
        assertFalse(service.canAccessCompany(20L, owner));
    }

    @Test
    void managerCanAccessOnlyOwnManagerEntities() {
        Authentication managerAuth = authentication("manager", "ROLE_MANAGER");
        User user = User.builder().id(3L).username("manager").build();
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerService.getManagerByUserId(3L)).thenReturn(manager(9L));
        when(orderRepository.existsByIdAndManager_IdIn(10L, Set.of(9L))).thenReturn(true);
        when(companyRepository.existsByIdAndManager_IdIn(20L, Set.of(9L))).thenReturn(false);

        assertTrue(service.canAccessOrder(10L, managerAuth));
        assertFalse(service.canAccessCompany(20L, managerAuth));
    }

    @Test
    void workerCanAccessOnlyAssignedOrdersAndNoCompanies() {
        Authentication workerAuth = authentication("worker", "ROLE_WORKER");
        User user = User.builder().id(4L).username("worker").build();
        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(4L)).thenReturn(Worker.builder().id(12L).build());
        when(orderRepository.existsByIdAndWorker_Id(10L, 12L)).thenReturn(true);

        assertTrue(service.canAccessOrder(10L, workerAuth));
        assertFalse(service.canAccessCompany(20L, workerAuth));
    }

    @Test
    void requireOrderAccessHidesForbiddenOrderAsNotFound() {
        Authentication managerAuth = authentication("manager", "ROLE_MANAGER");
        User user = User.builder().id(3L).username("manager").build();
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerService.getManagerByUserId(3L)).thenReturn(manager(9L));
        when(orderRepository.existsByIdAndManager_IdIn(10L, Set.of(9L))).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service.requireOrderAccess(10L, managerAuth));
    }

    private Manager manager(Long id) {
        return Manager.builder().id(id).build();
    }

    private Authentication authentication(String username, String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "password",
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
    }
}
