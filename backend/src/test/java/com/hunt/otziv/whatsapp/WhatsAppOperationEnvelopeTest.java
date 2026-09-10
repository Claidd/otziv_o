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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"79990000000", "+7 (999) 000-00-00", "89990000000"})
    void phoneNormalizationMatchesActualGatewayIdentity(String phone) {
        assertEquals("5c6f69e4336bb6cecac822993f746533c48d307248f86d2d6918e027e790425a",
                WhatsAppOperationEnvelope.phoneHash("client",phone,"1200 руб."));
    }

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
