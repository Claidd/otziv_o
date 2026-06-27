package com.hunt.otziv.worker_activity;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRiskEvaluationServiceTest {

    @Mock
    private WorkerActivityEventRepository eventRepository;

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private UserService userService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void publishWithoutCredentialCopyCreatesIncidentAndWarnings() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        User worker = user(1L, "worker", "Иван Работник", 101L);
        User managerUser = user(2L, "manager", "Мария Менеджер", 102L);
        User ownerUser = user(3L, "owner", "Ольга Владелец", 103L);
        User adminUser = user(4L, "admin", "Анна Админ", 104L);
        Manager manager = new Manager();
        manager.setUser(managerUser);
        worker.setManagers(Set.of(manager));

        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("PUBLISH_WITHOUT_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> {
            WorkerRiskIncident incident = invocation.getArgument(0);
            incident.setId(77L);
            return incident;
        });
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(ownerUser));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(adminUser));
        when(orderRepository.findCompanyIdByOrderId(100L)).thenReturn(Optional.of(15232L));

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("PUBLISH_WITHOUT_CREDENTIAL_COPY", captor.getValue().getRuleCode());
        assertEquals(30, captor.getValue().getScore());
        verify(personalReminderService).createSystemReminderDueNow(
                eq(managerUser),
                anyString(),
                anyString(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(ownerUser),
                anyString(),
                anyString(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(adminUser),
                anyString(),
                anyString(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(personalReminderService).createSystemReminderDueNow(
                eq(managerUser),
                anyString(),
                textCaptor.capture(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
        assertEquals(false, textCaptor.getValue().contains("Логин: worker"));

        verify(personalReminderService).createSystemReminderDueNow(
                eq(ownerUser),
                anyString(),
                textCaptor.capture(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
        assertEquals(true, textCaptor.getValue().contains("Логин: worker"));
        assertEquals(true, textCaptor.getValue().contains("Компания: №15232 Заказ: #100"));
        assertEquals(true, textCaptor.getValue().contains("Отзыв: #501"));

        verify(personalReminderService).createSystemReminderDueNow(
                eq(adminUser),
                anyString(),
                textCaptor.capture(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
        assertEquals(true, textCaptor.getValue().contains("Логин: worker"));

        ArgumentCaptor<String> telegramCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboard(
                eq(103L),
                telegramCaptor.capture(),
                eq("HTML"),
                any()
        );
        assertEquals(true, telegramCaptor.getValue().contains("<a href=\"https://o-ogo.ru/manager?section=orders&amp;companyId=15232\">№15232</a>"));
        assertEquals(true, telegramCaptor.getValue().contains("<a href=\"https://o-ogo.ru/manager/orders/0/100\">#100</a>"));
        assertEquals(true, telegramCaptor.getValue().contains("<a href=\"https://o-ogo.ru/manager/orders/0/100?reviewId=501\">#501</a>"));

        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(worker),
                anyString(),
                anyString(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(77L),
                isNull()
        );
    }

    @Test
    void duplicateOpenIncidentSuppressesNewWarning() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("PUBLISH_WITHOUT_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(true);

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
        verify(personalReminderService, never()).createSystemReminderDueNow(
                any(),
                anyString(),
                anyString(),
                anyString(),
                anyLong(),
                any()
        );
    }

    @Test
    void publishChecksCredentialCopyForPublishedBot() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        event.setDetails("botId=10;");
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                eq(1L),
                eq(WorkerActivityAction.REVIEW_COPY_LOGIN),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("botId=10;")
        )).thenReturn(true);
        when(eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                eq(1L),
                eq(WorkerActivityAction.REVIEW_COPY_PASSWORD),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("botId=10;")
        )).thenReturn(true);
        when(eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenAndDetailsContainingOrderByCreatedAtDesc(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("botId=10;")
        )).thenReturn(Optional.empty());

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void publishTooFastAfterCredentialCopyCreatesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                eq(event.getCreatedAt())
        )).thenReturn(true);
        WorkerActivityEvent copyEvent = event(WorkerActivityAction.REVIEW_COPY_PASSWORD);
        copyEvent.setCreatedAt(event.getCreatedAt().minusSeconds(3));
        when(eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                eq(event.getCreatedAt())
        )).thenReturn(Optional.of(copyEvent));
        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("PUBLISH_TOO_FAST_AFTER_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("PUBLISH_TOO_FAST_AFTER_CREDENTIAL_COPY", captor.getValue().getRuleCode());
        assertEquals(true, captor.getValue().getDetails().contains("3 сек"));
        assertEquals(true, captor.getValue().getDetails().contains("Минимум: 180 сек"));
    }

    @Test
    void nagulTooFastAfterCredentialCopyCreatesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_NAGUL);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                eq(event.getCreatedAt())
        )).thenReturn(true);
        WorkerActivityEvent copyEvent = event(WorkerActivityAction.REVIEW_COPY_PASSWORD);
        copyEvent.setCreatedAt(event.getCreatedAt().minusSeconds(10));
        when(eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                eq(event.getCreatedAt())
        )).thenReturn(Optional.of(copyEvent));
        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("NAGUL_TOO_FAST_AFTER_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("NAGUL_TOO_FAST_AFTER_CREDENTIAL_COPY", captor.getValue().getRuleCode());
        assertEquals(true, captor.getValue().getDetails().contains("10 сек"));
        assertEquals(true, captor.getValue().getDetails().contains("Минимум: 180 сек"));
    }

    @Test
    void repeatedFastCloseAfterAccountCopyCreatesSeriesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_NAGUL);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        User worker = user(1L, "worker", "Иван Работник", 101L);

        WorkerActivityEvent firstClose = event;
        WorkerActivityEvent secondClose = event(WorkerActivityAction.REVIEW_NAGUL);
        secondClose.setId(11L);
        secondClose.setCreatedAt(event.getCreatedAt().minusMinutes(5));
        WorkerActivityEvent thirdClose = event(WorkerActivityAction.REVIEW_NAGUL);
        thirdClose.setId(12L);
        thirdClose.setCreatedAt(event.getCreatedAt().minusMinutes(10));

        WorkerActivityEvent accountEvent = event(WorkerActivityAction.REVIEW_BOT_CHANGE);
        accountEvent.setCreatedAt(event.getCreatedAt().minusSeconds(30));

        when(eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(accountEvent));
        when(eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                eq(1L),
                any(),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(1L),
                eq(List.of(WorkerActivityAction.REVIEW_COPY_LOGIN, WorkerActivityAction.REVIEW_COPY_PASSWORD)),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            LocalDateTime until = invocation.getArgument(4);
            WorkerActivityEvent copyEvent = event(WorkerActivityAction.REVIEW_COPY_PASSWORD);
            copyEvent.setCreatedAt(until.minusSeconds(3));
            return Optional.of(copyEvent);
        });
        when(eventRepository.findTop50ByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(1L),
                any(),
                any(LocalDateTime.class)
        )).thenReturn(List.of(firstClose, secondClose, thirdClose));
        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                anyString(),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository, atLeastOnce()).save(captor.capture());
        WorkerRiskIncident seriesIncident = captor.getAllValues().stream()
                .filter(incident -> "ACCOUNT_CLOSE_TOO_FAST_AFTER_CREDENTIAL_COPY_SERIES".equals(incident.getRuleCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(true, seriesIncident.getDetails().contains("найдено 3"));
        assertEquals(true, seriesIncident.getDetails().contains("180 сек"));
    }

    @Test
    void botChangeAloneDoesNotCreateSameCardIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_BOT_CHANGE);
        event.setDetails("oldBotId=10, newBotId=11");
        User worker = user(1L, "worker", "Иван Работник", 101L);

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void botDeactivationWithoutCredentialCopyCreatesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_BOT_DEACTIVATE);
        event.setDetails("botId=10;sourcePage=order-details;sourceEntry=worker-all;sourceSection=all;");
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("ACCOUNT_DEACTIVATION_WITHOUT_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> {
            WorkerRiskIncident incident = invocation.getArgument(0);
            incident.setId(90L);
            return incident;
        });

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("ACCOUNT_DEACTIVATION_WITHOUT_CREDENTIAL_COPY", captor.getValue().getRuleCode());
        assertEquals(35, captor.getValue().getScore());
        assertEquals(true, captor.getValue().getDetails().contains("Место: Детали заказа, вход: Специалист -> Все, раздел: Все"));
    }

    @Test
    void botDeactivationAfterCredentialCopyDoesNotCreateIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_BOT_DEACTIVATE);
        event.setDetails("botId=10");
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                eq(1L),
                eq(WorkerActivityAction.REVIEW_COPY_LOGIN),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("botId=10;")
        )).thenReturn(true);
        when(eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                eq(1L),
                eq(WorkerActivityAction.REVIEW_COPY_PASSWORD),
                eq(501L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq("botId=10;")
        )).thenReturn(true);

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void reviewTextBulkUpdatesDoNotCreateFrequencyIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_TEXT_UPDATE);
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.countByWorkerUserIdAndActionInAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq(List.of(WorkerActivityAction.REVIEW_TEXT_UPDATE)),
                eq("review"),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(1L);

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void repeatedReviewTextUpdatesOnSameCardCreateIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_TEXT_UPDATE);
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(eventRepository.countByWorkerUserIdAndActionInAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq(List.of(WorkerActivityAction.REVIEW_TEXT_UPDATE)),
                eq("review"),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(5L);
        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("REVIEW_TEXT_SAME_CARD_HOUR"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> {
            WorkerRiskIncident incident = invocation.getArgument(0);
            incident.setId(88L);
            return incident;
        });

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("REVIEW_TEXT_SAME_CARD_HOUR", captor.getValue().getRuleCode());
    }

    private WorkerRiskEvaluationService service() {
        return new WorkerRiskEvaluationService(
                eventRepository,
                incidentRepository,
                personalReminderService,
                userService,
                telegramService,
                orderRepository,
                transactionManager()
        );
    }

    private WorkerActivityEvent event(WorkerActivityAction action) {
        WorkerActivityEvent event = new WorkerActivityEvent();
        event.setId(10L);
        event.setCreatedAt(LocalDateTime.now());
        event.setWorkerUserId(1L);
        event.setWorkerUsername("worker");
        event.setWorkerName("Иван Работник");
        event.setAction(action);
        event.setEntityType("review");
        event.setEntityId(501L);
        event.setOrderId(100L);
        event.setReviewId(501L);
        return event;
    }

    private User user(Long id, String username, String fio, Long telegramChatId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFio(fio);
        user.setTelegramChatId(telegramChatId);
        user.setActive(true);
        return user;
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
