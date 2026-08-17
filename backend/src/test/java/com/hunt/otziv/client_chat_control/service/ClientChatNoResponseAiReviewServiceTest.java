package com.hunt.otziv.client_chat_control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatNoResponseAiReviewServiceTest {

    @Mock private AiProviderRouter providerRouter;
    @Mock private AiProvider provider;
    @Mock private AppSettingService appSettingService;

    private ClientChatNoResponseAiReviewService service;

    @BeforeEach
    void setUp() {
        service = new ClientChatNoResponseAiReviewService(
                providerRouter,
                new ObjectMapper(),
                appSettingService
        );
    }

    @Test
    void confirmsOnlyHighConfidenceDeepSeekDecision() {
        stubAvailableDeepSeek();
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.TIMEOUT_SETTING,
                20
        )).thenReturn(12);
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.MINIMUM_CONFIDENCE_SETTING,
                90
        )).thenReturn(90);
        when(provider.generate(org.mockito.ArgumentMatchers.any(AiRequest.class))).thenReturn(new AiResponse(
                "{\"decision\":\"NO_RESPONSE_NEEDED\",\"confidence\":97,"
                        + "\"reason\":\"Самостоятельная благодарность\"}",
                "deepseek",
                24,
                18
        ));

        ClientChatNoResponseAiReviewService.Review review = service.review("Спасибо большое");

        assertTrue(review.checked());
        assertTrue(review.confirmed());
        assertEquals(97, review.confidence());
        assertEquals("deepseek", review.provider());
        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(provider).generate(request.capture());
        assertEquals("client-chat-no-response-review", request.getValue().task());
        assertTrue(request.getValue().jsonObject());
        assertFalse(request.getValue().thinkingEnabled());
        assertTrue(request.getValue().userPrompt().contains("Спасибо большое"));
    }

    @Test
    void lowConfidenceDecisionFailsClosed() {
        stubAvailableDeepSeek();
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.TIMEOUT_SETTING,
                20
        )).thenReturn(20);
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.MINIMUM_CONFIDENCE_SETTING,
                90
        )).thenReturn(90);
        when(provider.generate(org.mockito.ArgumentMatchers.any(AiRequest.class))).thenReturn(new AiResponse(
                "{\"decision\":\"NO_RESPONSE_NEEDED\",\"confidence\":75,"
                        + "\"reason\":\"Возможно, это прощание\"}",
                "deepseek",
                10,
                10
        ));

        ClientChatNoResponseAiReviewService.Review review = service.review("До завтра");

        assertTrue(review.checked());
        assertFalse(review.confirmed());
        assertTrue(review.reason().contains("не уверен"));
    }

    @Test
    void responseRequiredDecisionIsNeverConfirmed() {
        stubAvailableDeepSeek();
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.TIMEOUT_SETTING,
                20
        )).thenReturn(20);
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.MINIMUM_CONFIDENCE_SETTING,
                90
        )).thenReturn(90);
        when(provider.generate(org.mockito.ArgumentMatchers.any(AiRequest.class))).thenReturn(new AiResponse(
                "{\"decision\":\"RESPONSE_REQUIRED\",\"confidence\":99,"
                        + "\"reason\":\"Клиент задал вопрос о начале работы\"}",
                "deepseek",
                12,
                12
        ));

        ClientChatNoResponseAiReviewService.Review review =
                service.review("А мы начали работать с вами?");

        assertTrue(review.checked());
        assertFalse(review.confirmed());
        assertEquals("RESPONSE_REQUIRED", review.decision());
        assertEquals(99, review.confidence());
    }

    @Test
    void malformedResponseFailsClosedAsUnchecked() {
        stubAvailableDeepSeek();
        when(appSettingService.getInt(
                ClientChatNoResponseAiReviewService.TIMEOUT_SETTING,
                20
        )).thenReturn(20);
        when(provider.generate(org.mockito.ArgumentMatchers.any(AiRequest.class))).thenReturn(new AiResponse(
                "{\"answer\":\"yes\"}",
                "deepseek",
                5,
                5
        ));

        ClientChatNoResponseAiReviewService.Review review = service.review("Спасибо");

        assertFalse(review.checked());
        assertFalse(review.confirmed());
    }

    @Test
    void nonDeepSeekProviderIsNeverUsedForClosingDecision() {
        when(providerRouter.activeProviderName()).thenReturn("openai");

        ClientChatNoResponseAiReviewService.Review review = service.review("Спасибо");

        assertFalse(review.checked());
        assertFalse(review.confirmed());
        verify(providerRouter, never()).activeProvider();
        verify(appSettingService, never()).getInt(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private void stubAvailableDeepSeek() {
        when(providerRouter.activeProviderName()).thenReturn("deepseek");
        when(providerRouter.activeProviderAvailable()).thenReturn(true);
        when(providerRouter.activeProvider()).thenReturn(provider);
    }
}
