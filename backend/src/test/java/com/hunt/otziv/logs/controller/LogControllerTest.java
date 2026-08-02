package com.hunt.otziv.logs.controller;

import com.hunt.otziv.logs.conf.LogPathResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogControllerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void viewKeepsOnlyLastMatchingLinesAndEscapesSearchAttribute() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, """
                INFO ignored
                ERROR row-old-match
                ERROR row-new-match
                """, StandardCharsets.UTF_8);
        LogController controller = controllerFor(log);

        String body = controller.viewLogs("error", 1, "match", false, null, "unexpected").getBody();

        assertNotNull(body);
        assertFalse(body.contains("row-old-match"));
        assertTrue(body.contains("row-new-match"));
        assertTrue(body.contains("const savedTheme = localStorage.getItem(\"theme\") || \"dark\""));

        String escaped = controller.viewLogs(null, 10, "'><script>alert(1)</script>", false, null, "light")
                .getBody();
        assertNotNull(escaped);
        assertFalse(escaped.contains("value=''><script>"));
        assertTrue(escaped.contains("&#39;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test
    void tailIsBoundedResumableUtf8AndResetsInvalidOffset() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, "первая\r\nвторая\nтретья", StandardCharsets.UTF_8);
        LogController controller = controllerFor(log);

        Map<String, Object> first = body(controller.tailLog(0, 2));
        assertEquals(List.of("первая", "вторая"), first.get("lines"));
        assertEquals(true, first.get("hasMore"));
        assertEquals(false, first.get("reset"));

        long nextOffset = ((Number) first.get("newOffset")).longValue();
        Map<String, Object> second = body(controller.tailLog(nextOffset, 2));
        assertEquals(List.of("третья"), second.get("lines"));
        assertEquals(false, second.get("hasMore"));

        Map<String, Object> reset = body(controller.tailLog(Long.MAX_VALUE, 1));
        assertEquals(List.of("первая"), reset.get("lines"));
        assertEquals(true, reset.get("reset"));
    }

    @Test
    void tailTruncatesAnOversizedLineWithoutLosingFollowingLines() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(
                log,
                "a" + "я".repeat(LogController.MAX_TAIL_LINE_BYTES) + "\nследующая",
                StandardCharsets.UTF_8
        );
        LogController controller = controllerFor(log);

        Map<String, Object> response = body(controller.tailLog(0, 2));
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) response.get("lines");

        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().endsWith("[truncated]"));
        assertFalse(lines.getFirst().contains("�"));
        assertTrue(lines.getFirst().getBytes(StandardCharsets.UTF_8).length <= LogController.MAX_TAIL_LINE_BYTES);
        assertEquals("следующая", lines.get(1));
    }

    @Test
    void historicalViewAndTailStayOnTheExactSelectedSegment() throws Exception {
        Path current = tempDirectory.resolve("app.log");
        Path archive = tempDirectory.resolve("app.2026-07-31.2.log");
        Files.writeString(current, "CURRENT must not leak\n", StandardCharsets.UTF_8);
        Files.writeString(archive, "ARCHIVE existing\n", StandardCharsets.UTF_8);
        LogController controller = controllerFor(current);

        String body = controller.viewLogs(null, 10, null, true, "2026-07-31.2", "dark").getBody();

        assertNotNull(body);
        assertTrue(body.contains("ARCHIVE existing"));
        assertFalse(body.contains("CURRENT must not leak"));
        assertTrue(body.contains("const logSelector = \"2026-07-31.2\""));
        assertTrue(body.contains("2026-07-31 (часть 2)"));

        long archiveOffset = Files.size(archive);
        Files.writeString(current, "CURRENT appended\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.writeString(archive, "ARCHIVE appended\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Map<String, Object> tail = body(controller.tailLog(archiveOffset, 10, "2026-07-31.2"));
        assertEquals(List.of("ARCHIVE appended"), tail.get("lines"));
        assertEquals("2026-07-31.2", tail.get("date"));
    }

    @Test
    void snapshotOffsetDoesNotSkipBytesAppendedAfterTheSnapshot() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        LogController controller = controllerFor(log);

        LogController.LogSnapshot snapshot = LogController.readLastMatchingSnapshot(log, 10, ignored -> true);
        Files.writeString(log, "после snapshot\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(List.of("before"), snapshot.lines());
        Map<String, Object> tail = body(controller.tailLog(snapshot.offset(), 10));
        assertEquals(List.of("после snapshot"), tail.get("lines"));
    }

    @Test
    void unsafeLimitsUseDefaultsAndLargeLimitsAreClamped() {
        assertEquals(
                LogController.DEFAULT_VIEW_LIMIT,
                LogController.normalizeLimit(0, LogController.DEFAULT_VIEW_LIMIT, LogController.MAX_VIEW_LIMIT)
        );
        assertEquals(
                LogController.MAX_TAIL_LIMIT,
                LogController.normalizeLimit(Integer.MAX_VALUE, LogController.DEFAULT_TAIL_LIMIT, LogController.MAX_TAIL_LIMIT)
        );
    }

    private LogController controllerFor(Path log) {
        return new LogController(new LocalLogPathResolver(log));
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static final class LocalLogPathResolver extends LogPathResolver {
        private final Path log;

        private LocalLogPathResolver(Path log) {
            this.log = log;
        }

        @Override
        public Path getLogPath() {
            return log;
        }
    }
}
