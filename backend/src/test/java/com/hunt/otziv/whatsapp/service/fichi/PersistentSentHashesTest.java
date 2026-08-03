package com.hunt.otziv.whatsapp.service.fichi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentSentHashesTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T03:00:00Z"),
            ZoneOffset.UTC
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadsTimestampContainingColonsAndRejectsTheSameHashAfterRestart() throws Exception {
        Path storage = temporaryDirectory.resolve("sent-hashes.txt");
        PersistentSentHashes firstProcess = new PersistentSentHashes(storage, CLOCK, Duration.ofDays(30));

        assertTrue(firstProcess.isNew(HASH));
        assertTrue(Files.readString(storage, StandardCharsets.UTF_8)
                .contains(HASH + ":2026-08-03T03:00"));

        PersistentSentHashes restartedProcess = new PersistentSentHashes(storage, CLOCK, Duration.ofDays(30));
        assertFalse(restartedProcess.isNew(HASH));
        assertEquals(1, Files.readAllLines(storage, StandardCharsets.UTF_8).size());
    }

    @Test
    void admitsExactlyOneConcurrentRegistrationAndWritesOneDurableLine() throws Exception {
        Path storage = temporaryDirectory.resolve("sent-hashes.txt");
        PersistentSentHashes hashes = new PersistentSentHashes(storage, CLOCK, Duration.ofDays(30));
        int workers = 24;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return hashes.isNew(HASH);
                }));
            }
            ready.await();
            start.countDown();

            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(1, Files.readAllLines(storage, StandardCharsets.UTF_8).size());
        } finally {
            executor.shutdownNow();
        }
    }
}
