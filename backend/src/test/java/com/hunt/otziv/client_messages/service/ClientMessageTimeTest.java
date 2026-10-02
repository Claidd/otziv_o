package com.hunt.otziv.client_messages.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ClientMessageTimeTest {

    @Test
    void injectedClockPreservesStoragePrecisionAndTruncatesBusinessTimeToSeconds() {
        var time = new ClientMessageTime(Clock.fixed(
                Instant.parse("2026-08-31T16:00:00.123456789Z"), ZoneId.of("America/Buenos_Aires")));

        assertEquals(LocalDateTime.parse("2026-08-31T13:00:00.123456789"), time.nowStorage());
        assertEquals(LocalDateTime.parse("2026-09-01T00:00:00"), time.nowIrkutsk());
    }

    @Test
    void springDefaultKeepsJvmStorageZone() {
        try (var context = new AnnotationConfigApplicationContext(ClientMessageTime.class)) {
            var time = context.getBean(ClientMessageTime.class);
            var businessTime = LocalDateTime.parse("2026-09-01T00:00:00");
            var expected = businessTime.atZone(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                    .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

            assertEquals(expected, time.toStorageTime(businessTime));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "UTC,2026-08-31T16:30:00.123456789,2026-09-01T00:30:00.123456789",
            "Asia/Irkutsk,2026-08-31T23:45:00.123456789,2026-08-31T23:45:00.123456789",
            "Asia/Kolkata,2026-08-31T23:45:00.123456789,2026-09-01T02:15:00.123456789",
            "America/Buenos_Aires,2026-08-31T20:30:00.123456789,2026-09-01T07:30:00.123456789",
            "America/New_York,2026-03-08T01:59:59,2026-03-08T14:59:59"
    })
    void zoneConversionsPreserveInstantDateAndPrecision(String zone, String storage, String business) {
        var time = new ClientMessageTime(Clock.fixed(Instant.EPOCH, ZoneId.of(zone)));
        var storageTime = LocalDateTime.parse(storage);
        var businessTime = LocalDateTime.parse(business);

        assertEquals(businessTime, time.toIrkutskTime(storageTime));
        assertEquals(storageTime, time.toStorageTime(businessTime));
    }

    @Test
    void nonexistentStorageTimeKeepsExistingForwardResolutionAtDstGap() {
        var time = new ClientMessageTime(Clock.fixed(Instant.EPOCH, ZoneId.of("America/New_York")));
        var businessTime = time.toIrkutskTime(LocalDateTime.parse("2026-03-08T02:30:00"));

        assertEquals(LocalDateTime.parse("2026-03-08T15:30:00"), businessTime);
        assertEquals(LocalDateTime.parse("2026-03-08T03:30:00"), time.toStorageTime(businessTime));
    }

    @Test
    void ambiguousStorageTimeKeepsEarlierOffsetAndReverseConversionLosesOffsetAsBefore() {
        var time = new ClientMessageTime(Clock.fixed(Instant.EPOCH, ZoneId.of("America/New_York")));
        var repeatedStorageTime = LocalDateTime.parse("2026-11-01T01:30:00");

        assertEquals(LocalDateTime.parse("2026-11-01T13:30:00"), time.toIrkutskTime(repeatedStorageTime));
        assertEquals(repeatedStorageTime, time.toStorageTime(LocalDateTime.parse("2026-11-01T13:30:00")));
        assertEquals(repeatedStorageTime, time.toStorageTime(LocalDateTime.parse("2026-11-01T14:30:00")));
    }

    @ParameterizedTest
    @CsvSource({"999,0", "1000,1000", "123456789,123456000", "999999999,999999000"})
    void databaseTimestampTruncatesWithoutRoundingOrChangingSeconds(int nanos, int expectedNanos) {
        var time = new ClientMessageTime(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var value = LocalDateTime.parse("2026-08-31T23:59:59").withNano(nanos);

        assertEquals(LocalDateTime.parse("2026-08-31T23:59:59").withNano(expectedNanos),
                time.databaseTimestamp(value));
    }

    @Test
    void absentDatabaseTimestampStaysAbsent() {
        var time = new ClientMessageTime(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertNull(time.databaseTimestamp(null));
    }
}
