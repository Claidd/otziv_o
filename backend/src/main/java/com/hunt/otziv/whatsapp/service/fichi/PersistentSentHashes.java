package com.hunt.otziv.whatsapp.service.fichi;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public class PersistentSentHashes {

    private static final Path DEFAULT_HASH_FILE = Paths.get("sent-hashes/sent-hashes.txt");
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);
    private static final Pattern SHA1_HEX = Pattern.compile("[a-f0-9]{40}");

    private final Path hashFile;
    private final Clock clock;
    private final Duration ttl;
    private final Object stateLock = new Object();
    private final Map<String, LocalDateTime> hashMap = new ConcurrentHashMap<>();

    public PersistentSentHashes() {
        this(DEFAULT_HASH_FILE, Clock.systemDefaultZone(), DEFAULT_TTL);
    }

    PersistentSentHashes(Path hashFile, Clock clock, Duration ttl) {
        this.hashFile = Objects.requireNonNull(hashFile, "hashFile").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Sent-hash TTL must be positive");
        }

        Path parent = this.hashFile.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Sent-hash file must have a parent directory");
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the sent-hash directory", exception);
        }
        loadHashesFromFile();
    }

    public boolean isNew(String hash) {
        String normalizedHash = normalizeHash(hash);
        synchronized (stateLock) {
            cleanupOldHashes();
            LocalDateTime timestamp = LocalDateTime.now(clock);
            if (hashMap.putIfAbsent(normalizedHash, timestamp) != null) {
                return false;
            }

            try {
                appendHashToFile(normalizedHash, timestamp);
                return true;
            } catch (IOException exception) {
                hashMap.remove(normalizedHash, timestamp);
                throw new IllegalStateException("Unable to persist a sent-message hash", exception);
            }
        }
    }

    private void loadHashesFromFile() {
        if (!Files.exists(hashFile)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int[] malformedLines = {0};
        try (Stream<String> lines = Files.lines(hashFile, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int separator = line.indexOf(':');
                if (separator <= 0 || separator == line.length() - 1) {
                    malformedLines[0]++;
                    return;
                }

                String hash = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String timestampText = line.substring(separator + 1).trim();
                if (!SHA1_HEX.matcher(hash).matches()) {
                    malformedLines[0]++;
                    return;
                }

                try {
                    LocalDateTime timestamp = LocalDateTime.parse(timestampText);
                    Duration age = Duration.between(timestamp, now);
                    if (!age.isNegative() && age.compareTo(ttl) <= 0) {
                        hashMap.merge(hash, timestamp, (first, second) -> first.isAfter(second) ? first : second);
                    } else if (age.isNegative() && timestamp.isBefore(now.plusMinutes(5))) {
                        // Tolerate a small wall-clock correction without retaining
                        // arbitrarily far-future entries forever.
                        hashMap.put(hash, timestamp);
                    }
                } catch (DateTimeParseException exception) {
                    malformedLines[0]++;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load the sent-hash file", exception);
        }

        if (malformedLines[0] > 0) {
            log.warn("Skipped {} malformed lines while loading {}", malformedLines[0], hashFile);
        }
    }

    private void appendHashToFile(String hash, LocalDateTime timestamp) throws IOException {
        byte[] bytes = (hash + ":" + timestamp + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                hashFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void cleanupOldHashes() {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean needsRewrite = false;

        Iterator<Map.Entry<String, LocalDateTime>> iterator = hashMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalDateTime> entry = iterator.next();
            if (Duration.between(entry.getValue(), now).compareTo(ttl) > 0) {
                iterator.remove();
                needsRewrite = true;
            }
        }

        if (needsRewrite) {
            rewriteSnapshotAtomically();
        }
    }

    private void rewriteSnapshotAtomically() {
        Path parent = hashFile.getParent();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".sent-hashes-", ".tmp");
            StringBuilder snapshot = new StringBuilder();
            hashMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> snapshot
                            .append(entry.getKey())
                            .append(':')
                            .append(entry.getValue())
                            .append(System.lineSeparator()));
            Files.writeString(
                    temporary,
                    snapshot,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        hashFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, hashFile, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            log.info("Expired sent-message hashes were removed from {}", hashFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to atomically rewrite the sent-hash file", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupException) {
                    log.warn("Unable to remove temporary sent-hash snapshot {}", temporary, cleanupException);
                }
            }
        }
    }

    private String normalizeHash(String hash) {
        String normalized = Objects.requireNonNull(hash, "hash").trim().toLowerCase(Locale.ROOT);
        if (!SHA1_HEX.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Sent-message hash must be a 40-character SHA-1 hex value");
        }
        return normalized;
    }
}
