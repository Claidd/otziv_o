package com.hunt.otziv.manager_daily_summary.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerTeamDailyReportSectionServiceTest {

    @Mock
    private AiProviderRouter providerRouter;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private AiProvider provider;

    private ManagerTeamDailyReportSectionService service;

    @BeforeEach
    void setUp() {
        service = new ManagerTeamDailyReportSectionService(
                providerRouter,
                new ObjectMapper(),
                appSettingService
        );
    }

    @Test
    void buildsComparativeTeamAnalysisFromManagerEvidence() {
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
                  "overallAssessment":"Команда продвинулась по карточкам, но остаток распределён неравномерно.",
                  "teamStrengths":["Анжелика подтверждает большинство отправленных ответов."],
                  "employees":[
                    {
                      "managerName":"Анжелика Б.",
                      "status":"под наблюдением",
                      "progress":"Сохранила положительную динамику к прошлому дню.",
                      "problems":["Есть один клиентский диалог без ответа."],
                      "evidence":["Осталось 2 карточки, из них 1 сообщение без ответа."],
                      "actions":["До новой работы ответить на открытое сообщение и зафиксировать отправку."]
                    },
                    {
                      "managerName":"Вика Ц.",
                      "status":"нужна помощь",
                      "progress":"Обработана половина текущей очереди.",
                      "problems":["Большой остаток сообщений без ответа.","Нужна выборочная проверка быстрых серий."],
                      "evidence":["Осталось 67 карточек и 13 сообщений без ответа.","За 7 дней 51 быстрая серия."],
                      "actions":["В начале смены разобрать 13 сообщений без ответа.","Руководителю проверить по одной карточке из последних быстрых серий."]
                    },
                    {
                      "managerName":"Выдуманный сотрудник",
                      "status":"нужна помощь",
                      "progress":"Нет",
                      "problems":["Нет"],
                      "evidence":[],
                      "actions":[]
                    }
                  ],
                  "leaderActions":["Сначала снять клиентский хвост Вики, затем провести выборочный аудит быстрых серий."]
                }
                """,
                "deepseek",
                300,
                220
        ));

        String report = service.section(List.of(
                manager("Анжелика Б.", 47, 49, 46, 2, 1, 1,
                        "7 дней: быстрых серий 12 (66 действий)"),
                manager("Вика Ц.", 42, 140, 70, 67, 3, 13,
                        "7 дней: быстрых серий 51 (317 действий)")
        ));

        assertTrue(report.contains("<b>Вывод.</b> Команда продвинулась"));
        assertTrue(report.contains("<b>Анжелика Б. · под наблюдением</b>"));
        assertTrue(report.contains("<b>Вика Ц. · нужна помощь</b>"));
        assertTrue(report.contains("<b>Основание:</b> За 7 дней 51 быстрая серия."));
        assertTrue(report.contains("<b>Действия руководителя</b>"));
        assertFalse(report.contains("Выдуманный сотрудник"));

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        assertTrue(request.getValue().userPrompt().contains("\"managerName\":\"Вика Ц.\""));
        assertTrue(request.getValue().userPrompt().contains("быстрых серий 51"));
        assertTrue(request.getValue().userPrompt().contains("\"tasksOpen\":67"));
        assertTrue(request.getValue().systemPrompt().contains("кто стал работать лучше, кто хуже"));
        assertTrue(request.getValue().systemPrompt().contains("кому принадлежат просрочки"));
        assertTrue(request.getValue().maxTokens() == 3500);
        assertTrue(Boolean.FALSE.equals(request.getValue().thinkingEnabled()));
    }

    @Test
    void fallsBackToConcreteActionsWhenAiIsDisabled() {
        when(appSettingService.getBoolean(AppSettingService.MANAGER_SUMMARY_AI_ANALYSIS_ENABLED, true))
                .thenReturn(false);

        String report = service.section(List.of(
                manager("Вика Ц.", 42, 140, 70, 67, 3, 13, "")
        ));

        assertTrue(report.contains("<b>Вика Ц. · нужна помощь</b>"));
        assertTrue(report.contains("есть сообщения без ответа: 13"));
        assertTrue(report.contains("открыть каждое сообщение без ответа"));
        assertTrue(report.contains("<b>Действия руководителя</b>"));
    }

    private ManagerTeamDailyReportSectionService.ManagerFacts manager(
            String name,
            int score,
            long total,
            long completed,
            long open,
            long overdue,
            long unanswered,
            String communicationMetrics
    ) {
        return new ManagerTeamDailyReportSectionService.ManagerFacts(
                name,
                score,
                score > 45 ? "+5" : "0",
                score > 45 ? "+4" : "-1",
                total,
                completed,
                Math.max(0, total - completed - open),
                open,
                overdue,
                0,
                unanswered,
                completed,
                open,
                10,
                5,
                3600,
                "<b>Вывод.</b> Есть конкретные примеры для проверки.",
                communicationMetrics,
                "<b>Вывод.</b> Новых рисков нет.",
                "Риски: назначено 0"
        );
    }
}
