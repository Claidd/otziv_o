package com.hunt.otziv.logs.conf;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LogPathResolver {

    public Path getLogPath() {
        Path dockerPath = Path.of("/app/logs/app.log");
        Path localPath = Path.of("logs/app.log");
        return Files.exists(dockerPath) ? dockerPath : localPath;
    }

    public Path getLogPathForDate(String date) {
        if (date != null && !date.isBlank()) {
            Path rotatedLog = Path.of("/app/logs/app." + date + ".log");
            if (Files.exists(rotatedLog)) return rotatedLog;
        }
        return getLogPath(); // вернёт текущий лог
    }
}