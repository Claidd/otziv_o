package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerCommunicationDailyReportSectionServiceTest {

    @Mock
    private ClientChatUnansweredItemRepository repository;
    @Mock
    private ClientChatMessageRepository messageRepository;
    @Mock
    private AiProviderRouter providerRouter;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private AiProvider provider;

    private ManagerCommunicationDailyReportSectionService service;

    @BeforeEach
    void setUp() {
        service = new ManagerCommunicationDailyReportSectionService(
                repository,
                messageRepository,
                providerRouter,
                new ObjectMapper(),
                appSettingService
        );
    }

    @Test
    void reportAuditsManagerClientMessageSeriesWithFullContent() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        ClientChatUnansweredItem first = item(
                1L,
                LocalDateTime.of(2026, 7, 25, 12, 0, 0),
                "Когда исправите ошибку в отзыве?",
                "Проверим"
        );
        first.setReplyQuality(ClientChatReplyQuality.PARTIAL);
        first.setReplyQualityReason("На проблему не указан следующий шаг");

        ClientChatUnansweredItem second = item(
                2L,
                LocalDateTime.of(2026, 7, 25, 12, 0, 2),
                "Добавьте ещё пять отзывов",
                null
        );
        second.setResolutionType(ClientChatResolutionType.ACTION_COMPLETED);
        second.setAuditRequired(true);
        second.setManualOverride(false);
        second.setResolutionComment("Пять отзывов добавлены");
        ClientChatUnansweredItem third = item(
                3L,
                LocalDateTime.of(2026, 7, 25, 12, 0, 3),
                "Это сообщение только для сведения",
                null
        );
        third.setResolutionType(ClientChatResolutionType.NO_RESPONSE_NEEDED);
        third.setResolutionComment("Клиент не задавал вопрос и не просил выполнить действие");

        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of(first, second, third));
        when(repository.findManagerResolvedForDailyAudit(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any()
        )).thenReturn(List.of(first, second, third));
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getInt(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_TIMEOUT_SECONDS, 30))
                .thenReturn(30);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                """
                {
                  "overallAssessment":"Менеджеру важно давать клиенту понятный следующий шаг.",
                  "strengths":["Карточки обработаны в течение дня"],
                  "findings":[{
                    "caseId":1,
                    "classification":"CONFIRMED_PROBLEM",
                    "confidence":0.96,
                    "company":"Салон Тест",
                    "title":"Формальный ответ",
                    "evidence":"Клиент: Когда исправите? / Менеджер: Проверим",
                    "verdict":"Клиент не получил срок",
                    "recommendation":"Ответить: проверю отзыв сегодня и вернусь с результатом до 17:00"
                  }]
                }
                """,
                "deepseek",
                100,
                80
        ));

        String report = service.format(10L, date);

        assertTrue(report.contains("формальных/неполных 1"));
        assertTrue(report.contains("серий для содержательной проверки 1 (3 сообщений)"));
        assertTrue(report.contains("Клиент: Когда исправите? / Менеджер: Проверим"));
        assertTrue(report.contains("<b>Компания:</b> Салон Тест"));
        assertTrue(report.contains("<b>Вывод.</b>"));
        assertTrue(report.contains("<b>Как исправить:</b>"));
        assertTrue(report.contains("вернусь с результатом до 17:00"));
        assertFalse(report.contains("<b>Проверяемые примеры</b>"));
        assertTrue(report.contains("<b>🔎 Проверка серий обработки клиентских сообщений</b>"));
        assertTrue(report.contains("Напоминания специалистам и обычные изменения статусов исключены"));
        assertTrue(report.contains("<b>Важно.</b> Скорость обработки сама по себе не считается нарушением"));
        assertTrue(report.contains("Серия 1 — 25.07 12:00:00"));
        assertTrue(report.contains("Клиент: «Когда исправите ошибку в отзыве?»"));
        assertTrue(report.contains("Ответ менеджера: «Проверим»"));
        assertTrue(report.contains("Результат: действие по сообщению выполнено"));
        assertTrue(report.contains("Результат: закрыто как не требующее ответа"));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        assertTrue(request.getValue().userPrompt().contains("Когда исправите ошибку"));
        assertTrue(request.getValue().userPrompt().contains("\"companyName\":\"Салон Тест\""));
        assertTrue(request.getValue().userPrompt().contains("todayClientMessageSeries"));
        assertTrue(request.getValue().userPrompt().contains("\"managerReply\":\"Проверим\""));
        assertFalse(request.getValue().userPrompt().contains("LAST_7_DAYS"));
        assertTrue(request.getValue().maxTokens() == 3000);
        assertTrue(Boolean.FALSE.equals(request.getValue().thinkingEnabled()));
    }

    @Test
    void doesNotBuildSeriesFromClosuresNotAttributedToTheManager() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        List<ClientChatUnansweredItem> closures = List.of(
                item(21L, date.atTime(14, 0, 0), "Сообщение один", "Ответ один"),
                item(22L, date.atTime(14, 0, 1), "Сообщение два", "Ответ два"),
                item(23L, date.atTime(14, 0, 2), "Сообщение три", "Ответ три")
        );
        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(closures);
        when(repository.findManagerResolvedForDailyAudit(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any()
        )).thenReturn(List.of());
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(false);

        String report = service.format(10L, date);

        assertTrue(report.contains("менеджером обработано клиентских сообщений 0"));
        assertTrue(report.contains("серий для содержательной проверки 0"));
        assertFalse(report.contains("<b>🔎 Проверка серий обработки клиентских сообщений</b>"));
    }

    @Test
    void sendsConversationWindowAndTreatsHelpfulAlternativeAsAdviceWithoutPenalty() {
        LocalDate date = LocalDate.of(2026, 7, 26);
        LocalDateTime clientAt = LocalDateTime.of(2026, 7, 25, 23, 56, 39);
        User auditedUser = User.builder()
                .id(20L)
                .username("vika")
                .fio("Вика Ц.")
                .build();
        Manager auditedManager = Manager.builder().id(2L).user(auditedUser).build();
        ClientChatUnansweredItem item = item(
                5L,
                LocalDateTime.of(2026, 7, 26, 0, 24, 47),
                "Ну тут тексты не подходят к фото. Публикуйте без фото пока что",
                null
        );
        item.setPlatform(ClientChatPlatform.WHATSAPP);
        item.setChatId("group-1");
        item.setManager(auditedManager);
        item.setLastClientMessageAt(clientAt);
        item.setReplyQuality(ClientChatReplyQuality.GOOD);
        item.setAuditRequired(false);

        ClientChatMessage earlierStaff = message(
                100L,
                auditedManager,
                ClientChatSenderRole.STAFF,
                ClientChatDirection.INCOMING,
                "Мария",
                clientAt.minusMinutes(4),
                "Посмотрите тексты: стоит их исправить или какие фото лучше добавить?"
        );
        ClientChatMessage client = message(
                101L,
                auditedManager,
                ClientChatSenderRole.CLIENT,
                ClientChatDirection.INCOMING,
                "Александр",
                clientAt,
                item.getLastMessageText()
        );
        ClientChatMessage reply = message(
                102L,
                auditedManager,
                ClientChatSenderRole.STAFF,
                ClientChatDirection.INCOMING,
                "Мария",
                clientAt.plusMinutes(28),
                "Может какие-то тексты подкорректировать под фото?"
        );
        ClientChatMessage followUp = message(
                103L,
                auditedManager,
                ClientChatSenderRole.CLIENT,
                ClientChatDirection.INCOMING,
                "Александр",
                clientAt.plusMinutes(32),
                "Нет, не нужно."
        );
        item.setLastClientMessage(client);
        item.setResolutionMessage(reply);

        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of(item));
        when(messageRepository.findByPlatformAndChatIdAndMessageAtBetweenOrderByMessageAtAscIdAsc(
                eq(ClientChatPlatform.WHATSAPP),
                eq("group-1"),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(earlierStaff, client, reply, followUp));
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getInt(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_TIMEOUT_SECONDS, 30))
                .thenReturn(30);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                """
                {
                  "overallAssessment":"Менеджер предложил клиенту полезную альтернативу.",
                  "strengths":["Диалог продолжен по существу"],
                  "findings":[{
                    "caseId":5,
                    "classification":"IMPROVEMENT_ONLY",
                    "confidence":0.93,
                    "company":"Салон Тест",
                    "title":"Сначала подтвердить решение клиента",
                    "evidence":"Клиент попросил публиковать без фото; сотрудник Мария предложила подкорректировать тексты",
                    "verdict":"Предложение полезно, но сначала стоило подтвердить выбранный вариант",
                    "recommendation":"Ответить: Хорошо, пока публикуем без фото. При желании поможем адаптировать тексты"
                  }]
                }
                """,
                "deepseek",
                100,
                80
        ));

        String report = service.format(10L, date);

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        String facts = request.getValue().userPrompt();
        assertTrue(facts.contains("Посмотрите тексты"));
        assertTrue(facts.contains("Нет, не нужно."));
        assertTrue(facts.contains("\"replySenderName\":\"Мария\""));
        assertTrue(facts.contains("\"replyAuthorAttribution\":\"INTERNAL_STAFF_UNVERIFIED\""));
        assertTrue(report.contains("<b>Совет без штрафа:"));
        assertFalse(report.contains("<b>Подтверждённая проблема:"));
    }

    @Test
    void confirmedLegacyReplyWithoutTextIsNotReportedAsNoReply() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        ClientChatUnansweredItem item = item(
                3L,
                LocalDateTime.of(2026, 7, 25, 13, 0),
                "В понедельник теперь уже хорошо?",
                null
        );
        item.setResolutionReasonCode("CONFIRMED_SEND");
        item.setReplyQuality(ClientChatReplyQuality.PARTIAL);
        item.setAuditRequired(true);

        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of(item));
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(false);

        String report = service.format(10L, date);

        assertTrue(report.contains("с подтверждённой отправкой 1"));
        assertTrue(report.contains("текст ответа недоступен 1"));
        assertTrue(report.contains("без зафиксированного ответа 0"));
        assertTrue(report.contains("отправлен через карточку"));
        assertFalse(report.contains("<b>Ответ менеджера:</b> не зафиксирован"));
    }

    @Test
    void stalePartialAssessmentForPdfAndShortThanksIsNotReportedAsProblem() {
        LocalDate date = LocalDate.of(2026, 7, 26);
        ClientChatUnansweredItem item = item(
                6L,
                LocalDateTime.of(2026, 7, 26, 16, 8),
                "Документ-2026-07-26 180739.pdf",
                "Спасибо"
        );
        item.setReplyQuality(ClientChatReplyQuality.PARTIAL);
        item.setReplyQualityReason("На вопрос или проблему дан слишком общий ответ");
        item.setAuditRequired(true);

        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of(item));
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(false);

        String report = service.format(10L, date);

        assertTrue(report.contains("формальных/неполных 0"));
        assertTrue(report.contains("ждут аудита 0"));
        assertTrue(report.contains("Не обнаружено закрытий без ответа или формальных ответов"));
        assertFalse(report.contains("<b>Проверяемые примеры</b>"));
        assertFalse(report.contains("слишком общий ответ"));
        assertFalse(report.contains("дополнить ответ конкретным результатом"));
    }

    @Test
    void excludesHistoricalClientMessageSeriesFromDailyAudit() {
        LocalDate reportDate = LocalDate.of(2026, 7, 27);
        ClientChatUnansweredItem first = item(
                11L,
                LocalDateTime.of(2026, 7, 25, 0, 5, 8),
                "Первое старое сообщение",
                "Первый старый ответ"
        );
        ClientChatUnansweredItem second = item(
                12L,
                LocalDateTime.of(2026, 7, 25, 0, 5, 9),
                "Второе старое сообщение",
                "Второй старый ответ"
        );
        ClientChatUnansweredItem third = item(
                13L,
                LocalDateTime.of(2026, 7, 25, 0, 5, 10),
                "Третье старое сообщение",
                "Третий старый ответ"
        );
        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of());
        when(repository.findManagerResolvedForDailyAudit(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any()
        )).thenReturn(List.of(first, second, third));

        String report = service.format(10L, reportDate);

        assertTrue(report.contains("недостаточно, чтобы оценить качество ответов менеджера"));
        assertTrue(report.contains("менеджером обработано клиентских сообщений 0"));
        assertTrue(report.contains("серий для содержательной проверки 0"));
        assertFalse(report.contains("<b>🔎 Проверка серий обработки клиентских сообщений</b>"));
        assertFalse(report.contains("25.07"));
        assertFalse(report.contains("Первое старое сообщение"));
        verify(repository).findManagerResolvedForDailyAudit(
                10L,
                reportDate.atStartOfDay(),
                reportDate.plusDays(1).atStartOfDay(),
                List.of(
                        ClientChatResolutionType.ANSWERED,
                        ClientChatResolutionType.NO_RESPONSE_NEEDED,
                        ClientChatResolutionType.ACTION_COMPLETED,
                        ClientChatResolutionType.ADMIN_OVERRIDE
                )
        );
        verify(provider, never()).generate(any(AiRequest.class));
    }

    private ClientChatMessage message(
            Long id,
            Manager manager,
            ClientChatSenderRole role,
            ClientChatDirection direction,
            String sender,
            LocalDateTime at,
            String text
    ) {
        ClientChatMessage message = new ClientChatMessage();
        message.setId(id);
        message.setPlatform(ClientChatPlatform.WHATSAPP);
        message.setChatId("group-1");
        message.setManager(manager);
        message.setSenderRole(role);
        message.setDirection(direction);
        message.setSenderName(sender);
        message.setMessageAt(at);
        message.setMessageText(text);
        return message;
    }

    private ClientChatUnansweredItem item(
            Long id,
            LocalDateTime closedAt,
            String clientText,
            String replyText
    ) {
        ClientChatUnansweredItem item = new ClientChatUnansweredItem();
        item.setId(id);
        item.setStatus(ClientChatUnansweredStatus.ANSWERED);
        item.setCreatedAt(closedAt.minusMinutes(10));
        item.setLastClientMessageAt(closedAt.minusMinutes(10));
        item.setClosedAt(closedAt);
        item.setLastMessageText(clientText);
        item.setResolutionType(ClientChatResolutionType.ANSWERED);
        item.setManualOverride(true);
        Company company = new Company();
        company.setId(100L + id);
        company.setTitle("Салон Тест");
        item.setCompany(company);
        if (replyText != null) {
            ClientChatMessage reply = new ClientChatMessage();
            reply.setMessageText(replyText);
            item.setResolutionMessage(reply);
        }
        return item;
    }
}
