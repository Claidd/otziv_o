package com.hunt.otziv.metric_snapshots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The daily server report for the owner and administrators: disk, host housekeeping, Docker, MySQL binary logs,
 * containers, backups and the restore drill. Host facts come from the status file the disk maintenance timer writes
 * (read-only mount); a missing or stale file reads «нет данных», never zero. Backup facts come from the gauges the
 * backup module publishes for its alerts, so this module does not depend on the backup internals.
 */
@Service
@Slf4j
public class OperationsDailyReportService {

    static final Duration STATUS_MAX_AGE = Duration.ofHours(2);
    static final Duration BACKUP_MAX_AGE = Duration.ofHours(26);
    static final long DRILL_WARNING_DAYS = 80;
    private static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final String STATUS_SCHEMA = "otziv-ops-disk-status-v1";
    // Published by s3.backup BackupEvidenceMetrics and RestoreDrillMetrics (registered only when backups are enabled).
    static final String VERIFIED_METRIC = "otziv.backup.last.verified.epoch";
    static final String EMAIL_METRIC = "otziv.backup.last.email.status";
    static final String DRILL_METRIC = "otziv.backup.restore.drill.age.days";

    private final DiskSpaceUsageProvider usageProvider;
    private final DiskSpaceAlertService delivery;
    private final ObjectProvider<MeterRegistry> meters;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${otziv.monitoring.ops-report.status-file:/app/ops-status/disk.json}")
    private String statusFile = "/app/ops-status/disk.json";

    @Autowired
    public OperationsDailyReportService(DiskSpaceUsageProvider usageProvider, DiskSpaceAlertService delivery,
                                        ObjectProvider<MeterRegistry> meters, ObjectMapper objectMapper) {
        this(usageProvider, delivery, meters, objectMapper, Clock.systemUTC());
    }

    OperationsDailyReportService(DiskSpaceUsageProvider usageProvider, DiskSpaceAlertService delivery,
                                 ObjectProvider<MeterRegistry> meters, ObjectMapper objectMapper, Clock clock) {
        this.usageProvider = usageProvider;
        this.delivery = delivery;
        this.meters = meters;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void setStatusFile(String statusFile) {
        this.statusFile = statusFile;
    }

    /** Sends the report; returns the number of Telegram chats that accepted it. */
    public int send() {
        return delivery.sendTelegramToOwnersAndAdmins(buildReport());
    }

    public String buildReport() {
        Instant now = clock.instant();
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        lines.add("Сервер: сводка за " + DATE.format(now.atZone(ZONE)));

        DiskSpaceUsageProvider.DiskUsage disk = usageProvider.current();
        lines.add("Диск: занято " + disk.usedPercent() + "% (" + gib(disk.usedBytes()) + " из "
                + gib(disk.totalBytes()) + " ГиБ), свободно " + gib(disk.usableBytes()) + " ГиБ.");
        if (disk.usedPercent() >= 80) {
            warnings.add("диск заполнен на " + disk.usedPercent() + "%");
        }

        JsonNode host = readHostStatus(now);
        if (host == null) {
            lines.add("Данные хоста: нет данных (файл состояния автоочистки отсутствует или старше "
                    + STATUS_MAX_AGE.toHours() + " ч).");
            warnings.add("нет свежих данных автоочистки");
        } else {
            hostLines(host, lines, warnings);
        }

        backupLines(now, lines, warnings);

        lines.add("");
        lines.add(warnings.isEmpty() ? "Итог: всё в порядке." : "Внимание: " + String.join("; ", warnings) + ".");
        return String.join("\n", lines);
    }

    private JsonNode readHostStatus(Instant now) {
        try {
            Path path = Path.of(statusFile);
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                return null;
            }
            JsonNode status = objectMapper.readTree(Files.readString(path));
            if (status == null || !STATUS_SCHEMA.equals(status.path("schema").asText())) {
                return null;
            }
            Instant generatedAt = Instant.parse(status.path("generatedAt").asText());
            if (generatedAt.isBefore(now.minus(STATUS_MAX_AGE)) || generatedAt.isAfter(now.plus(Duration.ofMinutes(10)))) {
                return null;
            }
            return status;
        } catch (Exception unreadable) {
            log.warn("Ops status file is unreadable: {}", unreadable.getClass().getSimpleName());
            return null;
        }
    }

    private void hostLines(JsonNode host, List<String> lines, List<String> warnings) {
        lines.add("Автоочистка: с " + host.path("triggerPercent").asInt() + "% до "
                + host.path("targetPercent").asInt() + "%.");
        cleanupLine("Последняя автоочистка", host.path("lastCleanup"), lines);
        cleanupLine("Очистка после выпуска", host.path("lastPostDeployCleanup"), lines);

        JsonNode docker = host.path("docker");
        if (docker.isObject()) {
            lines.add("Docker: образы " + text(docker.path("images").path("size"))
                    + " (можно освободить " + text(docker.path("images").path("reclaimable")) + "), тома "
                    + text(docker.path("volumes").path("size")) + ", кэш сборки "
                    + text(docker.path("buildCache").path("size")) + ".");
        } else {
            lines.add("Docker: нет данных.");
        }

        JsonNode binlog = host.path("binlog");
        if (binlog.isObject()) {
            long retentionSeconds = binlog.path("retentionSeconds").asLong();
            lines.add("Журналы MySQL (binlog): " + binlog.path("files").asInt() + " файл(ов), "
                    + gib(binlog.path("bytes").asLong()) + " ГиБ, срок хранения "
                    + formatDays(retentionSeconds) + ".");
        } else {
            lines.add("Журналы MySQL (binlog): нет данных.");
        }

        JsonNode containers = host.path("containers");
        if (containers.isObject()) {
            List<String> stopped = names(containers.path("stopped"));
            List<String> unhealthy = names(containers.path("unhealthy"));
            lines.add("Контейнеры: работают " + containers.path("running").asInt() + " из "
                    + containers.path("total").asInt()
                    + (stopped.isEmpty() ? "" : "; остановлены: " + String.join(", ", stopped))
                    + (unhealthy.isEmpty() ? "" : "; с ошибкой проверки: " + String.join(", ", unhealthy)) + ".");
            if (!stopped.isEmpty() || !unhealthy.isEmpty()) {
                warnings.add("есть остановленные или неисправные контейнеры");
            }
        } else {
            lines.add("Контейнеры: нет данных.");
        }

        JsonNode sizes = host.path("sizes");
        if (sizes.isObject()) {
            List<String> parts = new ArrayList<>();
            addSize(parts, "точки отката", sizes.path("deployBackups"));
            addSize(parts, "журналы контейнеров", sizes.path("containerLogs"));
            addSize(parts, "журнал systemd", sizes.path("systemJournal"));
            JsonNode data = sizes.path("data");
            if (data.isObject()) {
                StreamSupport.stream(((Iterable<Map.Entry<String, JsonNode>>) data::fields).spliterator(), false)
                        .filter(entry -> entry.getValue().isNumber())
                        .sorted(Comparator.comparingLong((Map.Entry<String, JsonNode> entry) -> entry.getValue().asLong())
                                .reversed())
                        .limit(3)
                        .forEach(entry -> parts.add("data/" + entry.getKey() + " " + gib(entry.getValue().asLong()) + " ГиБ"));
            }
            if (!parts.isEmpty()) {
                lines.add("Крупные каталоги: " + String.join(", ", parts) + ".");
            }
        }
    }

    private void cleanupLine(String title, JsonNode cleanup, List<String> lines) {
        if (!cleanup.isObject() || cleanup.path("at").asText("").isBlank()) {
            lines.add(title + ": не было.");
            return;
        }
        String at;
        try {
            at = DAY_TIME.format(Instant.parse(cleanup.path("at").asText()).atZone(ZONE));
        } catch (RuntimeException invalid) {
            at = "время неизвестно";
        }
        StringBuilder line = new StringBuilder(title).append(": ").append(at);
        if (cleanup.path("beforePercent").isNumber() && cleanup.path("afterPercent").isNumber()) {
            line.append(", ").append(cleanup.path("beforePercent").asInt()).append("% → ")
                    .append(cleanup.path("afterPercent").asInt()).append('%');
        }
        if (cleanup.path("freedBytes").isNumber()) {
            line.append(", освобождено ").append(gib(cleanup.path("freedBytes").asLong())).append(" ГиБ");
        }
        List<String> failed = names(cleanup.path("failedActions"));
        if (!failed.isEmpty()) {
            line.append(", с ошибками: ").append(String.join(", ", failed));
        }
        lines.add(line.append('.').toString());
    }

    private void backupLines(Instant now, List<String> lines, List<String> warnings) {
        MeterRegistry registry = meters.getIfAvailable();
        Collection<Gauge> verified = registry == null ? List.of() : registry.find(VERIFIED_METRIC).gauges();
        if (verified.isEmpty()) {
            lines.add("Бэкапы: резервное копирование выключено.");
            warnings.add("резервное копирование выключено");
            return;
        }
        List<Gauge> ordered = new ArrayList<>(verified);
        ordered.sort(Comparator.comparing((Gauge gauge) -> !"mysql".equals(gauge.getId().getTag("database")))
                .thenComparing(gauge -> String.valueOf(gauge.getId().getTag("database"))));
        for (Gauge gauge : ordered) {
            String database = String.valueOf(gauge.getId().getTag("database"));
            String name = "keycloak".equalsIgnoreCase(database) ? "Keycloak" : "mysql".equalsIgnoreCase(database) ? "MySQL" : database;
            double epoch = gauge.value();
            if (!(epoch > 0) || Double.isInfinite(epoch)) {
                lines.add("Бэкап " + name + ": подтверждённых копий нет или сведения не читаются.");
                warnings.add("нет подтверждённого бэкапа " + name);
                continue;
            }
            Instant verifiedAt = Instant.ofEpochMilli(Math.round(epoch * 1000));
            Duration age = Duration.between(verifiedAt, now);
            Gauge email = registry.find(EMAIL_METRIC).tag("database", database).gauge();
            double emailStatus = email == null ? Double.NaN : email.value();
            lines.add("Бэкап " + name + ": " + DAY_TIME.format(verifiedAt.atZone(ZONE)) + " ("
                    + Math.max(0, age.toHours()) + " ч назад), " + emailText(emailStatus) + ".");
            if (age.compareTo(BACKUP_MAX_AGE) > 0) {
                warnings.add("бэкап " + name + " старше " + BACKUP_MAX_AGE.toHours() + " ч");
            }
            if (emailStatus == 0.0) {
                warnings.add("письмо с бэкапом " + name + " не отправлено");
            }
        }
        Gauge drill = registry.find(DRILL_METRIC).gauge();
        double drillDays = drill == null ? Double.NaN : drill.value();
        if (Double.isNaN(drillDays) || drillDays < 0) {
            lines.add("Учебное восстановление: дата не указана.");
        } else {
            long days = Math.round(drillDays);
            lines.add("Учебное восстановление: " + DATE.format(now.atZone(ZONE).toLocalDate().minusDays(days))
                    + " (" + days + " дн. назад).");
            if (days >= DRILL_WARNING_DAYS) {
                warnings.add("учебное восстановление было " + days + " дн. назад");
            }
        }
    }

    private static String emailText(double status) {
        if (status == 1.0) {
            return "письмо отправлено";
        }
        if (status == 0.0) {
            return "письмо не отправлено";
        }
        if (status == -1.0) {
            return "письмо выключено";
        }
        return "о письме нет данных";
    }

    private static void addSize(List<String> parts, String title, JsonNode bytes) {
        if (bytes.isNumber()) {
            parts.add(title + " " + gib(bytes.asLong()) + " ГиБ");
        }
    }

    private static List<String> names(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }

    private static String text(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : "нет данных";
    }

    private static String formatDays(long seconds) {
        if (seconds <= 0) {
            return "без ограничения";
        }
        long days = seconds / 86400;
        return days > 0 && seconds % 86400 == 0 ? days + " дн" : String.format(Locale.ROOT, "%.1f ч", seconds / 3600.0d);
    }

    static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1024.0d / 1024.0d / 1024.0d);
    }
}
