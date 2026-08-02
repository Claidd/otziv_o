package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationOutboxPayloadPolicyTest {

    private final IntegrationOutboxProperties properties =
            new IntegrationOutboxProperties();
    private final IntegrationOutboxPayloadPolicy policy =
            new IntegrationOutboxPayloadPolicy(new ObjectMapper(), properties);

    @Test
    void serializesBoundedObjectPayload() {
        assertThat(policy.serialize(Map.of("objectId", 42, "operation", "DELETE")))
                .contains("\"objectId\":42")
                .contains("\"operation\":\"DELETE\"");
    }

    @Test
    void rejectsCredentialShapedFieldsAndValues() {
        assertThatThrownBy(() -> policy.serialize(Map.of("clientSecret", "redacted")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-like field");
        assertThatThrownBy(() -> policy.serialize(Map.of("header", "Bearer abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-like value");
        assertThatThrownBy(() -> policy.serialize(Map.of(
                "value",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop" // gitleaks:allow -- deliberately invalid synthetic JWT
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-like value");
    }

    @Test
    void rejectsScalarAndOversizedPayloads() {
        assertThatThrownBy(() -> policy.serialize("not-an-object"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");

        properties.setMaxPayloadBytes(1024);
        assertThatThrownBy(() -> policy.serialize(Map.of("description", "x".repeat(2_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void validatesNonSecretDeduplicationKeys() {
        policy.validateDeduplicationKey("payment:invoice:123:v1");

        assertThatThrownBy(() -> policy.validateDeduplicationKey("Bearer credential"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain credentials");
    }

    @Test
    void comparesDeduplicatedPayloadsSemanticallyWithoutDependingOnFormatting() {
        assertThat(policy.semanticallyEquals(
                "{\"operation\":\"DELETE\",\"objectId\":42}",
                "{ \"objectId\" : 42, \"operation\" : \"DELETE\" }"
        )).isTrue();
        assertThat(policy.semanticallyEquals(
                "{\"objectId\":42}",
                "{\"objectId\":43}"
        )).isFalse();
    }
}
