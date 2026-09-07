package com.hunt.otziv.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.JavaType;

/** Synthetic DTO examples serialized by the MVC converter. These are not released-client evidence. */
class ClientApiJacksonFixtureTest {
    @Test
    void everyGeneratedDtoRoundTripsThroughActualJacksonAndMatchesItsWireShape() throws Exception {
        Path root = Path.of(System.getProperty("client.contract.root", "..")).toAbsolutePath().normalize();
        var mapper = new JacksonJsonHttpMessageConverter().getMapper();
        var model = new ClientApiContractModel(mapper, root);
        model.export();
        Map<String, Object> examples = new TreeMap<>();
        for (var entry : model.outputTypes.entrySet()) {
            Object seed = sample(model, schema(model, entry.getKey()), false, new HashSet<>());
            Object dto = instance(model, entry.getValue(), seed);
            Object json = mapper.readValue(mapper.writeValueAsString(dto), Object.class);
            validate(model, json, schema(model, entry.getKey()), entry.getKey());
            examples.put(entry.getKey(), json);
        }
        Map<String, Object> requests = new TreeMap<>();
        for (var entry : model.inputTypes.entrySet()) {
            Object seed = sample(model, schema(model, entry.getKey()), false, new HashSet<>());
            Object dto = mapper.convertValue(seed, entry.getValue());
            assertThat(dto).as(entry.getKey()).isNotNull();
            requests.put(entry.getKey(), seed);
        }
        Map<String, Object> fixture = ClientApiContractModel.object("provenance",
                "Synthetic non-production DTO values; deserialized and serialized by Spring MVC's Jackson 3 converter. Not released APK samples.",
                "responses", examples, "requests", requests);
        Path target = root.resolve("contracts/fixtures/client-api-current.json");
        if (Boolean.getBoolean("client.contract.export")) Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixture) + "\n");
        assertThat(Files.exists(target)).isTrue();
        assertThat(mapper.readTree(target)).isEqualTo(mapper.valueToTree(fixture));
    }

    private static Object instance(ClientApiContractModel model, JavaType type, Object seed) {
        Class<?> raw = type.getRawClass();
        if (org.springframework.data.domain.Page.class.isAssignableFrom(raw)) {
            JavaType item = type.containedTypeOrUnknown(0);
            Object value = sample(model, model.schema(item, false), false, new HashSet<>());
            return new org.springframework.data.domain.PageImpl<>(List.of(instance(model, item, value)), org.springframework.data.domain.PageRequest.of(1, 2), 5);
        }
        if (org.springframework.data.domain.Pageable.class.isAssignableFrom(raw)) return org.springframework.data.domain.PageRequest.of(1, 2);
        if (org.springframework.data.domain.Sort.class.isAssignableFrom(raw)) return org.springframework.data.domain.Sort.by("id");
        return model.mapper.convertValue(seed, type);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(ClientApiContractModel model, String name) { return (Map<String, Object>) model.schemas.get(name); }

    @SuppressWarnings("unchecked")
    static Object sample(ClientApiContractModel model, Map<String, Object> schema, boolean nullable, Set<String> stack) {
        if (schema.containsKey("$ref")) {
            String name = ((String) schema.get("$ref")).replace("#/components/schemas/", "");
            if (!stack.add(name)) return null;
            Object result = sample(model, schema(model, name), nullable, stack); stack.remove(name); return result;
        }
        if (schema.containsKey("anyOf")) return sample(model, ((List<Map<String, Object>>) schema.get("anyOf")).get(nullable ? 1 : 0), nullable, stack);
        if (schema.containsKey("enum")) return ((List<?>) schema.get("enum")).getFirst();
        return switch ((String) schema.getOrDefault("type", "unknown")) {
            case "null" -> null;
            case "string" -> switch ((String) schema.getOrDefault("format", (String) schema.getOrDefault("x-date-time-kind", ""))) {
                case "date" -> "2026-09-07";
                case "date-time" -> "2026-09-07T00:00:00Z";
                case "local" -> "2026-09-07T08:00:00";
                case "local-time" -> "08:00:00";
                case "uuid" -> "00000000-0000-0000-0000-000000000001";
                case "uri" -> "https://contract.invalid/example";
                default -> "contract-example";
            };
            case "integer" -> 2;
            case "number" -> 12.5;
            case "boolean" -> false;
            case "array" -> List.of(sample(model, (Map<String, Object>) schema.get("items"), nullable, stack));
            case "object" -> {
                Map<String, Object> result = new TreeMap<>();
                for (var field : ((Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of())).entrySet())
                    result.put(field.getKey(), sample(model, field.getValue(), nullable, stack));
                yield result;
            }
            default -> Map.of("synthetic", true);
        };
    }

    @SuppressWarnings("unchecked")
    static void validate(ClientApiContractModel model, Object value, Map<String, Object> schema, String name) {
        if (schema.containsKey("$ref")) { validate(model, value, schema(model, ((String) schema.get("$ref")).replace("#/components/schemas/", "")), name); return; }
        if (schema.containsKey("anyOf")) {
            for (var candidate : (List<Map<String, Object>>) schema.get("anyOf")) {
                try { validate(model, value, candidate, name); return; } catch (AssertionError ignored) { }
            }
            throw new AssertionError("No allowed JSON type for " + name);
        }
        switch ((String) schema.getOrDefault("type", "unknown")) {
            case "null" -> assertThat(value).as(name).isNull();
            case "string" -> assertThat(value).as(name).isInstanceOf(String.class);
            case "integer", "number" -> assertThat(value).as(name).isInstanceOf(Number.class);
            case "boolean" -> assertThat(value).as(name).isInstanceOf(Boolean.class);
            case "array" -> {
                assertThat(value).as(name).isInstanceOf(List.class);
                for (Object item : (List<?>) value) validate(model, item, (Map<String, Object>) schema.get("items"), name + "[]");
            }
            case "object" -> {
                assertThat(value).as(name).isInstanceOf(Map.class);
                Map<String, Object> fields = (Map<String, Object>) value;
                assertThat(fields.keySet()).as(name).containsAll((List<String>) schema.getOrDefault("required", List.of()));
                for (var field : ((Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of())).entrySet())
                    if (fields.containsKey(field.getKey())) validate(model, fields.get(field.getKey()), field.getValue(), name + "." + field.getKey());
            }
            default -> { }
        }
    }
}
