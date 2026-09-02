package com.hunt.otziv.client_messages.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ScheduledClientMessageStateRepositoryContractTest {

    @Test
    void claimsCannotOverwritePreparedDeliveryEnvelope() throws Exception {
        assertDeliveryFence("lockDueState");
        assertDeliveryFence("lockActiveState");
    }

    private void assertDeliveryFence(String methodName) throws Exception {
        Method method = ScheduledClientMessageStateRepository.class.getMethod(
                methodName,
                Long.class,
                LocalDateTime.class,
                LocalDateTime.class,
                String.class,
                String.class
        );
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).containsIgnoringCase("state.delivery_status IS NULL");
    }
}
