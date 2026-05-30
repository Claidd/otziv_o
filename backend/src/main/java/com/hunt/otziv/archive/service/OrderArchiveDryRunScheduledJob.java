package com.hunt.otziv.archive.service;

import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsResponse;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderArchiveDryRunScheduledJob {

    private static final Duration DEFAULT_CATCH_UP_WINDOW = Duration.ofHours(3);

    private final OrderArchiveDryRunService archiveDryRunService;
    private final AtomicReference<String> lastDecisionLogKey = new AtomicReference<>("");
    private Clock clock = Clock.systemDefaultZone();

    @Value("${otziv.archive.orders.schedule.catch-up-window:PT3H}")
    private Duration catchUpWindow = DEFAULT_CATCH_UP_WINDOW;

    @Scheduled(
            fixedDelayString = "${otziv.archive.orders.schedule.poll-delay-ms:30000}",
            initialDelayString = "${otziv.archive.orders.schedule.initial-delay-ms:30000}"
    )
    public void runScheduledDryRun() {
        try {
            ArchiveOrdersSettingsResponse settings = archiveDryRunService.settings();
            ScheduledArchiveDecision decision = scheduledDecision(settings);
            if (decision.type() == ScheduledArchiveDecisionType.NOT_DUE) {
                return;
            }

            if (decision.type() == ScheduledArchiveDecisionType.MISSED_WINDOW) {
                logMissedWindowOnce(decision);
                return;
            }

            if (!archiveDryRunService.claimScheduledArchiveRun(decision.runKey())) {
                logAlreadyClaimedOnce(decision);
                return;
            }

            log.info(
                    "Scheduled order archive starting: runKey={} scheduledAt={} now={} deadline={}",
                    decision.runKey(),
                    decision.scheduledAt(),
                    decision.now(),
                    decision.deadline()
            );
            Object result = archiveDryRunService.runScheduledConfiguredArchive();
            if (result != null) {
                log.info("Scheduled order archive completed: {}", result);
            } else {
                log.info("Scheduled order archive completed without run: runtime settings disabled after claim");
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled order archive failed", exception);
        }
    }

    ScheduledArchiveDecision scheduledDecision(ArchiveOrdersSettingsResponse settings) {
        ZoneId zoneId = zoneId(settings);
        return scheduledDecision(settings, ZonedDateTime.now(clock).withZoneSameInstant(zoneId));
    }

    ScheduledArchiveDecision scheduledDecision(ArchiveOrdersSettingsResponse settings, ZonedDateTime now) {
        if (settings == null || !settings.scheduleWorkerEnabled() || !settings.scheduleEnabled()) {
            return ScheduledArchiveDecision.notDue();
        }
        try {
            String timeValue = settings.scheduleTime();
            if (timeValue == null || timeValue.isBlank()) {
                return ScheduledArchiveDecision.notDue();
            }
            ZoneId zoneId = ZoneId.of(settings.scheduleZone());
            LocalTime scheduleTime = LocalTime.parse(timeValue.trim()).truncatedTo(ChronoUnit.MINUTES);
            ZonedDateTime zonedNow = now.withZoneSameInstant(zoneId);
            ZonedDateTime scheduledAt = zonedNow.toLocalDate().atTime(scheduleTime).atZone(zoneId);
            Duration window = normalizedCatchUpWindow();
            ZonedDateTime deadline = scheduledAt.plus(window);
            String runKey = zonedNow.toLocalDate() + " " + scheduleTime + " " + zoneId;

            if (zonedNow.isBefore(scheduledAt)) {
                return ScheduledArchiveDecision.notDue();
            }
            if (zonedNow.isAfter(deadline)) {
                return new ScheduledArchiveDecision(
                        ScheduledArchiveDecisionType.MISSED_WINDOW,
                        runKey,
                        scheduledAt,
                        zonedNow,
                        deadline
                );
            }

            return new ScheduledArchiveDecision(
                    ScheduledArchiveDecisionType.DUE,
                    runKey,
                    scheduledAt,
                    zonedNow,
                    deadline
            );
        } catch (DateTimeException exception) {
            log.warn("Scheduled order archive has invalid runtime schedule: {}", exception.getMessage());
            return ScheduledArchiveDecision.notDue();
        }
    }

    void setCatchUpWindow(Duration catchUpWindow) {
        this.catchUpWindow = catchUpWindow;
    }

    void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    private ZoneId zoneId(ArchiveOrdersSettingsResponse settings) {
        if (settings == null || settings.scheduleZone() == null || settings.scheduleZone().isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(settings.scheduleZone());
        } catch (DateTimeException exception) {
            return ZoneId.systemDefault();
        }
    }

    private Duration normalizedCatchUpWindow() {
        if (catchUpWindow == null || catchUpWindow.isNegative() || catchUpWindow.isZero()) {
            return DEFAULT_CATCH_UP_WINDOW;
        }
        return catchUpWindow;
    }

    private void logMissedWindowOnce(ScheduledArchiveDecision decision) {
        String key = "missed:" + decision.runKey();
        if (!key.equals(lastDecisionLogKey.getAndSet(key))) {
            log.info(
                    "Scheduled order archive skipped: scheduledAt={} now={} deadline={} catchUpWindow={}",
                    decision.scheduledAt(),
                    decision.now(),
                    decision.deadline(),
                    normalizedCatchUpWindow()
            );
        }
    }

    private void logAlreadyClaimedOnce(ScheduledArchiveDecision decision) {
        String key = "claimed:" + decision.runKey();
        if (!key.equals(lastDecisionLogKey.getAndSet(key))) {
            log.info("Scheduled order archive skipped: runKey={} has already been claimed", decision.runKey());
        }
    }

    enum ScheduledArchiveDecisionType {
        NOT_DUE,
        DUE,
        MISSED_WINDOW
    }

    record ScheduledArchiveDecision(
            ScheduledArchiveDecisionType type,
            String runKey,
            ZonedDateTime scheduledAt,
            ZonedDateTime now,
            ZonedDateTime deadline
    ) {
        static ScheduledArchiveDecision notDue() {
            return new ScheduledArchiveDecision(ScheduledArchiveDecisionType.NOT_DUE, "", null, null, null);
        }
    }
}
