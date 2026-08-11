package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRiskResponseSlaJobTest {

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;
    @Mock
    private WorkerRiskEventService eventService;
    @Mock
    private UserService userService;
    @Mock
    private TelegramService telegramService;
    @Mock
    private NotificationMediaDeliveryService notificationMediaDeliveryService;
    @Mock
    private AppSettingService appSettingService;

    private WorkerRiskResponseSlaJob job;

    @BeforeEach
    void setUp() {
        job = new WorkerRiskResponseSlaJob(
                incidentRepository,
                eventService,
                userService,
                telegramService,
                notificationMediaDeliveryService,
                appSettingService,
                transactionManager()
        );
        when(appSettingService.getInt(AppSettingService.WORKER_RISK_EXPLANATION_DEADLINE_MINUTES, 180))
                .thenReturn(180);
        when(appSettingService.getInt(AppSettingService.WORKER_RISK_EXPLANATION_REMINDER_MINUTES, 120))
                .thenReturn(120);
        when(appSettingService.getBoolean(
                AppSettingService.WORKER_RISK_SPECIALIST_SECTION_RESTRICTION_ENABLED,
                true
        )).thenReturn(true);
    }

    @Test
    void overdueRiskSendsRedReplyButtonAndRestrictsOnlySpecialistSection() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно подтвердить фактическое выполнение");
        incident.setOrderId(100L);
        incident.setReviewId(501L);
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));

        User worker = new User();
        worker.setId(2L);
        worker.setUsername("worker-renamed");
        worker.setActive(true);
        worker.setTelegramChatId(888L);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(incident));
        when(notificationMediaDeliveryService.send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(-100123L),
                eq(2L),
                contains("Код запроса: risk-77"),
                eq(null),
                any()
        ))
                .thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();
        job.process();

        assertNotNull(incident.getExplanationReminderAt());
        assertNotNull(incident.getSectionRestrictedAt());
        assertNull(incident.getSlaDeliveryClaimToken());
        verify(eventService).record(
                eq(incident),
                eq(WorkerRiskEventType.EXPLANATION_REMINDER_SENT),
                eq(2L),
                eq("WORKER"),
                eq("telegram-overdue"),
                any()
        );
        verify(eventService).record(
                eq(incident),
                eq(WorkerRiskEventType.SPECIALIST_SECTION_RESTRICTED),
                eq(2L),
                eq("WORKER"),
                eq("sla-job"),
                any()
        );
        ArgumentCaptor<String> reminder = ArgumentCaptor.forClass(String.class);
        verify(notificationMediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(-100123L),
                eq(2L),
                reminder.capture(),
                eq(null),
                any()
        );
        assertTrue(reminder.getValue().contains("🔴 ОТВЕТ ПРОСРОЧЕН"));
        assertTrue(reminder.getValue().contains("Пояснить причину"));
    }

    @Test
    void upcomingDeadlineSendsYellowReplyButtonWithoutRestriction() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(78L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно пояснить действие");
        incident.setOrderId(100L);
        incident.setReviewId(501L);
        incident.setResponseDueAt(LocalDateTime.now().plusMinutes(59));

        User worker = new User();
        worker.setId(2L);
        worker.setUsername("worker");
        worker.setActive(true);
        worker.setTelegramChatId(888L);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(incident));
        when(notificationMediaDeliveryService.send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_REMINDER.code()),
                eq(-100123L),
                eq(2L),
                contains("Код запроса: risk-78"),
                eq(null),
                any()
        )).thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNotNull(incident.getExplanationReminderAt());
        assertNull(incident.getSectionRestrictedAt());
        assertNull(incident.getSlaDeliveryClaimToken());
        ArgumentCaptor<String> reminder = ArgumentCaptor.forClass(String.class);
        verify(notificationMediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_REMINDER.code()),
                eq(-100123L),
                eq(2L),
                reminder.capture(),
                eq(null),
                any()
        );
        assertTrue(reminder.getValue().contains("🟡 НУЖНО ОТВЕТИТЬ"));
        verify(eventService, never()).record(
                eq(incident),
                eq(WorkerRiskEventType.SPECIALIST_SECTION_RESTRICTED),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void overdueDeliveryFailureDoesNotRestrictSpecialist() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(79L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно пояснить действие");
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));

        User worker = new User();
        worker.setId(2L);
        worker.setUsername("worker");
        worker.setActive(true);
        worker.setTelegramChatId(888L);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.findByIdForUpdate(79L)).thenReturn(Optional.of(incident));
        when(notificationMediaDeliveryService.send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(-100123L),
                eq(2L),
                any(),
                eq(null),
                any()
        )).thenReturn(false);

        job.process();
        job.process();

        assertNull(incident.getSectionRestrictedAt());
        assertNull(incident.getExplanationReminderAt());
        assertNotNull(incident.getSlaDeliveryClaimToken());
        verify(notificationMediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(-100123L),
                eq(2L),
                any(),
                eq(null),
                any()
        );
        verify(eventService, never()).record(
                eq(incident),
                eq(WorkerRiskEventType.SPECIALIST_SECTION_RESTRICTED),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void missingPersonalTelegramSuspendsSlaAndReleasesExistingRestriction() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(80L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно пояснить действие");
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));
        incident.setExplanationReminderAt(LocalDateTime.now().minusMinutes(30));
        incident.setSectionRestrictedAt(LocalDateTime.now().minusMinutes(5));

        User worker = new User();
        worker.setId(2L);
        worker.setUsername("worker");
        worker.setActive(true);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.findByIdForUpdate(80L)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNull(incident.getResponseDueAt());
        assertNull(incident.getExplanationReminderAt());
        assertNotNull(incident.getSectionRestrictionReleasedAt());
        verify(notificationMediaDeliveryService, never()).send(any(), anyLong(), any(), any(), any(), any());
        verify(eventService).record(
                eq(incident),
                eq(WorkerRiskEventType.EXPLANATION_REQUEST_FAILED),
                eq(2L),
                eq("WORKER"),
                eq("sla-job"),
                any()
        );
        verify(eventService).record(
                eq(incident),
                eq(WorkerRiskEventType.SPECIALIST_SECTION_RELEASED),
                eq(2L),
                eq("WORKER"),
                eq("sla-job"),
                any()
        );
    }

    @Test
    void staleDeliveryClaimSuspendsSlaWithoutSendingOrRestricting() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(81L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно пояснить действие");
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));
        incident.setSlaDeliveryClaimToken("stale-token");
        incident.setSlaDeliveryClaimKind("OVERDUE");
        incident.setSlaDeliveryClaimedAt(LocalDateTime.now().minusMinutes(11));

        User worker = new User();
        worker.setId(2L);
        worker.setActive(true);
        worker.setTelegramChatId(888L);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(incidentRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNull(incident.getResponseDueAt());
        assertNull(incident.getExplanationReminderAt());
        assertNull(incident.getSectionRestrictedAt());
        assertNull(incident.getSlaDeliveryClaimToken());
        assertNull(incident.getSlaDeliveryClaimedAt());
        assertNull(incident.getSlaDeliveryClaimKind());
        verify(notificationMediaDeliveryService, never()).send(any(), anyLong(), any(), any(), any(), any());
        verify(eventService).record(
                eq(incident),
                eq(WorkerRiskEventType.EXPLANATION_REQUEST_FAILED),
                eq(2L),
                eq("WORKER"),
                eq("sla-job"),
                any()
        );
    }

    @Test
    void futureDatedDeliveryClaimIsNotKeptForever() {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(82L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setWorkerUserId(2L);
        incident.setWorkerUsername("worker");
        incident.setTitle("Нужно пояснить действие");
        incident.setResponseDueAt(LocalDateTime.now().minusMinutes(1));
        incident.setSlaDeliveryClaimToken("future-token");
        incident.setSlaDeliveryClaimKind("OVERDUE");
        incident.setSlaDeliveryClaimedAt(LocalDateTime.now().plusHours(1));

        User worker = new User();
        worker.setId(2L);
        worker.setActive(true);
        worker.setTelegramChatId(888L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(incidentRepository.findByIdForUpdate(82L)).thenReturn(Optional.of(incident));
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNull(incident.getResponseDueAt());
        assertNull(incident.getSlaDeliveryClaimToken());
        verify(notificationMediaDeliveryService, never()).send(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void keysetPagingDoesNotLetFiveHundredOldRisksStarveNextRisk() {
        LocalDateTime oldDueAt = LocalDateTime.now().minusDays(2);
        List<WorkerRiskIncident> firstBatch = new ArrayList<>();
        for (long id = 1; id <= 500; id++) {
            WorkerRiskIncident oldIncident = new WorkerRiskIncident();
            oldIncident.setId(id);
            oldIncident.setResponseDueAt(oldDueAt.plusNanos(id * 1_000));
            firstBatch.add(oldIncident);
        }

        WorkerRiskIncident target = new WorkerRiskIncident();
        target.setId(501L);
        target.setStatus(WorkerRiskIncidentStatus.OPEN);
        target.setWorkerUserId(2L);
        target.setWorkerUsername("worker");
        target.setTitle("Новый риск после первой страницы");
        target.setResponseDueAt(LocalDateTime.now().minusMinutes(1));

        User worker = new User();
        worker.setId(2L);
        worker.setActive(true);
        worker.setTelegramChatId(888L);

        when(incidentRepository.findPendingResponseSlaAfter(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(),
                any(),
                any(Pageable.class)
        )).thenReturn(firstBatch, List.of(target));
        when(incidentRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation ->
                invocation.<Long>getArgument(0).equals(501L) ? Optional.of(target) : Optional.empty()
        );
        when(userService.findByIdToUserInfo(2L)).thenReturn(worker);
        when(notificationMediaDeliveryService.send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(888L),
                eq(2L),
                contains("Код запроса: risk-501"),
                eq(null),
                any()
        )).thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNotNull(target.getSectionRestrictedAt());
        verify(notificationMediaDeliveryService).send(
                eq(NotificationMediaEventCatalog.WORKER_RISK_OVERDUE.code()),
                eq(888L),
                eq(2L),
                contains("Код запроса: risk-501"),
                eq(null),
                any()
        );
    }

    private PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
