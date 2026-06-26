package com.hunt.otziv.worker_activity.controller;

import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.dto.WorkerRiskIncidentResponse;
import com.hunt.otziv.worker_activity.dto.WorkerRiskResolutionRequest;
import com.hunt.otziv.worker_activity.WorkerRiskEvaluationService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.worker_activity.service.WorkerRiskRollbackService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiWorkerRiskControllerTest {

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;

    @Mock
    private GamificationScoreLedgerRepository scoreLedgerRepository;

    @Mock
    private UserService userService;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private WorkerRiskRollbackService rollbackService;

    private ApiWorkerRiskController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiWorkerRiskController(
                incidentRepository,
                scoreLedgerRepository,
                userService,
                personalReminderService,
                telegramService,
                rollbackService
        );
    }

    @Test
    void requestExplanationKeepsIncidentOpen() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));
        when(userService.findByUserName("worker")).thenReturn(Optional.of(user(2L, "worker", 101L)));
        when(personalReminderService.hasOpenSystemReminder(any(), eq("WORKER_RISK_MANAGER_WARNING"), eq(77L)))
                .thenReturn(false);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("EXPLANATION_REQUESTED", null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.OPEN, response.status());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, response.resolutionAction());
        assertEquals(0, response.penaltyPoints());
        verify(personalReminderService).createSystemReminderDueNow(
                any(),
                eq("Нужно пояснение по действию"),
                any(),
                eq("WORKER_RISK_MANAGER_WARNING"),
                eq(77L),
                eq(100L)
        );
        verify(scoreLedgerRepository, never()).save(any());
    }

    @Test
    void violationCreatesPenaltyLedgerEntry() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));
        when(userService.findByUserName("worker")).thenReturn(Optional.of(user(2L, "worker", 101L)));
        when(scoreLedgerRepository.existsByUniqueScoreKey("worker-risk-penalty:77")).thenReturn(false);
        when(personalReminderService.hasOpenSystemReminder(any(), eq("WORKER_RISK_MANAGER_VIOLATION"), eq(77L)))
                .thenReturn(false);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("VIOLATION_CONFIRMED", 3),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.VIOLATION, response.status());
        assertEquals(WorkerRiskResolutionAction.VIOLATION_CONFIRMED, response.resolutionAction());
        assertEquals(3, response.penaltyPoints());

        ArgumentCaptor<GamificationScoreLedger> ledgerCaptor = ArgumentCaptor.forClass(GamificationScoreLedger.class);
        verify(scoreLedgerRepository).save(ledgerCaptor.capture());
        GamificationScoreLedger ledger = ledgerCaptor.getValue();
        assertEquals("WORKER_RISK_PENALTY", ledger.getEventType());
        assertEquals(2L, ledger.getActorUserId());
        assertEquals(-3, ledger.getPoints());
        assertEquals("worker-risk-penalty:77", ledger.getUniqueScoreKey());
        assertNotNull(ledger.getSourceEventCreatedAt());
        verify(personalReminderService).deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                77L
        );
    }

    @Test
    void verifiedDeletesOpenRiskReminder() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findById(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("VERIFIED", null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.RESOLVED, response.status());
        verify(personalReminderService).deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                77L
        );
    }

    private WorkerRiskIncident incident() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setLevel(WorkerRiskIncidentLevel.MANAGER_REVIEW);
        incident.setRuleCode("PUBLISH_WITHOUT_CREDENTIAL_COPY");
        incident.setScore(30);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setWorkerName("Иван Работник");
        incident.setAction("REVIEW_PUBLISH");
        incident.setEntityType("review");
        incident.setEntityId(501L);
        incident.setOrderId(100L);
        incident.setReviewId(501L);
        incident.setTitle("Публикация без копирования данных аккаунта");
        incident.setMessage("Проверить публикацию");
        return incident;
    }

    private User user(Long id, String username, Long telegramChatId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFio(username);
        user.setActive(true);
        user.setTelegramChatId(telegramChatId);
        return user;
    }

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                "admin",
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}
