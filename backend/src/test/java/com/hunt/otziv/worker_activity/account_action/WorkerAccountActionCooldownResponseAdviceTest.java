package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.config.api.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class WorkerAccountActionCooldownResponseAdviceTest {
    private final WorkerAccountActionCooldownRepository repository = mock(WorkerAccountActionCooldownRepository.class);

    @Test
    void refreshesSuccessfulJsonNoContentAndBusinessErrorResponses() throws Exception {
        when(repository.currentState("specialist")).thenReturn(WorkerAccountActionCooldownState.of(60, 61000, 13000));
        var advice = new WorkerAccountActionCooldownResponseAdvice(repository);
        var mvc = MockMvcBuilders.standaloneSetup(new SlowActionController())
                .setControllerAdvice(advice, new ApiExceptionHandler())
                .addInterceptors(advice).build();
        for (var scenario : Map.of("/change", 200, "/block", 204, "/unavailable", 409).entrySet()) {
            var response = mvc.perform(post(scenario.getKey())).andReturn().getResponse();
            assertThat(response.getStatus()).as(scenario.getKey()).isEqualTo(scenario.getValue());
            assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("true");
            var serverNow = Instant.parse(response.getHeader("X-Worker-Account-Action-Server-Now"));
            var availableAt = Instant.parse(response.getHeader("X-Worker-Account-Action-Available-At"));
            assertThat(availableAt.toEpochMilli() - serverNow.toEpochMilli()).isEqualTo(48_000L);
        }
        verify(repository, times(3)).currentState("specialist");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void responseAdviceKeepsDisabledFlagAndDurationForEveryResponseKind() throws Exception {
        when(repository.currentState("specialist")).thenReturn(WorkerAccountActionCooldownState.of(false, 60, 61000, 13000));
        var advice = new WorkerAccountActionCooldownResponseAdvice(repository);
        var mvc = MockMvcBuilders.standaloneSetup(new SlowActionController())
                .setControllerAdvice(advice, new ApiExceptionHandler())
                .addInterceptors(advice).build();
        for (var scenario : Map.of("/change", 200, "/block", 204, "/unavailable", 409).entrySet()) {
            var response = mvc.perform(post(scenario.getKey()).param("cooldownEnabled", "false"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as(scenario.getKey()).isEqualTo(scenario.getValue());
            assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("false");
            assertThat(response.getHeader("X-Worker-Account-Action-Duration-Seconds")).isEqualTo("60");
            assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEmpty();
            assertThat(Instant.parse(response.getHeader("X-Worker-Account-Action-Server-Now")))
                    .isEqualTo(Instant.ofEpochMilli(13000));
        }
        verify(repository, times(3)).currentState("specialist");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void disablingDuringAcceptedActionWinsForSuccessfulNoContentAndBusinessErrorResponses() throws Exception {
        when(repository.currentState("specialist")).thenReturn(WorkerAccountActionCooldownState.of(false, 180, 61000, 13000));
        var advice = new WorkerAccountActionCooldownResponseAdvice(repository);
        var mvc = MockMvcBuilders.standaloneSetup(new SlowActionController())
                .setControllerAdvice(advice, new ApiExceptionHandler())
                .addInterceptors(advice).build();

        for (var scenario : Map.of("/change", 200, "/block", 204, "/unavailable", 409).entrySet()) {
            // The controller accepted with the old enabled, 60-second policy before the admin disabled it.
            var response = mvc.perform(post(scenario.getKey())).andReturn().getResponse();

            assertThat(response.getStatus()).as(scenario.getKey()).isEqualTo(scenario.getValue());
            assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("false");
            assertThat(response.getHeader("X-Worker-Account-Action-Duration-Seconds")).isEqualTo("180");
            assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEmpty();
            assertThat(Instant.parse(response.getHeader("X-Worker-Account-Action-Server-Now")))
                    .isEqualTo(Instant.ofEpochMilli(13000));
        }
        verify(repository, times(3)).currentState("specialist");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void failedFinalStatusReadPreservesSuccessAndOriginalStateTimestamp() throws Exception {
        when(repository.currentState("specialist")).thenThrow(new IllegalStateException("Database temporarily unavailable"));
        var advice = new WorkerAccountActionCooldownResponseAdvice(repository);
        var mvc = MockMvcBuilders.standaloneSetup(new SlowActionController())
                .setControllerAdvice(advice, new ApiExceptionHandler())
                .addInterceptors(advice).build();

        var response = mvc.perform(post("/change")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"botId\":25}");
        assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("true");
        assertThat(response.getHeader("X-Worker-Account-Action-Duration-Seconds")).isEqualTo("60");
        assertThat(Instant.parse(response.getHeader("X-Worker-Account-Action-Available-At")))
                .isEqualTo(Instant.ofEpochMilli(61000));
        assertThat(Instant.parse(response.getHeader("X-Worker-Account-Action-Server-Now")))
                .isEqualTo(Instant.ofEpochMilli(1000));
        verify(repository).currentState("specialist");
        verifyNoMoreInteractions(repository);
    }

    @RestController
    static class SlowActionController {
        @PostMapping("/change")
        Map<String, Long> change(HttpServletRequest request) {
            accepted(request);
            return Map.of("botId", 25L);
        }

        @PostMapping("/block")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void block(HttpServletRequest request) {
            accepted(request);
        }

        @PostMapping("/unavailable")
        void unavailable(HttpServletRequest request) {
            accepted(request);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нет доступных аккаунтов");
        }

        void accepted(HttpServletRequest request) {
            var state = WorkerAccountActionCooldownState.of(
                    !"false".equals(request.getParameter("cooldownEnabled")), 60, 61000, 1000);
            request.setAttribute(WorkerAccountActionCooldownService.ACCEPTED_REQUEST_ATTRIBUTE,
                    new WorkerAccountActionCooldownService.AcceptedAction(
                            "specialist", state));
        }
    }
}
