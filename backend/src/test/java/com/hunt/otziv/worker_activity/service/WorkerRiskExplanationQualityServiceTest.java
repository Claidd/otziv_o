package com.hunt.otziv.worker_activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkerRiskExplanationQualityServiceTest {

    @Test
    void genericAnswerIsRejectedWithoutCallingDeepSeek() {
        AiProviderRouter providerRouter = mock(AiProviderRouter.class);
        WorkerRiskExplanationQualityService service = new WorkerRiskExplanationQualityService(
                providerRouter,
                mock(ReputationAiProperties.class),
                new ObjectMapper(),
                mock(AppSettingService.class),
                mock(OrderRepository.class),
                mock(WorkerActivityEventRepository.class)
        );
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setTitle("Работа не выполнена за день");

        WorkerRiskExplanationQualityService.Result result = service.assess(incident, "большой заказ");

        assertEquals(WorkerRiskExplanationQuality.PARTIAL, result.quality());
        verify(providerRouter, never()).activeProvider();
    }
}
