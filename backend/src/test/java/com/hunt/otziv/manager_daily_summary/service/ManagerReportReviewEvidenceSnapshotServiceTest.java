package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagerReportReviewEvidenceSnapshotServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagerReportReviewEvidenceSnapshotService service = new ManagerReportReviewEvidenceSnapshotService(mapper);
    private static final String MARKER = "СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:";

    @Test
    void hugeHistoricalReportCannotDisplaceTheWholeFreshQuestionEvidence() {
        String history = "sourceTaskId=99; unrelated=" + "x".repeat(579_000) + "\nsourceTaskId=42; исходная причина\n";
        String fresh = MARKER + "\n" + fact(42, "invoice", "status", "PARTIALLY_PAID") + "\n";
        String context = service.compose(history, fresh, List.of(42L));
        assertThat(context).hasSizeLessThan(2000).contains("исходная причина").doesNotContain("unrelated").endsWith(fresh);
        assertThat(service.restore(service.encode(context))).contains(context);
    }

    @Test
    void historicalArchiveAndRestorationRemainSelectedAndAdvisory() {
        String archive = fact(7, "invoice_history", "closed_at", "2026-07-30");
        String restore = fact(9, "order_history", "restored_at", "2026-09-08");
        String history = fact(99, "invoice", "status", "UNRELATED") + "\n" +
                (fact(7, "order", "details", "x".repeat(1500)) + "\n").repeat(30) + archive + "\n" + restore;
        String fresh = MARKER + "\n" + fact(7, "invoice", "status", "ARCHIVED");
        String context = service.compose(history, fresh, List.of(7L, 9L));
        assertThat(context).contains(archive, restore, "пропущено целых строк").doesNotContain("UNRELATED").endsWith(fresh);
        assertThat(context.split(MARKER, -1)).hasSize(2);
    }

    @Test
    void historyBudgetOmitsWholeOversizedJsonRatherThanBreakingIt() {
        String huge = fact(42, "recorded_chat_message", "recorded_text", "я".repeat(13_000));
        String small = fact(42, "invoice", "status", "COLLECTING");
        String context = service.compose(huge + "\n" + small, MARKER + "\n" + small, List.of(42L));
        assertThat(context).contains(small, "пропущено целых строк 1").doesNotContain("recorded_chat_message");
    }

    @Test
    void aQuestionWithoutSourceIdsKeepsBoundedHistoryAndTheCompleteFreshBlock() {
        String fresh = "Рабочие цели\n" + MARKER + "\nworkerId=7; завершено=2; всего=3\n";
        String context = service.compose("История вопроса\n" + MARKER + "\nпрежнее состояние", fresh, List.of());
        assertThat(context).contains("История вопроса", "Факты прежнего снимка:").endsWith(fresh);
        assertThat(context.split(MARKER, -1)).hasSize(2);
    }

    @Test
    void embeddedForeignSourceMarkerCannotMakeHistoricalMessageRelevant() {
        String forged = fact(99, "recorded_chat_message", "recorded_text", "sourceTaskId=42; unrelated message");
        String own = fact(42, "invoice", "status", "COLLECTING");
        assertThat(service.compose(forged + "\n" + own, MARKER + "\n" + own, List.of(42L)))
                .contains(own).doesNotContain("unrelated message");
    }

    @Test
    void completeUnicodeSnapshotOverOldLimitRestoresIdenticallyOnEveryRetry() {
        String fresh = MARKER + "\r\n" + (fact(42, "recorded_chat_message", "recorded_text", "📄\nРусский текст ".repeat(50)) + "\r\n").repeat(350);
        assertThat(fresh.length()).isGreaterThan(200_000);
        String context = service.compose("История", fresh, List.of(42L));
        String encoded = service.encode(context);
        assertThat(service.restore(encoded)).contains(context);
        assertThat(service.restore(encoded)).contains(context);
        assertThat(service.restore(encoded.substring(0, 200_000))).isEmpty();
    }

    @Test
    void truncatedLegacyAndApparentlyCompleteLegacyCannotBeMistakenForOriginalEvidence() {
        String legacy = "СНИМОК НА МОМЕНТ ПОДГОТОВКИ ВОПРОСОВ:\n" + "x".repeat(199_992);
        assertThat(service.restore(legacy)).isEmpty();
        assertThat(service.restore(MARKER + "\n" + fact(42, "invoice", "status", "COLLECTING"))).isEmpty();
        assertThat(service.restore(null)).isEmpty();
    }

    @Test
    void validJsonWithChangedContextLengthHashSchemaOrTrailingPayloadFailsClosed() throws Exception {
        String encoded = service.encode(MARKER + "\n" + fact(42, "invoice", "status", "COLLECTING"));
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
        changed.put("context", changed.path("context").asText().replace("COLLECTING", "ARCHIVED__"));
        assertThat(service.restore(mapper.writeValueAsString(changed))).isEmpty();
        changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
        changed.put("utf16Length", 1);
        assertThat(service.restore(mapper.writeValueAsString(changed))).isEmpty();
        changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
        changed.put("schema", "legacy");
        assertThat(service.restore(mapper.writeValueAsString(changed))).isEmpty();
        assertThat(service.restore(encoded + " {}" )).isEmpty();
    }

    private String fact(long sourceTaskId, String source, String field, String value) {
        var json = mapper.createObjectNode();
        json.put("id", sourceTaskId + ":" + source + ":" + field);
        json.put("sourceTaskId", sourceTaskId);
        json.put("source", source);
        json.put("field", field);
        json.put("value", value);
        return json.toString();
    }
}
