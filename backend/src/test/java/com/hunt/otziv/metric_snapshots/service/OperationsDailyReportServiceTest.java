package com.hunt.otziv.metric_snapshots.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsDailyReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T15:00:00Z"); // 23:00 in Irkutsk
    private static final long GIB = 1024L * 1024L * 1024L;

    @TempDir
    Path folder;

    private final DiskSpaceUsageProvider usage = mock(DiskSpaceUsageProvider.class);
    private final DiskSpaceAlertService delivery = mock(DiskSpaceAlertService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private OperationsDailyReportService service() {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        when(usage.current()).thenReturn(new DiskSpaceUsageProvider.DiskUsage(50 * GIB, 36 * GIB, 14 * GIB, 73));
        OperationsDailyReportService service = new OperationsDailyReportService(usage, delivery, provider,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        service.setStatusFile(folder.resolve("disk.json").toString());
        return service;
    }

    /** The gauges the backup module publishes: latest verified epoch and e-mail status per database. */
    private void backup(String database, Instant verifiedAt, double emailStatus) {
        double epoch = verifiedAt == null ? 0.0 : verifiedAt.getEpochSecond();
        Gauge.builder(OperationsDailyReportService.VERIFIED_METRIC, () -> epoch).tag("database", database).register(registry);
        Gauge.builder(OperationsDailyReportService.EMAIL_METRIC, () -> emailStatus).tag("database", database).register(registry);
    }

    private void drillAgeDays(double days) {
        Gauge.builder(OperationsDailyReportService.DRILL_METRIC, () -> days).register(registry);
    }

    private void writeStatus(Instant generatedAt) throws Exception {
        Files.writeString(folder.resolve("disk.json"), """
                {"schema":"otziv-ops-disk-status-v1","generatedAt":"%s","usage":{"usedPercent":73},
                 "triggerPercent":75,"targetPercent":70,
                 "lastCleanup":{"at":"2026-11-22T10:00:09+00:00","beforePercent":82,"afterPercent":80,
                                "freedBytes":1073672192,"failedActions":[]},
                 "lastPostDeployCleanup":null,
                 "docker":{"images":{"size":"11.96GB","reclaimable":"1.41GB (11%%)"},
                           "volumes":{"size":"8.46GB"},"buildCache":{"size":"245.4MB"}},
                 "binlog":{"retentionSeconds":259200,"files":5,"bytes":4286578688},
                 "containers":{"total":14,"running":13,"stopped":["grafana"],"unhealthy":[]},
                 "sizes":{"deployBackups":371195904,"containerLogs":31457280,"systemJournal":null,
                          "data":{"whatsapp_auth":2899102924,"app-logs":218103808,"bots":2097152}},
                 "unavailable":[]}
                """.formatted(generatedAt.toString()));
    }

    @Test
    void reportsHostBackupsAndDrillWithWarnings() throws Exception {
        writeStatus(NOW.minus(Duration.ofMinutes(20)));
        backup("keycloak", Instant.parse("2026-11-21T23:02:20Z"), 0.0);
        backup("mysql", Instant.parse("2026-11-22T23:02:10Z"), 1.0);
        drillAgeDays(42);

        String report = service().buildReport();

        assertThat(report).startsWith("Сервер: сводка за 23.11.2026");
        assertThat(report).contains("Диск: занято 73% (36.0 из 50.0 ГиБ), свободно 14.0 ГиБ.");
        assertThat(report).contains("Автоочистка: с 75% до 70%.");
        assertThat(report).contains("Последняя автоочистка: 22.11 18:00, 82% → 80%, освобождено 1.0 ГиБ.");
        assertThat(report).contains("Очистка после выпуска: не было.");
        assertThat(report).contains("Docker: образы 11.96GB (можно освободить 1.41GB (11%)), тома 8.46GB, кэш сборки 245.4MB.");
        assertThat(report).contains("Журналы MySQL (binlog): 5 файл(ов), 4.0 ГиБ, срок хранения 3 дн.\n");
        assertThat(report).contains("Контейнеры: работают 13 из 14; остановлены: grafana.");
        assertThat(report).contains("Крупные каталоги: точки отката 0.3 ГиБ, журналы контейнеров 0.0 ГиБ, data/whatsapp_auth 2.7 ГиБ");
        assertThat(report.indexOf("Бэкап MySQL")).isLessThan(report.indexOf("Бэкап Keycloak"));
        assertThat(report).contains("Бэкап MySQL: 23.11 07:02 (15 ч назад), письмо отправлено.");
        assertThat(report).contains("Бэкап Keycloak: 22.11 07:02 (39 ч назад), письмо не отправлено.");
        assertThat(report).contains("Учебное восстановление: 12.10.2026 (42 дн. назад).");
        assertThat(report).contains("Внимание: есть остановленные или неисправные контейнеры; бэкап Keycloak старше 26 ч; "
                + "письмо с бэкапом Keycloak не отправлено.");
        assertThat(report).doesNotContain("backup/").doesNotContain("bucket");
    }

    @Test
    void staleOrMissingHostStatusIsNoDataNeverZero() throws Exception {
        backup("mysql", Instant.parse("2026-11-22T23:02:10Z"), 1.0);
        drillAgeDays(Double.NaN);

        String missing = service().buildReport();
        assertThat(missing).contains("Данные хоста: нет данных (файл состояния автоочистки отсутствует или старше 2 ч).");
        assertThat(missing).doesNotContain("Docker:").contains("Учебное восстановление: дата не указана.")
                .contains("Внимание: нет свежих данных автоочистки.");

        writeStatus(NOW.minus(Duration.ofHours(3)));
        assertThat(service().buildReport()).contains("Данные хоста: нет данных");

        Files.writeString(folder.resolve("disk.json"), "{\"schema\":\"something-else\"}");
        assertThat(service().buildReport()).contains("Данные хоста: нет данных");
    }

    @Test
    void healthyServerSaysAllIsWell() throws Exception {
        writeStatus(NOW.minus(Duration.ofMinutes(5)));
        Files.writeString(folder.resolve("disk.json"), Files.readString(folder.resolve("disk.json"))
                .replace("\"stopped\":[\"grafana\"]", "\"stopped\":[]").replace("\"running\":13", "\"running\":14"));
        backup("mysql", Instant.parse("2026-11-22T23:02:10Z"), 1.0);
        backup("keycloak", Instant.parse("2026-11-22T23:02:20Z"), -1.0);
        drillAgeDays(22);

        String report = service().buildReport();

        assertThat(report).contains("Контейнеры: работают 14 из 14.").contains("письмо выключено")
                .endsWith("Итог: всё в порядке.");
    }

    @Test
    void missingOrUnreadableBackupsAreWarnings() throws Exception {
        writeStatus(NOW.minus(Duration.ofMinutes(5)));
        assertThat(service().buildReport())
                .contains("Бэкапы: резервное копирование выключено.")
                .contains("; резервное копирование выключено.");

        backup("mysql", null, Double.NaN);
        backup("keycloak", Instant.parse("2026-11-22T23:02:20Z"), Double.NaN);
        drillAgeDays(114);
        assertThat(service().buildReport())
                .contains("Бэкап MySQL: подтверждённых копий нет или сведения не читаются.")
                .contains("Бэкап Keycloak: 23.11 07:02 (15 ч назад), о письме нет данных.")
                .contains("нет подтверждённого бэкапа MySQL")
                .contains("учебное восстановление было 114 дн. назад");
    }

    @Test
    void sendGoesToOwnersAndAdministratorsByTelegram() throws Exception {
        writeStatus(NOW.minus(Duration.ofMinutes(5)));
        when(delivery.sendTelegramToOwnersAndAdmins(anyString())).thenReturn(2);

        assertThat(service().send()).isEqualTo(2);
        verify(delivery).sendTelegramToOwnersAndAdmins(org.mockito.ArgumentMatchers.startsWith("Сервер: сводка за"));
    }

    @Test
    void schedulerSendsOnceUnderTheLeaseAndOnlyWhenEnabled() {
        OperationsDailyReportService report = mock(OperationsDailyReportService.class);
        SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
        OperationsDailyReportScheduler scheduler = new OperationsDailyReportScheduler(report, leases);

        scheduler.sendDailyReport();
        verify(leases, never()).tryAcquire(anyString(), org.mockito.ArgumentMatchers.any());
        verify(report, never()).send();

        scheduler.setEnabled(true);
        when(leases.tryAcquire(OperationsDailyReportScheduler.LEASE, OperationsDailyReportScheduler.LEASE_DURATION))
                .thenReturn(Optional.empty());
        scheduler.sendDailyReport();
        verify(report, never()).send();

        when(leases.tryAcquire(OperationsDailyReportScheduler.LEASE, OperationsDailyReportScheduler.LEASE_DURATION))
                .thenReturn(Optional.of(new SchedulerLeaseService.Lease(OperationsDailyReportScheduler.LEASE, "token", 1)));
        when(report.send()).thenThrow(new IllegalStateException("telegram down"));
        scheduler.sendDailyReport();
        verify(report).send();
        verify(leases, never()).release(org.mockito.ArgumentMatchers.any());
    }
}
