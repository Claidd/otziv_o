package com.hunt.otziv.client_messages;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMessageSlotPlannerTest {

    private final ClientMessageSlotPlanner planner = new ClientMessageSlotPlanner();

    @Test
    void keepsTimeInsideMorningWindow() {
        LocalDateTime time = LocalDateTime.of(2026, 5, 24, 10, 30);

        assertEquals(time, planner.nextAllowedAt(time));
        assertTrue(planner.isAllowedNow(time));
    }

    @Test
    void movesLunchTimeToAfternoonWindow() {
        LocalDateTime lunch = LocalDateTime.of(2026, 5, 24, 12, 30);

        assertEquals(LocalDateTime.of(2026, 5, 24, 14, 0), planner.nextAllowedAt(lunch));
        assertFalse(planner.isAllowedNow(lunch));
    }

    @Test
    void movesEarlyEveningPauseToNineteen() {
        LocalDateTime pause = LocalDateTime.of(2026, 5, 24, 17, 30);

        assertEquals(LocalDateTime.of(2026, 5, 24, 19, 0), planner.nextAllowedAt(pause));
        assertFalse(planner.isAllowedNow(pause));
    }

    @Test
    void movesAfterNineToNextMorning() {
        LocalDateTime late = LocalDateTime.of(2026, 5, 24, 21, 1);

        assertEquals(LocalDateTime.of(2026, 5, 25, 10, 0), planner.nextAllowedAt(late));
        assertFalse(planner.isAllowedNow(late));
    }

    @Test
    void appliesGapAndKeepsResultInsideAllowedWindow() {
        LocalDateTime candidate = LocalDateTime.of(2026, 5, 24, 11, 59);
        LocalDateTime lastSent = LocalDateTime.of(2026, 5, 24, 11, 58);

        assertEquals(
                LocalDateTime.of(2026, 5, 24, 14, 0),
                planner.afterGap(candidate, lastSent, 180)
        );
    }

    @Test
    void supportsCustomWindowsSpec() {
        LocalDateTime candidate = LocalDateTime.of(2026, 5, 24, 10, 30);
        String customWindows = "09:00-10:00,15:00-16:00";

        assertEquals(
                LocalDateTime.of(2026, 5, 24, 15, 0),
                planner.nextAllowedAt(candidate, customWindows)
        );
        assertTrue(ClientMessageSlotPlanner.isValidWindowsSpec(customWindows));
        assertFalse(ClientMessageSlotPlanner.isValidWindowsSpec("10:00-12:00,11:00-13:00"));
    }
}
