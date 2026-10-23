package com.hunt.otziv.manager_daily_summary.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure snapshot codec. Historical material is advisory; fresh evidence is never prefix-truncated. */
public final class ManagerReportReviewEvidenceSnapshotService {
    static final int HISTORY_LIMIT = 12_000;
    private static final String SCHEMA = "manager-answer-evidence-v3";
    private static final String FACTS_MARKER = "СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:";
    private static final Pattern SOURCE_ID = Pattern.compile("sourceTaskId=(\\d+)(?!\\d)");
    private final ObjectMapper mapper;

    public ManagerReportReviewEvidenceSnapshotService(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public String compose(String historical, String fresh, Collection<Long> sourceTaskIds) {
        Set<Long> selected = new LinkedHashSet<>();
        if (sourceTaskIds != null) sourceTaskIds.stream().filter(Objects::nonNull).filter(id -> id > 0).forEach(selected::add);
        List<HistoricalLine> lines = new ArrayList<>();
        boolean hasScopedLines = false;
        int ordinal = 0;
        for (String line : Objects.requireNonNullElse(historical, "").split("\\R", -1)) {
            if (line.isBlank()) continue;
            JsonNode json = historicalFact(line);
            Set<Long> owners = owners(line, json);
            hasScopedLines |= !owners.isEmpty();
            lines.add(new HistoricalLine(ordinal++, line, owners, priority(json)));
        }
        final boolean scoped = hasScopedLines && !selected.isEmpty();
        List<HistoricalLine> relevant = lines.stream()
                .filter(line -> !scoped || line.owners().stream().anyMatch(selected::contains))
                .sorted(Comparator.comparingInt(HistoricalLine::priority).thenComparingInt(HistoricalLine::ordinal))
                .toList();
        List<HistoricalLine> kept = new ArrayList<>();
        int characters = 0;
        for (HistoricalLine line : relevant) {
            if (characters + line.text().length() + 1 > HISTORY_LIMIT) continue;
            characters += line.text().length() + 1;
            kept.add(line);
        }
        kept.sort(Comparator.comparingInt(HistoricalLine::ordinal));
        StringBuilder result = new StringBuilder("СНИМОК НА МОМЕНТ ПОДГОТОВКИ ВОПРОСОВ (исторический контекст; не текущее подтверждение):\n");
        for (HistoricalLine line : kept) {
            result.append(line.text().replace(FACTS_MARKER, "Факты прежнего снимка:")).append('\n');
        }
        if (kept.isEmpty()) result.append("Релевантные исторические сведения не представлены.\n");
        if (kept.size() < relevant.size()) result.append("Исторический контекст ограничен: пропущено целых строк ")
                .append(relevant.size() - kept.size()).append(". Пропуск не подтверждает отсутствие события.\n");
        // No trim, line splitting, HTML cleanup or byte cap may change this fresh context.
        return result.append("\nСВЕЖАЯ ПРОВЕРКА ИСТОЧНИКОВ:\n")
                .append(Objects.requireNonNullElse(fresh, "")).toString();
    }

    /** Keep the existing answer identity header outside this encoded envelope. Store the result as LONGTEXT. */
    public String encode(String context) {
        Objects.requireNonNull(context, "context");
        var envelope = mapper.createObjectNode();
        envelope.put("schema", SCHEMA);
        envelope.put("utf16Length", context.length());
        envelope.put("sha256", hash(context));
        envelope.put("context", context);
        try { return mapper.writeValueAsString(envelope); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode answer evidence", e); }
    }

    /** Legacy prefix-limited snapshots have no integrity proof and must never masquerade as original evidence. */
    public Optional<String> restore(String encoded) {
        if (encoded == null || encoded.isBlank()) return Optional.empty();
        try {
            JsonNode envelope = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(encoded);
            if (!envelope.isObject() || !SCHEMA.equals(envelope.path("schema").asText())
                    || !envelope.path("context").isTextual() || !envelope.path("utf16Length").isIntegralNumber()
                    || !envelope.path("sha256").isTextual()) return Optional.empty();
            String context = envelope.path("context").asText();
            if (context.length() != envelope.path("utf16Length").asLong(-1)
                    || !hash(context).equals(envelope.path("sha256").asText())) return Optional.empty();
            return Optional.of(context);
        } catch (Exception invalid) { return Optional.empty(); }
    }

    private JsonNode historicalFact(String line) {
        if (!line.stripLeading().startsWith("{")) return null;
        try { return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line); }
        catch (Exception ignored) { return null; }
    }

    private Set<Long> owners(String line, JsonNode json) {
        Set<Long> result = new LinkedHashSet<>();
        if (json != null) {
            long id = json.path("sourceTaskId").asLong(-1);
            if (id > 0) result.add(id);
            return result; // Embedded message text cannot assign another owner to a typed fact.
        }
        var matcher = SOURCE_ID.matcher(line);
        while (matcher.find()) {
            try { long id = Long.parseLong(matcher.group(1)); if (id > 0) result.add(id); }
            catch (NumberFormatException ignored) { /* Malformed historical marker is not evidence. */ }
        }
        return result;
    }

    private int priority(JsonNode json) {
        if (json == null) return 1;
        String source = json.path("source").asText("");
        String field = json.path("field").asText("");
        if (source.contains("history") || source.contains("chronology") || source.contains("audit")
                || Set.of("closed_at", "close_reason", "previous_status", "paid_at", "archived_at", "restored_at").contains(field)) return 0;
        return Set.of("status", "current_cycle", "control_date", "amount_kopecks", "paid_amount_kopecks").contains(field) ? 1 : 2;
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private record HistoricalLine(int ordinal, String text, Set<Long> owners, int priority) { }
}
