package com.hunt.otziv.notification_media.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.BlockedTask;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.Kind;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class AccountBlockerNoticeTextTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @Test
    void workerTextCapsTheListAndNamesTheButtonOfEachCard() {
        List<BlockedTask> tasks = LongStream.rangeClosed(1, 12)
                .mapToObj(id -> new BlockedTask(6L, Kind.BAD, id, null, null, id, false))
                .toList();

        String text = AccountBlockerNoticeText.worker(DAY, tasks);

        assertThat(text)
                .contains("• Задача по оценке · заказ №10 — нажмите «смена» в карточке задачи.")
                .doesNotContain("заказ №11")
                .contains("• …и ещё 2.")
                .contains("не будет засчитан на 100%");
    }

    @Test
    void recoveryWithoutLoginUsesTheGetAccountButtonAndArchiveTaskHasNoOrderNumber() {
        String text = AccountBlockerNoticeText.worker(DAY, List.of(
                new BlockedTask(12L, Kind.RECOVERY, 1102L, 4L, "Сад", null, true),
                new BlockedTask(12L, Kind.NAGUL, 204084L, null, null, null, false)));

        assertThat(text)
                .contains("• Восстановление · «Сад» — нажмите «Получить аккаунт» в карточке задачи.")
                .contains("• Выгул — нажмите «смена» в карточке задачи.");
    }

    @Test
    void managerTextSummarisesBySpecialistAndKind() {
        Map<String, List<BlockedTask>> byWorker = new LinkedHashMap<>();
        byWorker.put("Анна <А>", List.of(
                new BlockedTask(11L, Kind.PUBLISH, 1L, null, null, 1L, false),
                new BlockedTask(11L, Kind.PUBLISH, 2L, null, null, 2L, false)));

        String text = AccountBlockerNoticeText.manager(DAY, byWorker);

        assertThat(text)
                .contains("📅 Рабочий день: <b>06.11.2026</b>.")
                .contains("• Анна &lt;А&gt;: <b>2</b> (публикация ×2)")
                .contains("«Получить аккаунт» или «смена»");
    }
}
