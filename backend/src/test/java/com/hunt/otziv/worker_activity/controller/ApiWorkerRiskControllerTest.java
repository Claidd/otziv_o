package com.hunt.otziv.worker_activity.controller;

import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.dto.WorkerRiskIncidentResponse;
import com.hunt.otziv.worker_activity.dto.WorkerRiskAuditRequest;
import com.hunt.otziv.worker_activity.dto.WorkerRiskResolutionRequest;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.worker_activity.service.WorkerRiskRollbackService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEventService;
import com.hunt.otziv.worker_activity.service.WorkerRiskDecisionPolicy;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import java.time.LocalDateTime;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
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

    @Mock
    private ManagerDailyControlConcreteItemRepository managerControlConcreteItemRepository;

    @Mock
    private WorkerRiskEventService riskEventService;

    @Mock
    private WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService;

    private ApiWorkerRiskController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiWorkerRiskController(
                incidentRepository,
                scoreLedgerRepository,
                userService,
                personalReminderService,
                telegramService,
                rollbackService,
                managerControlConcreteItemRepository,
                riskEventService,
                new WorkerRiskDecisionPolicy(),
                mock(com.hunt.otziv.config.settings.service.AppSettingService.class),
                workerRiskTelegramCallbackService
        );
    }

    @Test
    void requestExplanationKeepsIncidentOpen() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));
        when(userService.findByIdToUserInfo(2L)).thenReturn(user(2L, "worker", 101L));
        when(personalReminderService.hasOpenSystemReminder(any(), eq("WORKER_RISK_MANAGER_WARNING"), eq(77L)))
                .thenReturn(false);
        when(telegramService.sendMessageWithInlineKeyboard(eq(101L), any(), eq(null), any()))
                .thenReturn(true);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("EXPLANATION_REQUESTED", null, null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.OPEN, response.status());
        assertEquals(WorkerRiskResolutionAction.EXPLANATION_REQUESTED, response.resolutionAction());
        assertEquals(0, response.penaltyPoints());
        assertNotNull(response.responseDueAt());
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
    void requestExplanationWithoutPersonalTelegramDoesNotStartSla() {
        WorkerRiskIncident incident = incident();
        User worker = user(2L, "worker", null);
        worker.setWorkerTelegramGroupChatId(-100123L);
        ManagerDailyControlConcreteItem controlItem = new ManagerDailyControlConcreteItem();
        controlItem.setWorkerNotificationAttemptedAt(LocalDateTime.now().minusHours(4));
        controlItem.setWorkerNotificationSentAt(LocalDateTime.now().minusHours(4));
        controlItem.setWorkerReminderSentAt(LocalDateTime.now().minusHours(1));
        controlItem.setWorkerReminderCount(2);
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(managerControlConcreteItemRepository.findByEntityTypeAndEntityId("RISK", 77L))
                .thenReturn(List.of(controlItem));
        when(personalReminderService.hasOpenSystemReminder(any(), eq("WORKER_RISK_MANAGER_WARNING"), eq(77L)))
                .thenReturn(false);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("EXPLANATION_REQUESTED", null, null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.OPEN, response.status());
        assertEquals(null, response.responseDueAt());
        ArgumentCaptor<String> bindingWarning = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-100123L), bindingWarning.capture());
        assertEquals(true, bindingWarning.getValue().contains("Трёхчасовой срок и ограничение раздела не запущены"));
        assertFalse(bindingWarning.getValue().contains(worker.getUsername()));
        assertFalse(bindingWarning.getValue().contains("отправьте логин"));
        assertNull(controlItem.getWorkerNotificationAttemptedAt());
        assertNull(controlItem.getWorkerNotificationSentAt());
        assertNull(controlItem.getWorkerReminderSentAt());
        assertEquals(0, controlItem.getWorkerReminderCount());
        verify(telegramService, never()).sendMessageWithInlineKeyboard(any(Long.class), any(), any(), any());
    }

    @Test
    void violationCreatesPenaltyLedgerEntry() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));
        when(userService.findByIdToUserInfo(2L)).thenReturn(user(2L, "worker", 101L));
        when(scoreLedgerRepository.existsByUniqueScoreKey("worker-risk-penalty:77")).thenReturn(false);
        when(personalReminderService.hasOpenSystemReminder(any(), eq("WORKER_RISK_MANAGER_VIOLATION"), eq(77L)))
                .thenReturn(false);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest(
                        "VIOLATION_CONFIRMED",
                        3,
                        "Проверено: специалист не выполнил обязательный шаг"
                ),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.VIOLATION, response.status());
        assertEquals(WorkerRiskResolutionAction.VIOLATION_CONFIRMED, response.resolutionAction());
        assertEquals(3, response.penaltyPoints());
        assertEquals(true, response.auditRequired());

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
        verify(workerRiskTelegramCallbackService).markOriginalRiskTelegramMessageResolved(incident);
    }

    @Test
    void verifiedDeletesOpenRiskReminder() {
        WorkerRiskIncident incident = incident();
        incident.setExplanationQuality(WorkerRiskExplanationQuality.LOGICAL);
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("VERIFIED", null, null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.RESOLVED, response.status());
        verify(personalReminderService).deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                77L
        );
    }

    @Test
    void adminVerificationClosesRiskWithoutExplanationOrAudit() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("VERIFIED", null, null),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.RESOLVED, response.status());
        assertEquals(WorkerRiskResolutionAction.VERIFIED, response.resolutionAction());
        assertEquals(false, response.auditRequired());
        assertEquals("ADMIN_VERIFIED", incident.getDecisionQuality());
        assertNotNull(response.resolvedAt());
    }

    @Test
    void ownerVerificationClosesRiskWithoutExplanationOrAudit() {
        WorkerRiskIncident incident = incident();
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("owner")).thenReturn(Optional.of(user(3L, "owner", null)));
        when(userService.findManagersByUserName("owner")).thenReturn(java.util.Set.of());
        when(managerControlConcreteItemRepository.existsByEntityTypeAndEntityIdAndControl_Manager_User_Username(
                "RISK",
                77L,
                "owner"
        )).thenReturn(true);

        WorkerRiskIncidentResponse response = controller.resolution(
                77L,
                new WorkerRiskResolutionRequest("VERIFIED", null, null),
                ownerAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.RESOLVED, response.status());
        assertEquals(WorkerRiskResolutionAction.VERIFIED, response.resolutionAction());
        assertEquals(false, response.auditRequired());
        assertEquals("OWNER_VERIFIED", incident.getDecisionQuality());
        assertNotNull(response.resolvedAt());
    }

    @Test
    void ownerReturnFromAuditReopensForManagerWithoutKeepingWorkerRestricted() {
        WorkerRiskIncident incident = incident();
        incident.setStatus(WorkerRiskIncidentStatus.RESOLVED);
        incident.setResolutionAction(WorkerRiskResolutionAction.VERIFIED);
        incident.setResolvedAt(java.time.LocalDateTime.now());
        incident.setResolvedByUserId(5L);
        incident.setResolvedByUsername("manager");
        incident.setAuditRequired(true);
        incident.setResponseDueAt(java.time.LocalDateTime.now().minusHours(1));
        incident.setSectionRestrictedAt(java.time.LocalDateTime.now().minusMinutes(30));

        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.findByUserName("admin")).thenReturn(Optional.of(user(1L, "admin", null)));

        WorkerRiskIncidentResponse response = controller.reviewAudit(
                77L,
                new WorkerRiskAuditRequest(
                        "RETURNED",
                        "Решение не подтверждено фактами, менеджеру нужно проверить заказ"
                ),
                adminAuth()
        );

        assertEquals(WorkerRiskIncidentStatus.OPEN, response.status());
        assertEquals(null, response.resolutionAction());
        assertEquals(null, response.responseDueAt());
        assertNotNull(response.sectionRestrictionReleasedAt());
        assertEquals(false, response.auditRequired());
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

    private Authentication ownerAuth() {
        return new UsernamePasswordAuthenticationToken(
                "owner",
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
    }
}
