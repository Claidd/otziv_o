package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/** Later discovery of a dated message may supplement a saved answer, never today's financial state. */
final class RecordedCommunicationEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");
    private RecordedCommunicationEvidence() { }

    static String fingerprint(String fresh, ZonedDateTime submittedAt) {
        try {
            var eligible = eligible(fresh, submittedAt);
            if (eligible.isEmpty()) return "";
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("recorded-communication-v1\n".getBytes(StandardCharsets.UTF_8));
            for (var group : eligible.values()) for (var fact : group.values()) {
                // Retrieval time and AI diagnosis must not cause an endless retry loop.
                digest.update((fact.path("sourceTaskId") + ":" + fact.path("id").asText() + "=" + fact.path("value") + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String supplement(String fresh, String original, ZonedDateTime submittedAt) {
        var previous = facts(original);
        var added = new StringBuilder();
        for (var group : eligible(fresh, submittedAt).values()) {
            // Never silently replace an edited or conflicting version of historical evidence.
            if (group.entrySet().stream().anyMatch(e -> previous.containsKey(e.getKey())
                    && !previous.get(e.getKey()).path("value").equals(e.getValue().path("value")))) continue;
            for (var entry : group.entrySet()) {
                if (previous.containsKey(entry.getKey())) continue;
                ObjectNode fact = entry.getValue().deepCopy();
                fact.put("source", "historical_" + fact.path("source").asText());
                added.append(fact).append('\n');
            }
        }
        return added.isEmpty() ? "" : "ДОПОЛНЕНИЕ ПРИ ПОВТОРНОЙ СВЕРКЕ: сообщения, написанные до ответа менеджера, "
                + "найдены при последующей проверке. Исходный снимок не изменён. Подтверждается запись сообщения "
                + "в чате и авторство, но не доставка на устройство, прочтение, оплата или завершение заказа.\n"
                + "СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\n" + added;
    }

    private static SortedMap<String, SortedMap<String, JsonNode>> eligible(String context, ZonedDateTime submittedAt) {
        var groups = new TreeMap<String, SortedMap<String, JsonNode>>();
        if (submittedAt == null) return groups;
        for (var entry : facts(context).entrySet()) {
            if (!entry.getKey().matches("message:[0-9]+:[a-z_]+")) continue;
            String source = entry.getValue().path("source").asText();
            if (!Set.of("client_chat_message", "recorded_chat_message").contains(source)) continue;
            String prefix = entry.getKey().substring(0, entry.getKey().lastIndexOf(':') + 1);
            groups.computeIfAbsent(prefix, ignored -> new TreeMap<>()).put(entry.getKey(), entry.getValue());
        }
        groups.entrySet().removeIf(e -> {
            var group = e.getValue(); String p = e.getKey();
            if (!Set.of("1", "true").contains(value(group, p + "author_is_audited_manager"))
                    || !"STAFF".equals(value(group, p + "sender_role"))
                    || !"true".equals(value(group, p + "communication_recorded"))
                    || !"0".equals(value(group, p + "edit_count"))
                    || value(group, p + "recorded_text").isBlank()) return true;
            try { return LocalDateTime.parse(value(group, p + "message_at").replace(' ', 'T'))
                    .isAfter(submittedAt.withZoneSameInstant(BUSINESS_ZONE).toLocalDateTime()); }
            catch (RuntimeException invalid) { return true; }
        });
        return groups;
    }

    private static String value(Map<String, JsonNode> facts, String id) {
        JsonNode fact = facts.get(id);
        return fact == null ? "" : fact.path("value").asText();
    }

    private static Map<String, JsonNode> facts(String context) {
        Map<String, JsonNode> result = new TreeMap<>();
        if (context == null) return result;
        context.lines().map(String::strip).filter(line -> line.startsWith("{")).forEach(line -> {
            try {
                var fact = JSON.readTree(line);
                if (fact.has("id") && fact.has("source") && fact.has("field") && fact.has("value"))
                    result.putIfAbsent(fact.path("id").asText(), fact);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* prose is not a fact */ }
        });
        return result;
    }
}
