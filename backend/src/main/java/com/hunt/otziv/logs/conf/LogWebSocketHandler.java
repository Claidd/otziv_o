package com.hunt.otziv.logs.conf;

import com.hunt.otziv.logs.BoundedUtf8LogReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@Slf4j
public class LogWebSocketHandler extends TextWebSocketHandler {

    static final int MAX_WEBSOCKET_LINE_BYTES = 64 * 1_024;
    static final int MAX_WEBSOCKET_BATCH_LINES = 1_000;
    static final int MAX_WEBSOCKET_BATCH_BYTES = 1_024 * 1_024;
    static final int MAX_WEBSOCKET_SCAN_BYTES_PER_POLL = 1_024 * 1_024;
    static final long POLL_INTERVAL_MILLIS = 250L;

    private final Path logPath;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<?>> sessionTasks = new ConcurrentHashMap<>();

    public LogWebSocketHandler(LogPathResolver resolver) {
        this.logPath = resolver.getLogPath(); // ✅ всегда инициализирован
    }

    @PreDestroy
    void shutdownExecutor() {
        sessionTasks.values().forEach(task -> task.cancel(true));
        sessionTasks.clear();
        executor.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        FileState initialState = null;
        try {
            initialState = readCurrentState();
        } catch (IOException e) {
            log.warn("Unable to prepare log WebSocket stream: sessionId={}", session.getId(), e);
        }
        FileState preparedState = initialState;
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                streamLog(session, preparedState);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (session.isOpen()) {
                    log.warn("Log WebSocket stream stopped unexpectedly: sessionId={}", session.getId(), e);
                }
            } finally {
                sessionTasks.remove(session.getId(), taskReference.get());
            }
            return null;
        });
        taskReference.set(task);
        Future<?> previous = sessionTasks.put(session.getId(), task);
        if (previous != null) {
            previous.cancel(true);
        }
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            sessionTasks.remove(session.getId(), task);
            throw e;
        }
    }

    private void streamLog(WebSocketSession session, FileState preparedState) throws Exception {
        FileIdentity activeIdentity = preparedState == null ? null : preparedState.identity();
        long filePointer = preparedState == null ? 0L : preparedState.size();
        boolean initialized = preparedState != null;
        BoundedUtf8LogReader.IncrementalReader lineReader =
                new BoundedUtf8LogReader.IncrementalReader(MAX_WEBSOCKET_LINE_BYTES);

        while (session.isOpen() && !Thread.currentThread().isInterrupted()) {
            FileState currentState = readCurrentState();
            if (currentState == null) {
                Thread.sleep(POLL_INTERVAL_MILLIS);
                continue;
            }

            if (!initialized) {
                activeIdentity = currentState.identity();
                filePointer = currentState.size();
                initialized = true;
                Thread.sleep(POLL_INTERVAL_MILLIS);
                continue;
            }

            if (!activeIdentity.sameFile(currentState.identity())) {
                activeIdentity = currentState.identity();
                filePointer = 0L;
                lineReader.reset();
            } else if (currentState.size() < filePointer) {
                filePointer = 0L;
                lineReader.reset();
            }

            ReadBatch batch = readBatch(activeIdentity, filePointer, currentState.size(), lineReader);
            if (batch == null) {
                Thread.sleep(POLL_INTERVAL_MILLIS);
                continue;
            }
            filePointer = batch.offset();
            for (String line : batch.lines()) {
                if (!session.isOpen()) {
                    break;
                }
                session.sendMessage(new TextMessage(line));
            }

            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    private ReadBatch readBatch(
            FileIdentity expectedIdentity,
            long offset,
            long snapshotLength,
            BoundedUtf8LogReader.IncrementalReader lineReader
    ) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            FileState afterOpen = readCurrentState();
            if (afterOpen == null || !expectedIdentity.sameFile(afterOpen.identity())) {
                return null;
            }

            long safeEnd = Math.min(snapshotLength, file.length());
            if (offset > safeEnd) {
                lineReader.reset();
                return new ReadBatch(List.of(), 0L);
            }

            file.seek(offset);
            List<String> lines = new ArrayList<>();
            int batchBytes = 0;
            int scannedBytes = 0;
            while (file.getFilePointer() < safeEnd
                    && lines.size() < MAX_WEBSOCKET_BATCH_LINES
                    && batchBytes <= MAX_WEBSOCKET_BATCH_BYTES - MAX_WEBSOCKET_LINE_BYTES
                    && scannedBytes < MAX_WEBSOCKET_SCAN_BYTES_PER_POLL) {
                BoundedUtf8LogReader.ScanResult scan = lineReader.readNext(
                        file,
                        safeEnd,
                        MAX_WEBSOCKET_SCAN_BYTES_PER_POLL - scannedBytes
                );
                scannedBytes += scan.scannedBytes();
                if (scan.line() == null) {
                    break;
                }
                BoundedUtf8LogReader.Line line = scan.line();
                lines.add(line.value());
                batchBytes += line.storedBytes();
            }
            return new ReadBatch(List.copyOf(lines), file.getFilePointer());
        } catch (FileNotFoundException ignored) {
            return null;
        }
    }

    private FileState readCurrentState() throws IOException {
        if (!Files.isRegularFile(logPath)) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(logPath, BasicFileAttributes.class);
            return new FileState(FileIdentity.from(attributes), attributes.size());
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelSessionTask(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        cancelSessionTask(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void cancelSessionTask(WebSocketSession session) {
        Future<?> task = sessionTasks.remove(session.getId());
        if (task != null) {
            task.cancel(true);
        }
    }

    private record FileState(FileIdentity identity, long size) {
    }

    private record ReadBatch(List<String> lines, long offset) {
    }

    private record FileIdentity(Object fileKey, long creationTimeMillis) {
        static FileIdentity from(BasicFileAttributes attributes) {
            return new FileIdentity(attributes.fileKey(), attributes.creationTime().toMillis());
        }

        boolean sameFile(FileIdentity other) {
            if (fileKey != null && other.fileKey != null) {
                return Objects.equals(fileKey, other.fileKey);
            }
            return creationTimeMillis == other.creationTimeMillis;
        }
    }

}
