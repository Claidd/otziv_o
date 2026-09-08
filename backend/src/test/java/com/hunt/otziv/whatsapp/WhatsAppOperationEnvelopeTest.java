package com.hunt.otziv.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WhatsAppOperationEnvelopeTest {
    @TestFactory
    Stream<DynamicTest> matchesGatewayWireIdentity() throws Exception {
        JsonNode fixtures = new ObjectMapper().readTree(Path.of("..", "contracts", "fixtures",
                "whatsapp-operation-envelope-v1.json").toFile());
        return StreamSupport.stream(fixtures.spliterator(), false).map(fixture -> DynamicTest.dynamicTest(
                fixture.path("name").asText(), () -> {
                    String clientId = fixture.path("clientId").asText(null);
                    String groupId = fixture.path("groupId").asText(null);
                    String message = fixture.path("message").asText(null);
                    if (fixture.path("invalid").asBoolean()) {
                        assertThrows(IllegalArgumentException.class,
                                () -> WhatsAppOperationEnvelope.groupHash(clientId, groupId, message));
                    } else {
                        assertEquals(fixture.path("hash").asText(),
                                WhatsAppOperationEnvelope.groupHash(clientId, groupId, message));
                    }
                }));
    }
}
