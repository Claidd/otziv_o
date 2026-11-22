package com.hunt.otziv.manager_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Manager control and the autoresponder agree on what a task that waits on purpose is: the order card neither calls
 * it a failure nor offers «Починить» (the page offers it for «автоответчик не обработал», «нет активного» …).
 */
class ManagerControlAutomationWaitReasonTest {

    private final ManagerControlProblemExamples examples = mock(ManagerControlProblemExamples.class, CALLS_REAL_METHODS);

    @Test
    void reminderHeldBehindTheManagersOwnMessageIsAHealthyQueue() {
        OrderDTOList order = order("scheduled", "manual_contact_reserved",
                "Ручная отправка текущего цикла уже сохранена или подтверждена", LocalDateTime.now().plusMinutes(5), 0);

        assertThat(examples.hasHealthyActiveClientMessageQueue(order)).isTrue();
        assertThat(examples.orderControlReason(order))
                .startsWith("Очередь автоответчика исправна: менеджер уже написал клиенту")
                .contains("Следующий слот отправки")
                .doesNotContain("не обработал");
    }

    @Test
    void dailyLimitAndSendingWindowAreHealthyWaitsToo() {
        for (String code : new String[]{"daily_limit", "outside_messaging_window", "review_recovery_active", "rate_limited"}) {
            OrderDTOList order = order("scheduled", code, "Ожидание", LocalDateTime.now().plusHours(3), 0);
            assertThat(examples.hasHealthyActiveClientMessageQueue(order)).as(code).isTrue();
            assertThat(examples.orderControlReason(order)).as(code).startsWith("Очередь автоответчика исправна");
        }
    }

    @Test
    void aWaitAfterARealFailureIsNotHealthy() {
        OrderDTOList order = order("scheduled", "daily_limit", "Дневной лимит", LocalDateTime.now().plusHours(3), 2);
        assertThat(examples.hasHealthyActiveClientMessageQueue(order)).isFalse();
    }

    @Test
    void scenarioSwitchedOffNamesTheSwitchAndTheStepToDoByHand() {
        OrderDTOList order = order("scheduled", "automation_paused",
                "Не отправлено: в настройках автоответчика (Справочники → Автоответчик) выключен переключатель «Напоминать о проверке шаблонов».",
                LocalDateTime.now().plusHours(20), 0);

        assertThat(examples.orderControlReason(order))
                .isEqualTo("Почему в контроле: автоответчик не пишет клиенту по этому заказу — в настройках автоответчика "
                        + "(Справочники → Автоответчик) выключен переключатель «Напоминать о проверке шаблонов». "
                        + "Напомните клиенту проверить шаблоны отзывов и при необходимости отправьте ссылку сами. "
                        + "«Починить» здесь не поможет. Это общий переключатель для всех клиентов: его включают владелец или администратор.")
                // The words the page uses to offer «Починить».
                .doesNotContain("не обработал")
                .doesNotContain("нет активного");
    }

    @Test
    void aTaskPostponedBeforeTheSwitchWasNamedStillGetsAClearReason() {
        OrderDTOList order = order("scheduled", "automation_paused",
                "Сценарий приостановлен настройками автоответчика", LocalDateTime.now().plusHours(20), 0);

        assertThat(examples.orderControlReason(order))
                .contains("в настройках автоответчика (Справочники → Автоответчик) выключен этот сценарий или весь автоответчик.")
                .contains("Напомните клиенту проверить шаблоны")
                .doesNotContain("не обработал");
    }

    @Test
    void administratorDecisionSaysWhoStoppedItAndWhatToDo() {
        OrderDTOList order = order("disabled", "manual_disabled", "Отключено вручную из мониторинга", null, 0);

        assertThat(examples.orderControlReason(order))
                .contains("администратор закрыл задачу автоответчика по этому заказу в мониторинге")
                .contains("Напомните клиенту проверить шаблоны")
                .doesNotContain("не обработал");
    }

    @Test
    void anActualFailureStillOffersRepair() {
        OrderDTOList order = order("failed", "delivery_failed", "Шлюз не ответил", LocalDateTime.now().plusMinutes(10), 3);

        assertThat(examples.orderControlReason(order)).contains("автоответчик не обработал заказ — Шлюз не ответил");
    }

    private OrderDTOList order(String state, String code, String message, LocalDateTime next, int failures) {
        return OrderDTOList.builder()
                .id(25362L)
                .status("На проверке")
                .clientMessageStatus(new ClientMessageOrderStatusResponse(
                        state,
                        "Ожидает отправки",
                        "wait",
                        "REVIEW_CHECK_REMINDER",
                        code,
                        message,
                        null,
                        null,
                        next,
                        failures,
                        0
                ))
                .build();
    }
}
