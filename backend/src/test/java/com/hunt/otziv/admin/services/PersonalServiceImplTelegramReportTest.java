package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.presonal.UserData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalServiceImplTelegramReportTest {

    private final PersonalServiceImpl service = new PersonalServiceImpl(
            null, null, null, null, null, null, null, null, null, null, null, null, null
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
                .contains("ЗП: <b>14 542 руб.</b>")
                .contains("Выгул: <b>1</b> | публикация: <b>148</b>")
                .doesNotContain("<b>Итоги</b>")
                .doesNotContain("*")
                .doesNotContain("`");
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
                .build();
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
