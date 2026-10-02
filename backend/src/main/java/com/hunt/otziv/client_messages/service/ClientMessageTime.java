package com.hunt.otziv.client_messages.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Keeps storage timestamps in the JVM zone and business timestamps in Irkutsk. */
@Component
public class ClientMessageTime {

    private final Clock clock;

    public ClientMessageTime() {
        this(Clock.systemDefaultZone());
    }

    ClientMessageTime(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    LocalDateTime nowStorage() {
        return LocalDateTime.now(clock);
    }

    LocalDateTime nowIrkutsk() {
        return ZonedDateTime.now(clock)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime()
                .withNano(0);
    }

    LocalDateTime toIrkutskTime(LocalDateTime storageTime) {
        return storageTime.atZone(clock.getZone())
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime();
    }

    LocalDateTime toStorageTime(LocalDateTime irkutskTime) {
        return irkutskTime.atZone(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .withZoneSameInstant(clock.getZone())
                .toLocalDateTime();
    }

    LocalDateTime databaseTimestamp(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.withNano((value.getNano() / 1_000) * 1_000);
    }
}
