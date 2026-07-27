package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;

class ManagerReportReviewQualityServiceTest {

    private AiProviderRouter providerRouter;
    private AppSettingService settings;
    private ManagerReportReviewQualityService service;

    @BeforeEach
    void setUp() {
        providerRouter = mock(AiProviderRouter.class);
        settings = mock(AppSettingService.class);
        when(providerRouter.activeProviderName()).thenReturn("local");
        when(settings.getInt("manager.report-review.question-count", 2)).thenReturn(2);
        when(settings.getInt("manager.report-review.max-question-count", 8)).thenReturn(8);
        when(settings.getInt("manager.report-review.max-answer-characters", 420)).thenReturn(420);
        when(settings.getInt("manager.report-review.max-plan-characters", 600)).thenReturn(600);
        when(settings.getInt("manager.report-review.copy-gram-size", 4)).thenReturn(4);
        when(settings.getInt("manager.report-review.copy-similarity-percent", 65)).thenReturn(65);
        when(settings.getInt("manager.report-review.question-generation-max-tokens", 8000)).thenReturn(8000);
        when(settings.getInt("manager.report-review.question-generation-retry-max-tokens", 12000)).thenReturn(12000);
        service = new ManagerReportReviewQualityService(providerRouter, new ObjectMapper(), settings);
    }

    @Test
    void rejectsGenericAcknowledgement() {
        var result = service.assessAnswer(
                "Компания Ромашка: клиент спросил о сроке, менеджер ответил «Хорошо».",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Почему ответ не решил вопрос клиента?",
                        List.of("не назван срок")
                ),
                "Понял, исправлю",
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("слишком общий");
        assertThat(result.clarificationQuestion()).isNotBlank();
    }

    @Test
    void createsFallbackQuestionsWhenDeepSeekIsUnavailable() {
        var questions = service.questions("Персональный отчёт");

        assertThat(questions).hasSize(2);
        assertThat(questions).allMatch(question -> !question.question().isBlank());
    }

    @Test
    void marksQuestionGenerationAsPendingInsteadOfFreezingFallbackQuestions() {
        var generation = service.generateQuestions("Персональный отчёт", 3);

        assertThat(generation.aiVerified()).isFalse();
        assertThat(generation.questions()).isEmpty();
        assertThat(generation.reason()).contains("недоступ");
    }

    @Test
    void doesNotInventFallbackQuestionWhenDeepSeekConfirmsThereAreNoMandatoryIssues() {
        AiProvider provider = mock(AiProvider.class);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(settings.getInt("manager.report-review.ai-timeout-seconds", 25)).thenReturn(25);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                "{\"questions\":[]}",
                "deepseek",
                100,
                10
        ));

        var questions = service.questions(
                "Совет без штрафа: сначала подтвердить решение клиента",
                2
        );

        assertThat(questions).isEmpty();
    }

    @Test
    void retriesTruncatedQuestionJsonWithLargerTokenBudget() {
        AiProvider provider = mock(AiProvider.class);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(settings.getInt("manager.report-review.ai-timeout-seconds", 25)).thenReturn(25);
        when(provider.generate(any(AiRequest.class)))
                .thenReturn(new AiResponse(
                        "{\"questions\":[{\"question\":\"Оборванный вопрос",
                        "deepseek",
                        100,
                        1000
                ))
                .thenReturn(new AiResponse(
                        """
                                {"questions":[{"question":"Что произошло в карточке Ромашка?",
                                "expectedPoints":["назвать действие","объяснить исправление"],
                                "sourceTaskIds":[3545]}]}
                                """,
                        "deepseek",
                        100,
                        80
                ));

        var generation = service.generateQuestions("Персональный отчёт", 8);

        ArgumentCaptor<AiRequest> requests = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider, times(2)).generate(requests.capture());
        assertThat(generation.aiVerified()).isTrue();
        assertThat(generation.questions()).hasSize(1);
        assertThat(generation.questions().getFirst().sourceTaskIds()).containsExactly(3545L);
        assertThat(requests.getAllValues())
                .extracting(AiRequest::maxTokens)
                .containsExactly(8000, 12000);
    }

    @Test
    void doesNotAcceptAnswerWhenAutomaticVerificationIsUnavailable() {
        var result = service.assessAnswer(
                "Компания Ромашка: клиент спросил о сроке, менеджер не ответил.",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Что произошло у компании Ромашка и что нужно было сделать?",
                        List.of("клиент спросил о сроке", "ответить клиенту")
                ),
                "Клиент спросил о сроке, нужно было назвать срок и следующий шаг.",
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("не засчитан");
    }

    @Test
    void disablesThinkingForShortStructuredAnswerAssessment() {
        AiProvider provider = mock(AiProvider.class);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(settings.getInt("manager.report-review.ai-timeout-seconds", 25)).thenReturn(25);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                """
                        {"accepted":true,"score":90,"reason":"Назван конкретный случай и правильное действие",
                         "clarificationQuestion":"","missingPoints":[],"authenticityRisk":false,
                         "authenticityQuestion":""}
                        """,
                "deepseek",
                100,
                30
        ));

        var result = service.assessAnswer(
                "Компания Ромашка: клиент спросил о сроке, менеджер не ответил.",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Что произошло у компании Ромашка и что нужно было сделать?",
                        List.of("клиент спросил о сроке", "ответить клиенту")
                ),
                "Клиент спросил о сроке, нужно было назвать срок и следующий шаг.",
                false
        );

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());

        assertThat(result.accepted()).isTrue();
        assertThat(request.getValue().thinkingEnabled()).isFalse();
        assertThat(request.getValue().jsonObject()).isTrue();
        assertThat(request.getValue().maxTokens()).isEqualTo(650);
    }

    @Test
    void rejectsNominallyAcceptedAnswerWhenAssessmentStillContainsACaveat() {
        AiProvider provider = mock(AiProvider.class);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(settings.getInt("manager.report-review.ai-timeout-seconds", 25)).thenReturn(25);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                """
                        {"accepted":true,"score":82,
                         "reason":"Случай назван, однако не указан способ проверки результата",
                         "clarificationQuestion":"Как проверите результат?","missingPoints":[],
                         "authenticityRisk":false,"authenticityQuestion":""}
                        """,
                "deepseek",
                100,
                30
        ));

        var result = service.assessAnswer(
                "Клиент не получил подтверждение результата.",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Что сделаете и как проверите результат?",
                        List.of("действие", "проверка результата")
                ),
                "Отвечу клиенту и закрою карточку.",
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.clarificationQuestion()).contains("проверите");
    }

    @Test
    void removesInventedSupervisorProcedureFromAssessment() {
        AiProvider provider = mock(AiProvider.class);
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
        when(settings.getInt("manager.report-review.ai-timeout-seconds", 25)).thenReturn(25);
        when(provider.generate(any(AiRequest.class))).thenReturn(new AiResponse(
                """
                        {"accepted":false,"score":55,
                         "reason":"Нужна проверка ответа через супервизора",
                         "clarificationQuestion":"Кто из супервизоров подтвердит результат?",
                         "missingPoints":["контроль результата"],"authenticityRisk":false,
                         "authenticityQuestion":""}
                        """,
                "deepseek",
                100,
                30
        ));

        var result = service.assessAnswer(
                "Менеджер должен проверить собственную карточку.",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Что вы проверите в своей карточке?",
                        List.of("запрос клиента", "содержательный ответ", "следующий шаг")
                ),
                "Открою чат, сопоставлю запрос с моим ответом и проверю следующий шаг.",
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).doesNotContainIgnoringCase("супервизор");
        assertThat(result.clarificationQuestion()).doesNotContainIgnoringCase("супервизор");
        assertThat(result.clarificationQuestion())
                .contains("самостоятельно")
                .doesNotContainIgnoringCase("чек-лист");
    }

    @Test
    void rejectsLongVerbatimCopyFromAuditBeforeCallingDeepSeek() {
        String copied = "Компания Ромашка. Менеджер ответил только «Хорошо», "
                + "хотя нужно было подтвердить оплату и назвать дальнейшее действие по заказу.";
        var result = service.assessAnswer(
                "Подтверждённая проблема. " + copied,
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Компания Ромашка: клиент сообщил об оплате. Что было сделано и как правильно?",
                        List.of("название компании", "фактический ответ", "правильное действие")
                ),
                copied,
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.provider()).isEqualTo("copy-check");
        assertThat(result.reason()).contains("дословно");
    }

    @Test
    void rejectsEssayLengthAnswerAndRequestsShortOwnWordsVersion() {
        String longAnswer = "Компания Ромашка. " + "Я проверю карточку и отвечу правильно. ".repeat(20);

        var result = service.assessAnswer(
                "Отчёт",
                new ManagerReportReviewQualityService.ReviewQuestion(
                        "Разберите случай компании Ромашка.",
                        List.of("компания", "действие", "правильный ответ")
                ),
                longAnswer,
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.provider()).isEqualTo("length-rules");
        assertThat(result.clarificationQuestion()).contains("420");
    }

    @Test
    void keepsFullAnswerHistoryIncludingRejectedAttempts() {
        String json = service.appendAnswer(
                null,
                new ManagerReportReviewQualityService.ReviewAnswer(
                        0,
                        "Вопрос",
                        "Понял",
                        false,
                        10,
                        "Слишком общий ответ",
                        "rules"
                )
        );
        json = service.appendAnswer(
                json,
                new ManagerReportReviewQualityService.ReviewAnswer(
                        0,
                        "Вопрос",
                        "Проверю переписку и зафиксирую результат",
                        true,
                        80,
                        "Ответ конкретный",
                        "deepseek"
                )
        );

        assertThat(json).contains("Понял", "Проверю переписку", "\"accepted\":false", "\"accepted\":true");
    }
}
