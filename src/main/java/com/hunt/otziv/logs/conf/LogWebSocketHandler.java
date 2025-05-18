package com.hunt.otziv.logs.conf;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private final Path logPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public LogWebSocketHandler(LogPathResolver resolver) {
        this.logPath = resolver.getLogPath(); // ✅ всегда инициализирован
    }

    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) {
        executor.submit(() -> {
            try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
                long filePointer = file.length();
                while (session.isOpen()) {
                    long fileLength = file.length();
                    if (fileLength > filePointer) {
                        file.seek(filePointer);
                        String line;
                        while ((line = file.readLine()) != null) {
                            session.sendMessage(new TextMessage(line));
                        }
                        filePointer = file.getFilePointer();
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}


