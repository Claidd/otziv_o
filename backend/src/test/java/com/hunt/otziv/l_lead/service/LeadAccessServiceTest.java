package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.MarketologService;
import com.hunt.otziv.u_users.service.OperatorService;
import com.hunt.otziv.u_users.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadAccessServiceTest {

    @Mock private LeadsRepository leadsRepository;
    @Mock private TelephoneRepository telephoneRepository;
    @Mock private UserService userService;
    @Mock private ManagerService managerService;
    @Mock private OperatorService operatorService;
    @Mock private MarketologService marketologService;

    private LeadAccessService service;

    @BeforeEach
    void setUp() {
        service = new LeadAccessService(
                leadsRepository,
                telephoneRepository,
                userService,
                managerService,
                operatorService,
                marketologService,
                new ManagerPermissionService()
        );
    }

    @Test
    void managerCannotMutateAnotherManagersLead() {
        Lead lead = Lead.builder()
                .id(17L)
                .manager(Manager.builder().id(8L).user(user(80L, "other-manager")).build())
                .build();
        when(leadsRepository.findById(17L)).thenReturn(Optional.of(lead));
        when(userService.findByUserName("manager-a")).thenReturn(Optional.of(user(10L, "manager-a")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireLeadAccess(17L, auth("manager-a", "MANAGER"))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void operatorCanMutateLeadOnlyThroughOwnTelephoneAssignment() {
        User operatorUser = user(30L, "operator-a");
        Operator operator = Operator.builder().id(3L).user(operatorUser).build();
        Telephone telephone = Telephone.builder().id(7L).telephoneOperator(operator).build();
        Lead lead = Lead.builder().id(18L).telephone(telephone).build();
        when(leadsRepository.findById(18L)).thenReturn(Optional.of(lead));
        when(userService.findByUserName("operator-a")).thenReturn(Optional.of(operatorUser));

        assertSame(lead, service.requireLeadAccess(18L, auth("operator-a", "OPERATOR")));
    }

    @Test
    void ownerWithOwnManagersCannotBindAnotherTeamsTelephone() {
        Manager ownManager = Manager.builder().id(11L).build();
        Manager otherManager = Manager.builder().id(12L).build();
        User owner = user(1L, "owner-a");
        owner.setOwnerControlViewMode("OWN_MANAGERS");
        User operatorUser = user(2L, "operator-b");
        operatorUser.setManagers(new HashSet<>(Set.of(otherManager)));
        Telephone telephone = Telephone.builder()
                .id(9L)
                .telephoneOperator(Operator.builder().id(4L).user(operatorUser).build())
                .build();
        when(telephoneRepository.findByIdWithOperator(9L)).thenReturn(Optional.of(telephone));
        when(userService.findByUserName("owner-a")).thenReturn(Optional.of(owner));
        when(userService.findManagersByUserName("owner-a")).thenReturn(Set.of(ownManager));

        assertTrue(!service.canAccessTelephone(9L, auth("owner-a", "OWNER")));
    }

    @Test
    void ownerReadScopeRestrictsOwnManagersModeToExplicitAssignments() {
        Manager manager = Manager.builder().id(11L).build();
        User owner = user(1L, "owner-a");
        owner.setOwnerControlViewMode("OWN_MANAGERS");
        when(userService.findByUserName("owner-a")).thenReturn(Optional.of(owner));
        when(userService.findManagersByUserName("owner-a")).thenReturn(Set.of(manager));

        LeadAccessService.OwnerReadScope scope = service.ownerReadScope(auth("owner-a", "OWNER"));

        assertFalse(scope.canReadAllManagers());
        assertEquals(List.of(manager), scope.managers());
    }

    @Test
    void ownerReadScopeMakesAllManagersModeUnrestricted() {
        User owner = user(1L, "owner-a");
        owner.setOwnerControlViewMode(" ALL_MANAGERS ");
        when(userService.findByUserName("owner-a")).thenReturn(Optional.of(owner));

        LeadAccessService.OwnerReadScope scope = service.ownerReadScope(auth("owner-a", "OWNER"));

        assertTrue(scope.canReadAllManagers());
        assertTrue(scope.managers().isEmpty());
    }

    @Test
    void operatorCannotBeAssignedWithoutManager() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireLeadAssignmentsAllowed(null, 4L, null, auth("admin", "ADMIN"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void managerCannotDetachLeadFromOwnTeam() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireLeadAssignmentsAllowed(null, null, null, auth("manager-a", "MANAGER"))
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void missingAssignmentTargetIsReportedAsNotFound() {
        when(managerService.getManagerById(11L)).thenReturn(Manager.builder().id(11L).build());
        when(operatorService.getOperatorById(404L)).thenThrow(new EntityNotFoundException("missing"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireLeadAssignmentsAllowed(11L, 404L, null, auth("admin", "ADMIN"))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    private Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    private User user(Long id, String username) {
        return User.builder().id(id).username(username).active(true).build();
    }
}
