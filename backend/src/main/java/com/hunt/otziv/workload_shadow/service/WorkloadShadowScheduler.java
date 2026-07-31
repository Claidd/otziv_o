package com.hunt.otziv.workload_shadow.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkloadShadowScheduler {

    private final WorkloadShadowSettingsService settingsService;
    private final WorkloadShadowRunService runService;
    private final WorkloadShadowCoordinator coordinator;
    private final WorkloadShadowRefreshSignal refreshSignal;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void tick() {
        var settings = settingsService.current();
        if (!settings.observationEnabled() || coordinator.isRunning()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(settingsService.zone(settings));
        boolean dirty = refreshSignal.isDirty();
        int intervalMinutes = effectiveIntervalMinutes(settings, now.toLocalTime());
        LocalDateTime lastSuccess = runService.lastSuccessfulFinishedAt();
        boolean due = lastSuccess == null
                || Duration.between(lastSuccess, now).toMinutes() >= intervalMinutes;
        if (!dirty && !due) {
            return;
        }

        try {
            coordinator.recalculate(dirty ? "EVENT_DIRTY" : "SCHEDULED");
        } catch (Exception exception) {
            refreshSignal.markDirty();
            log.warn("Workload shadow scheduled tick failed: {}", exception.getMessage());
            log.debug("Workload shadow scheduled failure", exception);
        }
    }

    static int effectiveIntervalMinutes(
            com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse settings,
            LocalTime now
    ) {
        LocalTime intakeCutoff = LocalTime.parse(settings.shiftEnd());
        long minutesUntilCutoff = Duration.between(now, intakeCutoff).toMinutes();
        boolean nearEnd = !now.isBefore(intakeCutoff)
                || minutesUntilCutoff >= 0
                && minutesUntilCutoff <= settings.nearEndWindowMinutes();
        return nearEnd ? settings.nearEndIntervalMinutes() : settings.schedulerIntervalMinutes();
    }
}
