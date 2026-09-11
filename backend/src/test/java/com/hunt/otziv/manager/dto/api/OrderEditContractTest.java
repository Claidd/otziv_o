package com.hunt.otziv.manager.dto.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderEditContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void actualSerializationMatchesTheGeneratedSchemaAndClientFixture() throws Exception {
        var response = response(new BigDecimal("1250.5"), 91L);
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(response));
        JsonNode fixture = mapper.readTree(contractPath("fixtures/order-editor-current.json").toFile());
        assertEquals(fixture, actual, "Review the shared compatibility fixtures when the runtime JSON changes");
        JsonNode schemas = mapper.readTree(contractPath("generated/order-editor.openapi.json").toFile())
                .path("components").path("schemas");
        assertSchema(actual, schemas.path("OrderEditResponse"), schemas);
    }

    @Test
    void nullableWorkerMoneyAndCompanyReferencesAreRepresentedByTheContract() throws Exception {
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(response(null, null)));
        assertTrue(actual.path("sum").isNull());
        assertTrue(actual.path("companyId").isNull());
        JsonNode schemas = mapper.readTree(contractPath("generated/order-editor.openapi.json").toFile())
                .path("components").path("schemas");
        assertSchema(actual, schemas.path("OrderEditResponse"), schemas);
    }

    private OrderEditResponse response(BigDecimal sum, Long companyId) {
        var manager = new OptionResponse(17L, "Менеджер");
        return new OrderEditResponse(202L, companyId, "Компания", "В работе", sum, 5, 1,
                "2026-09-07", "2026-09-07", "", "Комментарий", "", false,
                null, manager, null, List.of(), List.of(manager), List.of(), true, false, false);
    }

    private void assertSchema(JsonNode value, JsonNode schema, JsonNode schemas) {
        assertFalse(schema.isMissingNode(), "Generated schema is missing");
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            assertSchema(value, schemas.path(reference.substring(reference.lastIndexOf('/') + 1)), schemas);
            return;
        }
        if (schema.has("anyOf")) {
            for (JsonNode candidate : schema.path("anyOf")) {
                if ("null".equals(candidate.path("type").asText()) && value.isNull()) return;
                if (!"null".equals(candidate.path("type").asText()) && !value.isNull()) {
                    assertSchema(value, candidate, schemas);
                    return;
                }
            }
            fail("Value does not match nullable schema: " + value);
        }
        switch (schema.path("type").asText()) {
            case "object" -> {
                assertTrue(value.isObject());
                Set<String> actualFields = new HashSet<>();
                value.fieldNames().forEachRemaining(actualFields::add);
                Set<String> contractFields = new HashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(contractFields::add);
                assertEquals(contractFields, actualFields, "Java serialization and generated fields differ");
                for (JsonNode field : schema.path("required")) assertTrue(value.has(field.asText()), field.asText());
                schema.path("properties").fields().forEachRemaining(entry ->
                        assertSchema(value.path(entry.getKey()), entry.getValue(), schemas));
            }
            case "array" -> {
                assertTrue(value.isArray());
                value.forEach(item -> assertSchema(item, schema.path("items"), schemas));
            }
            case "string" -> assertTrue(value.isTextual(), value.toString());
            case "boolean" -> assertTrue(value.isBoolean(), value.toString());
            case "integer" -> assertTrue(value.isIntegralNumber(), value.toString());
            case "number" -> assertTrue(value.isNumber(), value.toString());
            default -> fail("Unsupported contract schema: " + schema);
        }
    }

    private Path contractPath(String relative) {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        Path file = root.resolve("contracts").resolve(relative);
        assertTrue(Files.isRegularFile(file), "Missing committed contract: " + file);
        return file;
    }
}
