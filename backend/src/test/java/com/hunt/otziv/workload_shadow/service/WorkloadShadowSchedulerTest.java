package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class WorkloadShadowSchedulerTest {

    @Test
    void usesFiveMinutesNearShiftEndAndTenMinutesOtherwise() {
        WorkloadShadowSettingsResponse settings = settings();

        assertEquals(10, WorkloadShadowScheduler.effectiveIntervalMinutes(
                settings,
                LocalTime.of(18, 0)
        ));
        assertEquals(5, WorkloadShadowScheduler.effectiveIntervalMinutes(
                settings,
                LocalTime.of(22, 0)
        ));
    }

    private WorkloadShadowSettingsResponse settings() {
        return new WorkloadShadowSettingsResponse(
                "SHADOW",
                false,
                true,
                true,
                10,
                5,
                120,
                "Asia/Irkutsk",
                "10:00",
                "23:00",
                4,
                3,
                5,
                10,
                3,
                10,
                10,
                true,
                30,
                30,
                3,
                85,
                80,
                2,
                15,
                1,
                25,
                2,
                30,
                3,
                14,
                2,
                60,
                30,
                400,
                90,
                60,
                30,
                10,
                8,
                5,
                1,
                1000,
                1
        );
    }
}
