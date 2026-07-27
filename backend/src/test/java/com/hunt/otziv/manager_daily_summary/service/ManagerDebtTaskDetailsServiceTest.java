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

    @BeforeEach
    void setUp() {
        controlRepository = mock(ManagerDailyControlRepository.class);
        itemRepository = mock(ManagerDailyControlItemRepository.class);
        concreteItemRepository = mock(ManagerDailyControlConcreteItemRepository.class);
        service = new ManagerDebtTaskDetailsService(
                controlRepository,
                itemRepository,
                concreteItemRepository
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
