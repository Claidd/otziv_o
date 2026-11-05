package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerDebtTaskDetailsServiceTest {

    private ManagerDailyControlRepository controlRepository;
    private ManagerDailyControlItemRepository itemRepository;
    private ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private ManagerDebtTaskDetailsService service;
    private ManagerReportReviewTaskContextService taskContextService;
    private ManagerReportReviewSettingsService reviewSettings;

    @BeforeEach
    void setUp() {
        controlRepository = mock(ManagerDailyControlRepository.class);
        itemRepository = mock(ManagerDailyControlItemRepository.class);
        concreteItemRepository = mock(ManagerDailyControlConcreteItemRepository.class);
        taskContextService = mock(ManagerReportReviewTaskContextService.class);
        reviewSettings = mock(ManagerReportReviewSettingsService.class);
        service = new ManagerDebtTaskDetailsService(
                controlRepository,
                itemRepository,
                concreteItemRepository,
                taskContextService,
                reviewSettings
        );
    }

    @Test
    void returnsOnlyOpenActionDebtWithConcreteCards() {
        LocalDate date = LocalDate.of(2026, 7, 26);
        Manager manager = Manager.builder().id(2L).build();
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(73L);
        control.setControlDate(date);
        control.setManager(manager);

        ManagerDailyControlItem unanswered = item(
                591L,
                control,
                "UNANSWERED_CLIENT_MESSAGES",
                "Неотвеченные сообщения",
                2,
                ManagerDailyControlItemStatus.OPEN
        );
        ManagerDailyControlItem resolved = item(
                592L,
                control,
                "OPEN_RISKS",
                "Риски",
                1,
                ManagerDailyControlItemStatus.RESOLVED
        );
        ManagerDailyControlConcreteItem alpha = concrete(
                3500L,
                control,
                unanswered,
                "Компания Альфа",
                "48 мин. без ответа"
        );
        ManagerDailyControlConcreteItem beta = concrete(
                3501L,
                control,
                unanswered,
                "Компания Бета",
                "26 мин. без ответа"
        );
        alpha.setReason("Клиент написал 48 мин. без ответа. Последнее сообщение: Когда будет готово?");
        when(controlRepository.findByControlDate(date)).thenReturn(List.of(control));
        when(itemRepository.findByControlIn(List.of(control))).thenReturn(List.of(unanswered, resolved));
        when(concreteItemRepository.findByParentItemIn(List.of(unanswered)))
                .thenReturn(List.of(beta, alpha));

        var result = service.tasks(date, List.of(2L));

        assertThat(result).containsOnlyKeys(2L);
        assertThat(result.get(2L).categories()).singleElement().satisfies(category -> {
            assertThat(category.reasonCode()).isEqualTo("UNANSWERED_CLIENT_MESSAGES");
            assertThat(category.count()).isEqualTo(2);
            assertThat(category.items()).extracting(ManagerDebtTaskDetailsService.DebtItem::title)
                    .containsExactly("Компания Альфа", "Компания Бета");
            assertThat(category.items().getFirst().detail())
                    .contains("48 мин. без ответа", "Когда будет готово?");
        });
        assertThat(service.location("Вика Ц.", result.get(2L).categories().getFirst()))
                .contains("Контроль менеджеров", "Вика Ц.", "Неотвеченные сообщения");
        assertThat(service.action(result.get(2L).categories().getFirst()))
                .contains("открыть каждую", "содержательный ответ");
        assertThat(service.completionCriterion(result.get(2L).categories().getFirst()))
                .contains("стал 0");
    }

    @Test
    void auditLeavesOutMessagesAnsweredAfterTheSnapshotOrStillWithinTheGrace() {
        LocalDate date = LocalDate.of(2026, 9, 14);
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(90L);
        control.setControlDate(date);
        control.setManager(Manager.builder().id(3L).build());
        ManagerDailyControlItem unanswered = item(700L, control, "UNANSWERED_CLIENT_MESSAGES",
                "Неотвеченные сообщения", 2, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlConcreteItem fresh = concrete(7699L, control, unanswered, "Красотулька", "0 мин. без ответа");
        ManagerDailyControlConcreteItem old = concrete(7700L, control, unanswered, "Toyama motors", "16 мин. без ответа");
        when(controlRepository.findByControlDate(date)).thenReturn(List.of(control));
        when(itemRepository.findByControlIn(List.of(control))).thenReturn(List.of(unanswered));
        when(concreteItemRepository.findByParentItemIn(List.of(unanswered))).thenReturn(List.of(fresh, old));
        when(reviewSettings.unansweredGraceMinutes()).thenReturn(10);
        when(taskContextService.unansweredNotYetARemark(
                org.mockito.ArgumentMatchers.eq(List.of(fresh, old)),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq(10))).thenReturn(java.util.Set.of(7699L));

        var audit = service.auditTasks(date, 3L);

        assertThat(audit.auditExcludedUnanswered()).isEqualTo(1);
        assertThat(audit.categories()).singleElement().satisfies(category -> {
            assertThat(category.count()).isEqualTo(1);
            assertThat(category.items()).extracting(ManagerDebtTaskDetailsService.DebtItem::title)
                    .containsExactly("Toyama motors");
        });
        assertThat(service.auditExcludedUnanswered(date, 3L)).isEqualTo(1);
        // The owner's team summary keeps the whole open queue.
        assertThat(service.tasks(date, List.of(3L)).get(3L).categories().getFirst().items()).hasSize(2);
    }

    @Test
    void auditDropsTheCategoryWhenEveryOpenMessageIsNotARemarkYet() {
        LocalDate date = LocalDate.of(2026, 9, 15);
        ManagerDailyControl control = new ManagerDailyControl();
        control.setId(91L);
        control.setControlDate(date);
        control.setManager(Manager.builder().id(3L).build());
        ManagerDailyControlItem unanswered = item(701L, control, "UNANSWERED_CLIENT_MESSAGES",
                "Неотвеченные сообщения", 1, ManagerDailyControlItemStatus.OPEN);
        ManagerDailyControlConcreteItem fresh = concrete(7742L, control, unanswered, "22 Философа", "2 мин. без ответа");
        when(controlRepository.findByControlDate(date)).thenReturn(List.of(control));
        when(itemRepository.findByControlIn(List.of(control))).thenReturn(List.of(unanswered));
        when(concreteItemRepository.findByParentItemIn(List.of(unanswered))).thenReturn(List.of(fresh));
        when(reviewSettings.unansweredGraceMinutes()).thenReturn(10);
        when(taskContextService.unansweredNotYetARemark(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(java.util.Set.of(7742L));

        assertThat(service.auditTasks(date, 3L)).isNull();
        assertThat(service.auditExcludedUnanswered(date, 3L)).isEqualTo(1);
    }

    private ManagerDailyControlItem item(
            Long id,
            ManagerDailyControl control,
            String reasonCode,
            String label,
            long count,
            ManagerDailyControlItemStatus status
    ) {
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setId(id);
        item.setControl(control);
        item.setReasonCode(reasonCode);
        item.setLabel(label);
        item.setCount(count);
        item.setStatus(status);
        item.setGroup(ManagerDailyControlGroup.ACTION);
        item.setItemType(ManagerDailyControlItemType.PROBLEM);
        return item;
    }

    private ManagerDailyControlConcreteItem concrete(
            Long id,
            ManagerDailyControl control,
            ManagerDailyControlItem parent,
            String title,
            String status
    ) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(id);
        item.setControl(control);
        item.setParentItem(parent);
        item.setTitle(title);
        item.setStatusLabel(status);
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        return item;
    }
}
