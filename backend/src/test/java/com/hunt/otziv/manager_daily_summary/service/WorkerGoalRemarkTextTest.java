package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerGoalRemarkTextTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    @Test
    void fiveTextsOfOneOrderAreOneTaskWithTheirArrivalTime() {
        var goal = new ManagerWorkerDailyProgressService.MissedWorkerGoal(13L, "Виктория Ц.", DAY, 57, 62, 0,
                List.of(new ManagerWorkerDayPlanReader.UnfinishedTask("NEW", 28264L, 5, DAY.atTime(21, 53, 35))), null);

        String question = WorkerGoalRemarkText.question(goal);

        assertThat(question)
                .startsWith("Специалист Виктория Ц. за 01.11.2026 ")
                .contains("не выполнена 1 задача — заказ №28264: 5 текстов к написанию, в работе с 21:53")
                .contains("закрыть эту задачу")
                .doesNotContain("57 из 62", "62 задач");
    }

    @Test
    void severalTasksAndAnUnknownRemainderAreNamedSeparately() {
        var goal = new ManagerWorkerDailyProgressService.MissedWorkerGoal(6L, "Альфия Л.", DAY, 22, 30, 0,
                List.of(new ManagerWorkerDayPlanReader.UnfinishedTask("PUBLISH", 27516L, 1, DAY.atTime(10, 0)),
                        new ManagerWorkerDayPlanReader.UnfinishedTask("RECOVERY", 27147L, 2, DAY.minusDays(1).atTime(10, 0))),
                null);

        assertThat(WorkerGoalRemarkText.shortfall(goal))
                .isEqualTo("не выполнены 2 задачи — заказ №27516: 1 публикация, в работе с 10:00; "
                        + "заказ №27147: 2 восстановления отзыва, в работе с 31.10 10:00; "
                        + "ещё 5 ед. работы без уточнённого состава");
        assertThat(WorkerGoalRemarkText.question(goal)).contains("закрыть эти задачи");
    }

    @Test
    void unknownPlanKeepsTheStoredUnitCounts() {
        var goal = new ManagerWorkerDailyProgressService.MissedWorkerGoal(50L, "Мария", DAY, 4, 8);

        assertThat(WorkerGoalRemarkText.question(goal))
                .contains("выполнено 4 из 8 ед. работы, осталось 4")
                .startsWith("Специалист Мария за 01.11.2026");
    }

    @Test
    void russianPluralsFollowTheNumber() {
        assertThat(WorkerGoalRemarkText.units("NEW", 1)).isEqualTo("1 текст к написанию");
        assertThat(WorkerGoalRemarkText.units("NEW", 3)).isEqualTo("3 текста к написанию");
        assertThat(WorkerGoalRemarkText.units("NEW", 11)).isEqualTo("11 текстов к написанию");
        assertThat(WorkerGoalRemarkText.units("PUBLISH", 21)).isEqualTo("21 публикация");
        assertThat(WorkerGoalRemarkText.units("BAD", 2)).isEqualTo("2 задачи по плохим отзывам");
    }
}
