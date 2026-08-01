package com.hunt.otziv.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntegrationOutboxHandlerRegistryTest {

    @Test
    void dispatchesStronglyTypedPayloadWithStableEventId() throws Exception {
        AtomicReference<IntegrationOutboxEvent<TestPayload>> delivered =
                new AtomicReference<>();
        IntegrationOutboxHandler<TestPayload> handler = handler(delivered, true);
        IntegrationOutboxHandlerRegistry registry = new IntegrationOutboxHandlerRegistry(
                List.of(handler),
                new ObjectMapper()
        );

        registry.dispatch(claim("test.event", "{\"objectId\":42}"));

        assertThat(registry.registeredEventTypes()).containsExactly("test.event");
        assertThat(delivered.get().payload().objectId()).isEqualTo(42L);
        assertThat(delivered.get().attempt()).isEqualTo(1);
        assertThat(delivered.get().eventId()).isNotNull();
    }

    @Test
    void rejectsDuplicateAndMissingHandlers() {
        IntegrationOutboxHandler<TestPayload> first = handler(new AtomicReference<>(), true);
        IntegrationOutboxHandler<TestPayload> second = handler(new AtomicReference<>(), true);

        assertThatThrownBy(() -> new IntegrationOutboxHandlerRegistry(
                List.of(first, second),
                new ObjectMapper()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate outbox handler");

        IntegrationOutboxHandlerRegistry empty = new IntegrationOutboxHandlerRegistry(
                List.of(),
                new ObjectMapper()
        );
        assertThatThrownBy(() -> empty.dispatch(claim("missing.event", "{}")))
                .isInstanceOf(IntegrationOutboxHandlerRegistry.DispatchException.class)
                .satisfies(exception -> assertThat(
                        ((IntegrationOutboxHandlerRegistry.DispatchException) exception)
                                .retryable()
                ).isFalse());
    }

    @Test
    void mapsHandlerClassificationWithoutPersistingExceptionMessage() {
        IntegrationOutboxHandler<TestPayload> permanent = new IntegrationOutboxHandler<>() {
            @Override
            public String eventType() {
                return "test.event";
            }

            @Override
            public Class<TestPayload> payloadType() {
                return TestPayload.class;
            }

            @Override
            public void handle(IntegrationOutboxEvent<TestPayload> event) {
                throw new IllegalArgumentException("sensitive remote response");
            }

            @Override
            public boolean isRetryable(Exception failure) {
                return false;
            }
        };
        IntegrationOutboxHandlerRegistry registry = new IntegrationOutboxHandlerRegistry(
                List.of(permanent),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> registry.dispatch(claim("test.event", "{\"objectId\":42}")))
                .isInstanceOf(IntegrationOutboxHandlerRegistry.DispatchException.class)
                .satisfies(exception -> {
                    var dispatch = (IntegrationOutboxHandlerRegistry.DispatchException) exception;
                    assertThat(dispatch.retryable()).isFalse();
                    assertThat(dispatch.reasonCode()).isEqualTo("HANDLER_PERMANENT_FAILURE");
                    assertThat(dispatch.getMessage()).doesNotContain("sensitive remote response");
                });
    }

    private IntegrationOutboxHandler<TestPayload> handler(
            AtomicReference<IntegrationOutboxEvent<TestPayload>> delivered,
            boolean retryable
    ) {
        return new IntegrationOutboxHandler<>() {
            @Override
            public String eventType() {
                return "test.event";
            }

            @Override
            public Class<TestPayload> payloadType() {
                return TestPayload.class;
            }

            @Override
            public void handle(IntegrationOutboxEvent<TestPayload> event) {
                delivered.set(event);
            }

            @Override
            public boolean isRetryable(Exception failure) {
                return retryable;
            }
        };
    }

    private IntegrationOutboxRepository.Claim claim(String eventType, String payload) {
        return new IntegrationOutboxRepository.Claim(
                1L,
                UUID.randomUUID().toString(),
                "order",
                "42",
                3L,
                eventType,
                payload,
                1,
                5,
                UUID.randomUUID().toString(),
                LocalDateTime.now().plusMinutes(2)
        );
    }

    private record TestPayload(long objectId) {
    }
}
