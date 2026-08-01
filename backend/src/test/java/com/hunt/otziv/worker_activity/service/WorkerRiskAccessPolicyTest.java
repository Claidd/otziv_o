package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerRiskAccessPolicyTest {

    @Test
    void onlySpecialistSectionIsRestrictedForOverdueWorkerResponse() {
        UserService userService = mock(UserService.class);
        WorkerRiskIncidentRepository repository = mock(WorkerRiskIncidentRepository.class);
        AppSettingService settings = mock(AppSettingService.class);
        WorkerRiskAccessPolicy policy = new WorkerRiskAccessPolicy(userService, repository, settings);
        User worker = user(7L, "worker", "ROLE_WORKER");
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(11L);
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));

        when(settings.getBoolean(
                AppSettingService.WORKER_RISK_SPECIALIST_SECTION_RESTRICTION_ENABLED,
                true
        )).thenReturn(true);
        when(userService.findByUserName("worker")).thenReturn(Optional.of(worker));
        when(repository.findByWorkerUserIdAndStatusAndSectionRestrictedAtIsNotNullAndSectionRestrictionReleasedAtIsNullAndExplanationAcceptedAtIsNullOrderBySectionRestrictedAtAsc(
                any(),
                any(WorkerRiskIncidentStatus.class)
        )).thenReturn(List.of(incident));

        assertTrue(policy.status("worker").restricted());

        User manager = user(8L, "manager", "ROLE_MANAGER");
        when(userService.findByUserName("manager")).thenReturn(Optional.of(manager));
        assertFalse(policy.status("manager").restricted());
    }

    private User user(Long id, String username, String roleName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        Role role = new Role();
        role.setName(roleName);
        user.setRoles(Set.of(role));
        return user;
    }
}
