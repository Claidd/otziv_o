package com.hunt.otziv.metric_snapshots.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DiskSpaceAlertService {

    static final String SOURCE_TYPE = "SYSTEM_DISK_SPACE";
    private static final String NORMAL = "NORMAL";
    private static final String WARNING = "WARNING";
    private static final String CRITICAL = "CRITICAL";
    private static final String EMERGENCY = "EMERGENCY";

    private final DiskSpaceUsageProvider usageProvider;
    private final AppSettingService appSettingService;
    private final PersonalReminderService personalReminderService;
    private final UserService userService;
    private final TelegramService telegramService;
    private final Clock clock;

    @Value("${otziv.monitoring.disk.warning-percent:80}")
    private int warningPercent = 80;

    @Value("${otziv.monitoring.disk.critical-percent:90}")
    private int criticalPercent = 90;

    @Value("${otziv.monitoring.disk.emergency-percent:95}")
    private int emergencyPercent = 95;

    @Value("${otziv.monitoring.disk.repeat-hours:6}")
    private int repeatHours = 6;

    @Autowired
    public DiskSpaceAlertService(
            DiskSpaceUsageProvider usageProvider,
            AppSettingService appSettingService,
            PersonalReminderService personalReminderService,
            UserService userService,
            TelegramService telegramService
    ) {
        this(usageProvider, appSettingService, personalReminderService, userService, telegramService, Clock.systemUTC());
    }

    DiskSpaceAlertService(
            DiskSpaceUsageProvider usageProvider,
            AppSettingService appSettingService,
            PersonalReminderService personalReminderService,
            UserService userService,
            TelegramService telegramService,
            Clock clock
    ) {
        this.usageProvider = usageProvider;
        this.appSettingService = appSettingService;
        this.personalReminderService = personalReminderService;
        this.userService = userService;
        this.telegramService = telegramService;
        this.clock = clock;
    }

    public DiskSpaceUsageProvider.DiskUsage checkAndNotify() {
        DiskSpaceUsageProvider.DiskUsage usage = usageProvider.current();
        String level = level(usage.usedPercent());
        String previous = appSettingService.getString(AppSettingService.MONITORING_DISK_LAST_LEVEL, NORMAL);
        Instant now = clock.instant();
        Instant lastAlertAt = parseInstant(appSettingService.getString(
                AppSettingService.MONITORING_DISK_LAST_ALERT_AT,
                ""
        ));

        boolean recovered = NORMAL.equals(level) && !NORMAL.equals(previous);
        boolean escalated = rank(level) > rank(previous);
        boolean repeatDue = !NORMAL.equals(level)
                && level.equals(previous)
                && (lastAlertAt == null || !now.isBefore(lastAlertAt.plus(Duration.ofHours(Math.max(1, repeatHours)))));

        if (recovered || escalated || (!NORMAL.equals(level) && !level.equals(previous)) || repeatDue) {
            notifyRecipients(level, usage, recovered, now);
            appSettingService.setString(AppSettingService.MONITORING_DISK_LAST_ALERT_AT, now.toString());
        }
        if (!level.equals(previous)) {
            appSettingService.setString(AppSettingService.MONITORING_DISK_LAST_LEVEL, level);
        }
        return usage;
    }

    private String level(int usedPercent) {
        if (usedPercent >= Math.max(emergencyPercent, Math.max(criticalPercent, warningPercent))) {
            return EMERGENCY;
        }
        if (usedPercent >= Math.max(criticalPercent, warningPercent)) {
            return CRITICAL;
        }
        if (usedPercent >= warningPercent) {
            return WARNING;
        }
        return NORMAL;
    }

    private int rank(String level) {
        return EMERGENCY.equals(level) ? 3 : CRITICAL.equals(level) ? 2 : WARNING.equals(level) ? 1 : 0;
    }

    private void notifyRecipients(
            String level,
            DiskSpaceUsageProvider.DiskUsage usage,
            boolean recovered,
            Instant now
    ) {
        String title = recovered
                ? "Место на сервере восстановлено"
                : EMERGENCY.equals(level) ? "Аварийно мало места на сервере"
                : CRITICAL.equals(level) ? "Критически мало места на сервере"
                : "Заканчивается место на сервере";
        String text = "Использовано: " + usage.usedPercent() + "% ("
                + gibibytes(usage.usedBytes()) + " из " + gibibytes(usage.totalBytes()) + " ГиБ)."
                + "\nСвободно: " + gibibytes(usage.usableBytes()) + " ГиБ."
                + (recovered
                ? "\nЗаполнение снова ниже порога " + warningPercent + "%."
                : "\nПороги: предупреждение " + warningPercent + "%, критический "
                        + criticalPercent + "%, аварийный " + emergencyPercent + "%.");
        long sourceId = now.getEpochSecond() / 3600L * 10L + rank(level);
        recipients().values().forEach(user -> notifyUser(user, title, text, sourceId));
        log.warn("Disk space state: level={}, usedPercent={}, freeBytes={}", level, usage.usedPercent(), usage.usableBytes());
    }

    private String gibibytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0d / 1024.0d / 1024.0d);
    }

    private Map<Long, User> recipients() {
        Map<Long, User> result = new LinkedHashMap<>();
        addRecipients(result, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(result, userService.getAllOwners("ROLE_ADMIN"));
        return result;
    }

    private void addRecipients(Map<Long, User> recipients, List<User> users) {
        if (users == null) {
            return;
        }
        users.stream()
                .filter(user -> user != null && user.getId() != null && user.isActive())
                .forEach(user -> recipients.putIfAbsent(user.getId(), user));
    }

    private void notifyUser(User user, String title, String text, long sourceId) {
        try {
            personalReminderService.createSystemReminderDueNow(user, title, text, SOURCE_TYPE, sourceId, null);
        } catch (RuntimeException e) {
            log.warn("Не удалось создать уведомление о диске для userId={}", user.getId(), e);
        }
        if (user.getTelegramChatId() == null) {
            return;
        }
        try {
            telegramService.sendMessage(user.getTelegramChatId(), title + "\n\n" + text);
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить Telegram-уведомление о диске для userId={}", user.getId(), e);
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
