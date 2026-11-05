package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PERF-7: the periodic control pass runs one transaction per manager and talks to Telegram only after commit. */
class ManagerControlReminderWorkflowTest {
    final ManagerControlDailySnapshotWorkflow dailySnapshot = mock(ManagerControlDailySnapshotWorkflow.class);
    final ManagerControlDayLifecycle dayLifecycle = mock(ManagerControlDayLifecycle.class);
    final ManagerControlCardLifecycle cardLifecycle = mock(ManagerControlCardLifecycle.class);
    final ManagerRepository managers = mock(ManagerRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final PersonalReminderService reminders = mock(PersonalReminderService.class);
    final TelegramService telegram = mock(TelegramService.class);
    final ManagerDailyControlRepository controls = mock(ManagerDailyControlRepository.class);
    final ManagerDailyControlItemRepository items = mock(ManagerDailyControlItemRepository.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final ManagerControlReminderWorkflow workflow = new ManagerControlReminderWorkflow(dailySnapshot, dayLifecycle,
            mock(ManagerControlProblemExamples.class), mock(ManagerControlAccessPolicy.class), cardLifecycle,
            mock(ManagerControlClientMessageText.class), managers, users, reminders, telegram, controls, items, transactions);

    private Manager manager(long id) {
        Manager manager = new Manager();
        manager.setId(id);
        when(managers.findByIdWithUser(id)).thenReturn(Optional.of(manager));
        when(managers.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        return manager;
    }

    @Test
    void failingManagerDoesNotRollBackOrBlockTheOthers() {
        Manager first = manager(1L), second = manager(2L);
        when(managers.findAllWithUserAndImage()).thenReturn(List.of(first, second));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(dailySnapshot.managerControl(eq(first), any(), isNull(), eq(true), eq(false)))
                .thenThrow(new IllegalStateException("broken board"));

        workflow.runTestModeNotifications();

        verify(dailySnapshot).managerControl(eq(second), any(), isNull(), eq(true), eq(false));
        // One read transaction for ids plus one per manager: the failing one rolls back alone.
        verify(transactions, times(3)).getTransaction(any());
        verify(transactions, times(1)).rollback(any());
        verify(transactions, times(2)).commit(any());
        verifyNoInteractions(telegram);
    }

    @Test
    void ownerTelegramIsSentOnlyAfterTheManagerTransactionCommits() {
        assumeTrue(!LocalTime.now().isBefore(ManagerControlDayLifecycle.MORNING_STAGE_START.plusMinutes(1)),
                "overdue notifications start at 05:00");
        Manager only = manager(1L);
        when(managers.findAllWithUserAndImage()).thenReturn(List.of(only));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ManagerDailyControl yesterday = new ManagerDailyControl();
        yesterday.setId(77L);
        yesterday.setManager(only);
        yesterday.setControlDate(LocalDate.now().minusDays(1));
        when(controls.findByControlDateAndManager(LocalDate.now().minusDays(1), only)).thenReturn(Optional.of(yesterday));
        User owner = new User();
        owner.setId(5L);
        owner.setTelegramChatId(500L);
        when(users.findAllOwners("ROLE_OWNER")).thenReturn(List.of(owner));
        when(users.findAllOwners("ROLE_ADMIN")).thenReturn(List.of(owner));

        workflow.runTestModeNotifications();

        InOrder order = inOrder(reminders, controls, transactions, telegram);
        order.verify(reminders).createSystemReminderDueNow(eq(owner), anyString(), anyString(), anyString(), eq(77L), isNull());
        order.verify(controls).save(yesterday);
        order.verify(transactions).commit(any());
        order.verify(telegram).sendMessage(eq(500L), startsWith("Просрочен конец дня"));
        verify(transactions, times(2)).commit(any());
        assertThat(yesterday.getEveningNotificationSentAt()).isNotNull();
        verify(telegram, times(1)).sendMessage(anyLong(), anyString());
    }
}
