package com.hunt.otziv.external_review_checks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerRequest;
import com.hunt.otziv.external_review_checks.repository.ReviewExternalCheckRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class ExternalReviewCheckKillSwitchTest {

    @Test
    void disabledManualCheckFailsBeforeReadingOrWritingDomainState() {
        ReviewExternalCheckRepository checkRepository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();

        ExternalReviewCheckService service = new ExternalReviewCheckService(
                checkRepository,
                workerClient,
                screenshotStorage,
                properties,
                runtimeSwitch,
                transactions
        );

        assertThatThrownBy(() -> service.createManualCheck(10L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(
                checkRepository,
                workerClient,
                screenshotStorage,
                transactions
        );
    }

    @Test
    void disabledProcessorLeavesQueuedCheckUntouched() {
        ReviewExternalCheckRepository checkRepository = mock(ReviewExternalCheckRepository.class);
        ExternalReviewWorkerClient workerClient = mock(ExternalReviewWorkerClient.class);
        ExternalReviewScreenshotStorage screenshotStorage = mock(ExternalReviewScreenshotStorage.class);
        ExternalReviewCheckTransactionService transactions = mock(ExternalReviewCheckTransactionService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();

        ExternalReviewCheckService service = new ExternalReviewCheckService(
                checkRepository,
                workerClient,
                screenshotStorage,
                properties,
                runtimeSwitch,
                transactions
        );

        service.processOne(42L);

        verifyNoInteractions(
                checkRepository,
                workerClient,
                screenshotStorage,
                transactions
        );
    }

    @Test
    void disabledHttpClientCannotBeCalledDirectly() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        ExternalReviewWorkerClient client = new ExternalReviewWorkerClient(
                restTemplate,
                properties,
                runtimeSwitch
        );
        ExternalReviewWorkerRequest request = new ExternalReviewWorkerRequest(
                1L,
                2L,
                "YANDEX",
                "https://example.test/card",
                "review text"
        );

        assertThatThrownBy(() -> client.verify(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void disabledSchedulerDoesNotEnterEitherProcessingPhase() {
        ExternalReviewCheckService service = mock(ExternalReviewCheckService.class);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);

        new ExternalReviewCheckScheduler(service, runtimeSwitch).tick();

        verifyNoInteractions(service);
    }
}
