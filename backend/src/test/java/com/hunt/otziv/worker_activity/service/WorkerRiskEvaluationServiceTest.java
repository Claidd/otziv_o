package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparation;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import com.hunt.otziv.worker_activity.repository.WorkerCredentialPreparationRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRiskEvaluationServiceTest {

    @Mock
    private WorkerActivityEventRepository eventRepository;

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;

    @Mock
    private WorkerCredentialPreparationRepository credentialPreparationRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private UserService userService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private WorkerRiskEventService riskEventService;

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
        Order riskOrder = new Order();
        riskOrder.setId(100L);
        Company riskCompany = new Company();
        riskCompany.setId(15232L);
        riskCompany.setTitle("Арком");
        riskOrder.setCompany(riskCompany);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(riskOrder));

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
        assertEquals(true, textCaptor.getValue().contains("Компания: Арком (№15232) Заказ: #100"));
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
        assertEquals(true, telegramCaptor.getValue().contains("<a href=\"https://o-ogo.ru/manager?section=orders&amp;companyId=15232\">Арком (№15232)</a>"));
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
        when(credentialPreparationRepository.findByWorkerUserIdAndScope(1L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.of(preparation(
                        WorkerCredentialPreparationScope.PUBLISH,
                        501L,
                        10L,
                        event.getCreatedAt().minusMinutes(10)
                )));

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void publishAllowsOldCredentialCopyWhenPreparationIsStillActive() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        event.setDetails("botId=10;");
        User worker = user(1L, "worker", "Иван Работник", 101L);
        when(credentialPreparationRepository.findByWorkerUserIdAndScope(1L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.of(preparation(
                        WorkerCredentialPreparationScope.PUBLISH,
                        501L,
                        10L,
                        event.getCreatedAt().minusMinutes(64)
                )));

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void publishTooFastAfterCredentialCopyCreatesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(credentialPreparationRepository.findByWorkerUserIdAndScope(1L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.of(preparation(
                        WorkerCredentialPreparationScope.PUBLISH,
                        501L,
                        null,
                        event.getCreatedAt().minusSeconds(3)
                )));
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
        assertEquals(true, captor.getValue().getDetails().contains("Минимум: 150 сек"));
    }

    @Test
    void nagulTooFastAfterCredentialCopyCreatesIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_NAGUL);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 22, 12, 0));
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(credentialPreparationRepository.findByWorkerUserIdAndScope(1L, WorkerCredentialPreparationScope.NAGUL))
                .thenReturn(Optional.of(preparation(
                        WorkerCredentialPreparationScope.NAGUL,
                        501L,
                        null,
                        event.getCreatedAt().minusSeconds(10)
                )));
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
    void groupWithoutPersonalTelegramDoesNotStartExplanationSla() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH);
        User worker = user(1L, "worker", "Иван Работник", null);
        worker.setWorkerTelegramGroupChatId(-100123L);

        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("PUBLISH_WITHOUT_CREDENTIAL_COPY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> {
            WorkerRiskIncident incident = invocation.getArgument(0);
            incident.setId(79L);
            return incident;
        });
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of());
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of());

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> incident = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(incident.capture());
        assertEquals(null, incident.getValue().getResponseDueAt());
        assertEquals(null, incident.getValue().getExplanationRequestedAt());
        ArgumentCaptor<String> bindingWarning = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(-100123L), bindingWarning.capture());
        assertEquals(true, bindingWarning.getValue().contains("Трёхчасовой срок и ограничение раздела не запущены"));
        assertFalse(bindingWarning.getValue().contains(worker.getUsername()));
        assertFalse(bindingWarning.getValue().contains("отправьте логин"));
        verify(telegramService, never()).sendMessageWithInlineKeyboardMessageId(
                anyLong(),
                anyString(),
                anyString(),
                any()
        );
        verify(riskEventService).record(
                eq(incident.getValue()),
                eq(com.hunt.otziv.worker_activity.model.WorkerRiskEventType.EXPLANATION_REQUEST_FAILED),
                eq(1L),
                eq("WORKER"),
                eq("telegram"),
                any()
        );
    }

    @Test
    void normalDailyNagulVolumeDoesNotCreateFrequencyIncident() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_NAGUL);
        User worker = user(1L, "worker", "Иван Работник", 101L);
        when(eventRepository.countByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqual(
                eq(1L), eq(List.of(WorkerActivityAction.REVIEW_NAGUL)), any(LocalDateTime.class)
        )).thenReturn(0L, 51L);

        service.evaluateSafely(event, worker);

        verify(incidentRepository, never()).save(any(WorkerRiskIncident.class));
    }

    @Test
    void dailyNagulLimitIsRaisedToOneHundred() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_NAGUL);
        User worker = user(1L, "worker", "Иван Работник", 101L);
        when(eventRepository.countByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqual(
                eq(1L), eq(List.of(WorkerActivityAction.REVIEW_NAGUL)), any(LocalDateTime.class)
        )).thenReturn(0L, 100L);
        when(incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                eq(1L),
                eq("NAGUL_DAY"),
                eq(WorkerRiskIncidentStatus.OPEN),
                eq(501L),
                any(LocalDateTime.class)
        )).thenReturn(false);
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> captor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals("NAGUL_DAY", captor.getValue().getRuleCode());
        assertEquals(true, captor.getValue().getDetails().contains("порог: 100"));
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
        assertEquals(true, seriesIncident.getDetails().contains("150 сек"));
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
    void manualPublicationDateChangeCreatesManagerReviewAndWorkerNotification() {
        WorkerRiskEvaluationService service = service();
        WorkerActivityEvent event = event(WorkerActivityAction.REVIEW_PUBLISH_DATE_UPDATE);
        event.setDetails(
                "previousPublishedDate=2026-07-15;newPublishedDate=2026-07-22;companyId=12;"
                        + "sourcePage=order-details;sourceEntry=worker-all;sourceSection=all;"
        );
        User worker = user(1L, "worker", "Иван Работник", 101L);

        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> {
            WorkerRiskIncident incident = invocation.getArgument(0);
            incident.setId(91L);
            return incident;
        });

        service.evaluateSafely(event, worker);

        ArgumentCaptor<WorkerRiskIncident> incidentCaptor = ArgumentCaptor.forClass(WorkerRiskIncident.class);
        verify(incidentRepository).save(incidentCaptor.capture());
        WorkerRiskIncident incident = incidentCaptor.getValue();
        assertEquals("WORKER_PUBLICATION_DATE_CHANGED", incident.getRuleCode());
        assertEquals(50, incident.getScore());
        assertEquals("Специалист изменил дату публикации", incident.getTitle());
        assertEquals(true, incident.getDetails().contains("2026-07-15"));
        assertEquals(true, incident.getDetails().contains("2026-07-22"));
        assertEquals(true, incident.getDetails().contains("Место: Детали заказа, вход: Специалист -> Все, раздел: Все"));
        verify(personalReminderService).createSystemReminderDueNow(
                eq(worker),
                eq("Изменение даты попало в риски"),
                anyString(),
                eq(WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT),
                eq(91L),
                eq(100L)
        );
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
        assertEquals("Блок аккаунта без попытки войти в него", captor.getValue().getTitle());
        assertEquals(true, captor.getValue().getDetails().contains("система не увидела попытку входа"));
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
        lenient().when(credentialPreparationRepository.findByWorkerUserIdAndScope(anyLong(), any()))
                .thenReturn(Optional.empty());
        lenient().when(appSettingService.getBoolean(
                AppSettingService.WORKER_RISK_EXPLANATION_AUTO_REQUEST_ENABLED,
                true
        )).thenReturn(true);
        lenient().when(appSettingService.getInt(
                AppSettingService.WORKER_RISK_EXPLANATION_DEADLINE_MINUTES,
                180
        )).thenReturn(180);
        return new WorkerRiskEvaluationService(
                eventRepository,
                incidentRepository,
                credentialPreparationRepository,
                personalReminderService,
                userService,
                telegramService,
                orderRepository,
                transactionManager(),
                appSettingService,
                riskEventService
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

    private WorkerCredentialPreparation preparation(
            WorkerCredentialPreparationScope scope,
            Long reviewId,
            Long botId,
            LocalDateTime lastCopyAt
    ) {
        WorkerCredentialPreparation preparation = new WorkerCredentialPreparation();
        preparation.setWorkerUserId(1L);
        preparation.setScope(scope);
        preparation.setReviewId(reviewId);
        preparation.setBotId(botId);
        preparation.setLoginCopiedAt(lastCopyAt.minusSeconds(5));
        preparation.setPasswordCopiedAt(lastCopyAt);
        return preparation;
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
