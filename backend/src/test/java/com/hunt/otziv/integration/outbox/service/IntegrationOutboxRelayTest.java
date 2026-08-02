package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegrationOutboxRelayTest {

    private final IntegrationOutboxTransactionService transactions =
            mock(IntegrationOutboxTransactionService.class);
    private final IntegrationOutboxHandlerRegistry handlers =
            mock(IntegrationOutboxHandlerRegistry.class);
    private final IntegrationOutboxProperties properties =
            new IntegrationOutboxProperties();
    private final IntegrationOutboxMetrics metrics =
            new IntegrationOutboxMetrics(new SimpleMeterRegistry(), properties);
    private final IntegrationOutboxBackoffPolicy backoff =
            new IntegrationOutboxBackoffPolicy(properties, () -> 0.5d);

    private IntegrationOutboxRelay relay;

    @BeforeEach
    void setUp() {
        properties.setBatchSize(1);
        when(handlers.registeredEventTypes()).thenReturn(Set.of("test.event"));
        relay = new IntegrationOutboxRelay(
                transactions,
                handlers,
                backoff,
                properties,
                metrics
        );
    }

    @Test
    void disabledRelayPerformsNoDatabaseWork() {
        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.enabled()).isFalse();
        verify(transactions, never()).claimNext(any());
        verify(transactions, never()).markExpiredFinalAttemptsDead(any());
    }

    @Test
    void enabledRelayWithNoRegisteredHandlersPerformsNoDatabaseWork() {
        properties.setRelayEnabled(true);
        when(handlers.registeredEventTypes()).thenReturn(Set.of());

        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.enabled()).isTrue();
        assertThat(report.claimed()).isZero();
        verify(transactions, never()).claimNext(any());
        verify(transactions, never()).markExpiredFinalAttemptsDead(any());
    }

    @Test
    void successfulDeliveryUsesFencedCompletion() throws Exception {
        properties.setRelayEnabled(true);
        IntegrationOutboxRepository.Claim claim = claim(1, 5);
        when(transactions.claimNext(Set.of("test.event"))).thenReturn(Optional.of(claim));
        when(transactions.markSucceeded(claim)).thenReturn(true);

        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.fenced()).isZero();
        verify(handlers).dispatch(claim);
        verify(transactions).markSucceeded(claim);
    }

    @Test
    void retryableFailureUsesBoundedBackoff() throws Exception {
        properties.setRelayEnabled(true);
        IntegrationOutboxRepository.Claim claim = claim(2, 5);
        when(transactions.claimNext(Set.of("test.event"))).thenReturn(Optional.of(claim));
        doThrow(IntegrationOutboxHandlerRegistry.DispatchException.retryable(
                "HANDLER_RETRYABLE_FAILURE",
                new IllegalStateException("remote detail must not be logged")
        )).when(handlers).dispatch(claim);
        when(transactions.markRetry(
                any(IntegrationOutboxRepository.Claim.class),
                any(Duration.class),
                any(String.class)
        )).thenReturn(true);

        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.retried()).isEqualTo(1);
        verify(transactions).markRetry(
                claim,
                backoff.delayForAttempt(2),
                "HANDLER_RETRYABLE_FAILURE"
        );
    }

    @Test
    void finalAttemptMovesDirectlyToDead() throws Exception {
        properties.setRelayEnabled(true);
        IntegrationOutboxRepository.Claim claim = claim(5, 5);
        when(transactions.claimNext(Set.of("test.event"))).thenReturn(Optional.of(claim));
        doThrow(IntegrationOutboxHandlerRegistry.DispatchException.retryable(
                "HANDLER_RETRYABLE_FAILURE",
                new IllegalStateException("remote detail")
        )).when(handlers).dispatch(claim);
        when(transactions.markDead(claim, "HANDLER_RETRYABLE_FAILURE")).thenReturn(true);

        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.dead()).isEqualTo(1);
        verify(transactions).markDead(claim, "HANDLER_RETRYABLE_FAILURE");
        verify(transactions, never()).markRetry(any(), any(), any());
    }

    @Test
    void lostProcessingTokenFencesStaleCompletion() throws Exception {
        properties.setRelayEnabled(true);
        IntegrationOutboxRepository.Claim claim = claim(1, 5);
        when(transactions.claimNext(Set.of("test.event"))).thenReturn(Optional.of(claim));
        when(transactions.markSucceeded(claim)).thenReturn(false);

        IntegrationOutboxRelay.RelayReport report = relay.runOnce();

        assertThat(report.fenced()).isEqualTo(1);
        assertThat(report.succeeded()).isZero();
    }

    private IntegrationOutboxRepository.Claim claim(int attempt, int maxAttempts) {
        return new IntegrationOutboxRepository.Claim(
                1L,
                UUID.randomUUID().toString(),
                "order",
                "42",
                3L,
                "test.event",
                "{}",
                attempt,
                maxAttempts,
                UUID.randomUUID().toString(),
                LocalDateTime.now().plusMinutes(2)
        );
    }
}
