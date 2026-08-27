package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.dto.personal.UserData;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalServiceImplTelegramReportTest {

    private final PersonalServiceImpl service = new PersonalServiceImpl(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
    );

    @Test
    void displayResultUsesReadableTelegramBlocks() {
        Map<String, UserData> result = new LinkedHashMap<>();
        result.put("Вика_Ц.", manager("Вика_Ц."));
        result.put("Люба Р.", worker("Люба Р."));
        result.put("SMM <One>", marketolog("SMM <One>"));

        String report = service.displayResult(result);

        assertThat(report)
                .contains("<b>Итоги</b>")
                .contains("<b>Рейтинг менеджеров</b>")
                .contains("1. <b>Вика_Ц.</b>")
                .contains("Выручка: <b>119 050 руб.</b> | новые: <b>6</b>")
                .contains("Заказы: новые <b>32</b>, коррекция <b>12</b>")
                .contains("<b>Специалисты</b>")
                .contains("👷 <b>Люба Р.</b>")
                .contains("<b>СММ</b>")
                .contains("📣 <b>SMM &lt;One&gt;</b>")
                .doesNotContain("*")
                .doesNotContain("`")
                .doesNotContain("•");
    }

    @Test
    void displayResultToWorkerKeepsOnlyPersonalWorkerCard() {
        String report = service.displayResultToWorker(Map.of("Люба Р.", worker("Люба Р.")));

        assertThat(report)
                .startsWith("📊 <b>Личный отчёт</b>")
                .contains("👷 <b>Люба Р.</b>")
                .doesNotContain("ЗП:")
                .doesNotContain("14 542 руб.")
                .contains("Выгул: <b>1</b> | публикация: <b>148</b>")
                .contains("Плохие: <b>4</b> | восстановление: <b>2</b>")
                .contains("Прогресс дня: <b>19/34 (56%)</b>")
                .doesNotContain("<b>Итоги</b>")
                .doesNotContain("*")
                .doesNotContain("`");
    }

    @Test
    void displayResultToManagerHidesCompanyFinanceAndWorkerPay() {
        Map<String, UserData> result = new LinkedHashMap<>();
        result.put("Вика_Ц.", manager("Вика_Ц."));
        result.put("Люба Р.", worker("Люба Р."));
        result.put("SMM <One>", marketolog("SMM <One>"));

        String report = service.displayResultToManager(result);

        assertThat(report)
                .startsWith("📊 <b>Отчёт за месяц</b>")
                .contains("<b>Менеджер</b>")
                .contains("👤 <b>Вика_Ц.</b>")
                .contains("Начислено: <b>9 524 руб.</b>")
                .contains("<b>Специалисты</b>")
                .contains("👷 <b>Люба Р.</b>")
                .contains("Заказы: новые <b>1</b>, коррекция <b>0</b>")
                .contains("Выгул: <b>1</b> | публикация: <b>148</b>")
                .doesNotContain("<b>Итоги</b>")
                .doesNotContain("<b>Рейтинг менеджеров</b>")
                .doesNotContain("Выручка")
                .doesNotContain("Вознаграждения")
                .doesNotContain("85 756 руб.")
                .doesNotContain("119 050 руб.")
                .doesNotContain("14 542 руб.")
                .doesNotContain("<b>СММ</b>")
                .doesNotContain("SMM &lt;One&gt;");
    }

    private UserData manager(String fio) {
        return UserData.builder()
                .fio(fio)
                .role("ROLE_MANAGER")
                .salary(9_524L)
                .totalSum(119_050L)
                .zpTotal(85_756L)
                .newCompanies(6L)
                .newOrders(32L)
                .correctOrders(12L)
                .inVigul(188L)
                .inPublish(1_392L)
                .leadsNew(0L)
                .orderToCheck(1L)
                .orderInCheck(65L)
                .orderInPublished(10L)
                .orderInWaitingPay1(72L)
                .orderInWaitingPay2(0L)
                .orderNoPay(8L)
                .build();
    }

    private UserData worker(String fio) {
        return UserData.builder()
                .fio(fio)
                .role("ROLE_WORKER")
                .salary(14_542L)
                .zpTotal(85_756L)
                .newOrders(1L)
                .correctOrders(0L)
                .inVigul(1L)
                .inPublish(148L)
                .badTasks(4L)
                .recoveryTasks(2L)
                .dailyProgress(dailyProgress())
                .build();
    }

    private DailyWorkProgressResponse dailyProgress() {
        return new DailyWorkProgressResponse(
                true, "WORKER", java.time.LocalDate.of(2026, 7, 16),
                19, 15, 34, 56, false,
                null, null, 0, 0, 0,
                null, null, 0, 0, 0, 0, 0,
                34, 10, 3, 3, 2, 1, 0,
                0, 0, 0, 0, 0, 0, 0,
                false, null, null, "DAY", 0, 0, 0, false
        );
    }

    private UserData marketolog(String fio) {
        return UserData.builder()
                .fio(fio)
                .role("ROLE_MARKETOLOG")
                .salary(21_000L)
                .zpTotal(85_756L)
                .leadsNew(4L)
                .newOrders(0L)
                .correctOrders(0L)
                .inVigul(0L)
                .inPublish(0L)
                .build();
    }
}
