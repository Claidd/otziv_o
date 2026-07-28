package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowBusinessTimeTest {

    @Mock private AppSettingService settings;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-07-27T21:30:00Z"), ZoneOffset.UTC);

    @Test
    void usesConfiguredBusinessZoneForDateAndTime() {
        when(settings.getString(
                WorkloadShadowBusinessTime.BUSINESS_ZONE_SETTING,
                "Z"
        )).thenReturn("Asia/Irkutsk");

        assertThat(WorkloadShadowBusinessTime.now(settings, clock))
                .isEqualTo(LocalDateTime.of(2026, 7, 28, 5, 30));
        assertThat(WorkloadShadowBusinessTime.today(settings, clock))
                .isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    void fallsBackToClockZoneForCorruptSetting() {
        when(settings.getString(
                WorkloadShadowBusinessTime.BUSINESS_ZONE_SETTING,
                "Z"
        )).thenReturn("not/a-zone");

        assertThat(WorkloadShadowBusinessTime.now(settings, clock))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 21, 30));
    }
}
