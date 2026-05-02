package com.hunt.otziv.whatsapp.service.fichi;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
public class PersistentSentHashes {


    private static final Path HASH_FILE = Paths.get("sent-hashes/sent-hashes.txt"); // путь в volume
    private static final Duration TTL = Duration.ofDays(30);
    private final Map<String, LocalDateTime> hashMap = new ConcurrentHashMap<>();

    public PersistentSentHashes() {
        try {
            Files.createDirectories(HASH_FILE.getParent()); // Создаём папку, если её нет
        } catch (IOException e) {
            log.error("❌ Не удалось создать директорию для sent-hashes.txt: {}", e.getMessage());
        }
        loadHashesFromFile();
    }

    public boolean isNew(String hash) {
        cleanupOldHashes();
        if (hashMap.containsKey(hash)) return false;

        hashMap.put(hash, LocalDateTime.now());
        appendHashToFile(hash);
        return true;
    }

    private void loadHashesFromFile() {
        if (!Files.exists(HASH_FILE)) return;

        try (Stream<String> lines = Files.lines(HASH_FILE)) {
            lines.forEach(line -> {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    String hash = parts[0];
                    LocalDateTime timestamp = LocalDateTime.parse(parts[1]);
                    if (Duration.between(timestamp, LocalDateTime.now()).compareTo(TTL) <= 0) {
                        hashMap.put(hash, timestamp);
                    }
                }
            });
        } catch (IOException e) {
            log.error("⚠️ Не удалось загрузить sent-hashes.txt: {}", e.getMessage());
        }
    }

    private void appendHashToFile(String hash) {
        String line = hash + ":" + LocalDateTime.now();
        try {
            Files.write(HASH_FILE, (line + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("⚠️ Не удалось записать хэш в файл: {}", e.getMessage());
        }
    }

    private void cleanupOldHashes() {
        LocalDateTime now = LocalDateTime.now();
        boolean needsRewrite = false;

        Iterator<Map.Entry<String, LocalDateTime>> it = hashMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LocalDateTime> entry = it.next();
            if (Duration.between(entry.getValue(), now).compareTo(TTL) > 0) {
                it.remove();
                needsRewrite = true;
            }
        }

        if (needsRewrite) {
            try {
                Files.write(HASH_FILE, new byte[0], StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                for (Map.Entry<String, LocalDateTime> entry : hashMap.entrySet()) {
                    String line = entry.getKey() + ":" + entry.getValue();
                    Files.write(HASH_FILE, (line + System.lineSeparator()).getBytes(), StandardOpenOption.APPEND);
                }
                log.info("🧹 Старые хэши удалены и файл перезаписан");
            } catch (IOException e) {
                log.error("⚠️ Не удалось перезаписать файл sent-hashes.txt: {}", e.getMessage());
            }
        }
    }
}
