package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rejects credential-shaped and unbounded payloads before they can reach the
 * durable outbox. This is intentionally conservative: integrations should
 * persist stable object ids and resolve credentials from their normal secret
 * store inside the handler.
 */
@Component
final class IntegrationOutboxPayloadPolicy {

    private static final int MAX_DEPTH = 20;
    private static final int MAX_NODES = 5_000;
    private static final int MAX_ARRAY_ELEMENTS = 1_000;
    private static final int MAX_STRING_LENGTH = 8_192;

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "passwd",
            "secret",
            "clientsecret",
            "webhooksecret",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "authorization",
            "credential",
            "credentials",
            "apikey",
            "accesskey",
            "secretkey",
            "privatekey",
            "signingkey",
            "cookie",
            "setcookie",
            "sessionid"
    );

    private static final Pattern JWT = Pattern.compile(
            "(?i)(?:^|\\s)[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}(?:$|\\s)"
    );
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(akia|asia)[a-z0-9]{16}(?:$|[^a-z0-9])"
    );

    private final ObjectMapper objectMapper;
    private final IntegrationOutboxProperties properties;

    IntegrationOutboxPayloadPolicy(
            ObjectMapper objectMapper,
            IntegrationOutboxProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    String serialize(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Outbox payload is required");
        }

        final JsonNode root;
        try {
            root = objectMapper.valueToTree(payload);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Outbox payload must be a JSON object");
        }

        validateTree(root);

        final String json;
        try {
            json = objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
            throw new IllegalArgumentException("Outbox payload exceeds the configured byte limit");
        }
        return json;
    }

    void validateDeduplicationKey(String deduplicationKey) {
        if (deduplicationKey == null || deduplicationKey.isBlank()) {
            throw new IllegalArgumentException("Outbox deduplication key is required");
        }
        if (deduplicationKey.length() > 512 || containsControlCharacter(deduplicationKey)) {
            throw new IllegalArgumentException("Outbox deduplication key is invalid");
        }
        if (looksLikeSecretValue(deduplicationKey)) {
            throw new IllegalArgumentException("Outbox deduplication key must not contain credentials");
        }
    }

    boolean semanticallyEquals(String storedJson, String proposedJson) {
        if (storedJson == null || proposedJson == null) {
            return false;
        }
        try {
            JsonNode stored = objectMapper.readTree(storedJson);
            JsonNode proposed = objectMapper.readTree(proposedJson);
            return stored != null && stored.equals(proposed);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Outbox payload equivalence could not be verified"
            );
        }
    }

    private void validateTree(JsonNode root) {
        Deque<NodeAtDepth> queue = new ArrayDeque<>();
        queue.add(new NodeAtDepth(root, 0));
        int visited = 0;

        while (!queue.isEmpty()) {
            NodeAtDepth current = queue.removeFirst();
            JsonNode node = current.node();
            if (++visited > MAX_NODES) {
                throw new IllegalArgumentException("Outbox payload contains too many values");
            }
            if (current.depth() > MAX_DEPTH) {
                throw new IllegalArgumentException("Outbox payload is nested too deeply");
            }

            if (node.isObject()) {
                node.properties().forEach(entry -> {
                    if (isSensitiveField(entry.getKey())) {
                        throw new IllegalArgumentException(
                                "Outbox payload contains a prohibited credential-like field"
                        );
                    }
                    queue.addLast(new NodeAtDepth(entry.getValue(), current.depth() + 1));
                });
            } else if (node.isArray()) {
                if (node.size() > MAX_ARRAY_ELEMENTS) {
                    throw new IllegalArgumentException("Outbox payload array is too large");
                }
                node.forEach(child -> queue.addLast(
                        new NodeAtDepth(child, current.depth() + 1)
                ));
            } else if (node.isTextual()) {
                String value = node.textValue();
                if (value.length() > MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("Outbox payload string is too large");
                }
                if (looksLikeSecretValue(value)) {
                    throw new IllegalArgumentException(
                            "Outbox payload contains a prohibited credential-like value"
                    );
                }
            }
        }
    }

    private boolean isSensitiveField(String fieldName) {
        String normalized = fieldName == null
                ? ""
                : fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELD_NAMES.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("password")
                || normalized.endsWith("secret")
                || normalized.endsWith("credential")
                || normalized.endsWith("credentials")
                || normalized.endsWith("accesstoken")
                || normalized.endsWith("refreshtoken")
                || normalized.endsWith("authorization")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("signingkey")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("cookie");
    }

    private boolean looksLikeSecretValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("bearer ")
                || lower.contains("-----begin private key-----")
                || lower.contains("-----begin rsa private key-----")
                || JWT.matcher(trimmed).find()
                || AWS_ACCESS_KEY.matcher(trimmed).find();
    }

    private boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
    }

    private record NodeAtDepth(JsonNode node, int depth) {
    }
}
