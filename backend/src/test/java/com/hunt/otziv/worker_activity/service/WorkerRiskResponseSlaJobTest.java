package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private AppSettingService appSettingService;

    private WorkerRiskResponseSlaJob job;

    @BeforeEach
    void setUp() {
        job = new WorkerRiskResponseSlaJob(
                incidentRepository,
                eventService,
                userService,
                telegramService,
                appSettingService
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
        worker.setUsername("worker");
        worker.setActive(true);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSla(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByUserName("worker")).thenReturn(Optional.of(worker));
        when(telegramService.sendMessageWithInlineKeyboard(
                eq(-100123L),
                contains("Код запроса: risk-77"),
                eq(null),
                any()
        ))
                .thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNotNull(incident.getExplanationReminderAt());
        assertNotNull(incident.getSectionRestrictedAt());
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
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100123L),
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
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.findPendingResponseSla(
                eq(WorkerRiskIncidentStatus.OPEN),
                any(Pageable.class)
        )).thenReturn(List.of(incident));
        when(userService.findByUserName("worker")).thenReturn(Optional.of(worker));
        when(telegramService.sendMessageWithInlineKeyboard(
                eq(-100123L),
                contains("Код запроса: risk-78"),
                eq(null),
                any()
        )).thenReturn(true);
        when(incidentRepository.save(any(WorkerRiskIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.process();

        assertNotNull(incident.getExplanationReminderAt());
        assertNull(incident.getSectionRestrictedAt());
        ArgumentCaptor<String> reminder = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(-100123L),
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
}
