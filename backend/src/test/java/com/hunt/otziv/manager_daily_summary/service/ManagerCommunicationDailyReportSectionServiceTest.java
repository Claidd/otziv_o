package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerCommunicationDailyReportSectionServiceTest {

    @Mock
    private ClientChatUnansweredItemRepository repository;
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
                providerRouter,
                new ObjectMapper(),
                appSettingService
        );
    }

    @Test
    void reportFindsGenericRepliesFastClicksAndAddsDeepSeekCoaching() {
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

        when(repository.findDailyReportItems(
                eq(10L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(ClientChatUnansweredStatus.OPEN)
        )).thenReturn(List.of(first, second));
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
                  "problems":["Ответ «Проверим» слишком общий"],
                  "advice":["Назовите срок и конкретное действие"]
                }
                """,
                "deepseek",
                100,
                80
        ));

        String report = service.format(10L, date);

        assertTrue(report.contains("формальных/неполных <b>1</b>"));
        assertTrue(report.contains("быстрых серийных действий ≤3 сек: <b>2</b>"));
        assertTrue(report.contains("ответ «Проверим»"));
        assertTrue(report.contains("Разбор DeepSeek"));
        assertTrue(report.contains("Назовите срок и конкретное действие"));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        assertTrue(request.getValue().userPrompt().contains("Когда исправите ошибку"));
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
        if (replyText != null) {
            ClientChatMessage reply = new ClientChatMessage();
            reply.setMessageText(replyText);
            item.setResolutionMessage(reply);
        }
        return item;
    }
}
