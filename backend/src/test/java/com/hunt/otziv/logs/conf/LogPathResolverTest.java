package com.hunt.otziv.logs.conf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPathResolverTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resolvesEveryProductionRotatedSegmentWithStrictSafeSelectors() throws Exception {
        Path current = Files.createFile(tempDirectory.resolve("app.log"));
        Path older = Files.createFile(tempDirectory.resolve("app.2026-07-30.0.log"));
        Path newerFirst = Files.createFile(tempDirectory.resolve("app.2026-07-31.0.log"));
        Path newerSecond = Files.createFile(tempDirectory.resolve("app.2026-07-31.2.log"));
        Path newerLatest = Files.createFile(tempDirectory.resolve("app.2026-07-31.10.log"));
        Files.createFile(tempDirectory.resolve("app.2026-02-30.0.log"));
        Files.createFile(tempDirectory.resolve("app.2026-07-31.01.log"));
        Files.createFile(tempDirectory.resolve("app.not-a-date.log"));
        Files.createFile(tempDirectory.resolve("app.2026-07-31.log"));
        LogPathResolver resolver = new LocalLogPathResolver(current);

        assertEquals(
                List.of("2026-07-31.10", "2026-07-31.2", "2026-07-31.0", "2026-07-30.0"),
                resolver.getAvailableDates()
        );
        assertEquals(older, resolver.resolveLogSelection("2026-07-30.0").orElseThrow().path());
        assertEquals(newerFirst, resolver.resolveLogSelection("2026-07-31.0").orElseThrow().path());
        assertEquals(newerSecond, resolver.resolveLogSelection("2026-07-31.2").orElseThrow().path());
        assertEquals(newerLatest, resolver.resolveLogSelection("2026-07-31").orElseThrow().path());
        assertTrue(resolver.resolveLogSelection("../../etc/passwd").isEmpty());
        assertTrue(resolver.resolveLogSelection("2026-02-30.0").isEmpty());
        assertTrue(resolver.resolveLogSelection("2026-07-31.01").isEmpty());
        assertEquals(current, resolver.getLogPathForDate("../../etc/passwd"));
    }

    private static final class LocalLogPathResolver extends LogPathResolver {
        private final Path current;

        private LocalLogPathResolver(Path current) {
            this.current = current;
        }

        @Override
        public Path getLogPath() {
            return current;
        }
    }
}
