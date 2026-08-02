package com.hunt.otziv.logs.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogWebSocketHandlerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void streamsToConcurrentSessionsAndCancelsEachSessionTask() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, "baseline\n", StandardCharsets.UTF_8);
        LogWebSocketHandler handler = new LogWebSocketHandler(new LocalLogPathResolver(log));
        AtomicBoolean firstOpen = new AtomicBoolean(true);
        AtomicBoolean secondOpen = new AtomicBoolean(true);
        WebSocketSession first = session("first", firstOpen);
        WebSocketSession second = session("second", secondOpen);

        try {
            handler.afterConnectionEstablished(first);
            handler.afterConnectionEstablished(second);
            verify(first, timeout(2_000)).isOpen();
            verify(second, timeout(2_000)).isOpen();

            Files.writeString(log, "новая строка\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            verify(first, timeout(3_000)).sendMessage(argThat(message -> hasPayload(message, "новая строка")));
            verify(second, timeout(3_000)).sendMessage(argThat(message -> hasPayload(message, "новая строка")));
        } finally {
            firstOpen.set(false);
            secondOpen.set(false);
            handler.afterConnectionClosed(first, CloseStatus.NORMAL);
            handler.afterConnectionClosed(second, CloseStatus.NORMAL);
            handler.shutdownExecutor();
        }
    }

    @Test
    void reopensTheActivePathAfterRolloverAndStreamsUtf8FromTheNewFile() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Path rotated = tempDirectory.resolve("app.2026-08-02.0.log");
        Files.writeString(log, "baseline\n", StandardCharsets.UTF_8);
        LogWebSocketHandler handler = new LogWebSocketHandler(new LocalLogPathResolver(log));
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = session("rollover", open);

        try {
            handler.afterConnectionEstablished(session);
            verify(session, timeout(2_000)).isOpen();

            Files.writeString(log, "до ротации\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            verify(session, timeout(3_000)).sendMessage(argThat(message -> hasPayload(message, "до ротации")));

            Files.move(log, rotated);
            Files.writeString(log, "после ротации\n", StandardCharsets.UTF_8);

            verify(session, timeout(3_000)).sendMessage(argThat(message -> hasPayload(message, "после ротации")));
        } finally {
            open.set(false);
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            handler.shutdownExecutor();
        }
    }

    @Test
    void followsAnOversizedGrowingLineWithoutLeakingOrSendingItTwice() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, "baseline\n", StandardCharsets.UTF_8);
        LogWebSocketHandler handler = new LogWebSocketHandler(new LocalLogPathResolver(log));
        AtomicBoolean open = new AtomicBoolean(true);
        WebSocketSession session = session("oversized-line", open);

        try {
            handler.afterConnectionEstablished(session);
            verify(session, timeout(2_000)).isOpen();

            String growingLine = "ж".repeat(LogWebSocketHandler.MAX_WEBSOCKET_SCAN_BYTES_PER_POLL / 2 + 1_024);
            Files.writeString(log, growingLine, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            verify(session, after(750).never()).sendMessage(any(TextMessage.class));

            Files.writeString(log, "\nпосле\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            verify(session, timeout(4_000)).sendMessage(argThat(LogWebSocketHandlerTest::isBoundedTruncatedPayload));
            verify(session, timeout(4_000)).sendMessage(argThat(message -> hasPayload(message, "после")));
            verify(session, after(750).times(2)).sendMessage(any(TextMessage.class));
        } finally {
            open.set(false);
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            handler.shutdownExecutor();
        }
    }

    private static boolean hasPayload(Object message, String expected) {
        return message instanceof TextMessage textMessage && expected.equals(textMessage.getPayload());
    }

    private static boolean isBoundedTruncatedPayload(Object message) {
        if (!(message instanceof TextMessage textMessage)) {
            return false;
        }
        String payload = textMessage.getPayload();
        return payload.endsWith(" … [truncated]")
                && !payload.contains("�")
                && payload.getBytes(StandardCharsets.UTF_8).length
                <= LogWebSocketHandler.MAX_WEBSOCKET_LINE_BYTES;
    }

    private static WebSocketSession session(String id, AtomicBoolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        return session;
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
