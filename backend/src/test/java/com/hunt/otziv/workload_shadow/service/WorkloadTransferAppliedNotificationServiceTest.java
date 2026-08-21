package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.AppliedNotificationProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferAppliedNotificationServiceTest {

    @Mock private WorkloadTransferExecutionRepository repository;
    @Mock private UserService userService;
    @Mock private TelegramService telegramService;

    private WorkloadTransferAppliedNotificationService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferAppliedNotificationService(
                repository,
                userService,
                telegramService
        );
    }

    @Test
    void sendsAppliedTransferOnlyToOwnersAndAdminsPersonalTelegramChats() {
        AppliedNotificationProjection execution = execution();
        when(repository.findAppliedNotification(81L)).thenReturn(Optional.of(execution));
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(
                user(1L, 1001L),
                user(2L, null),
                user(3L, -100L)
        ));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(
                user(4L, 2002L),
                user(5L, 1001L)
        ));
        when(telegramService.sendMessage(eq(1001L), anyString(), eq("HTML"))).thenReturn(true);
        when(telegramService.sendMessage(eq(2002L), anyString(), eq("HTML"))).thenReturn(true);

        service.notifyApplied(81L);

        ArgumentCaptor<String> ownerText = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(eq(1001L), ownerText.capture(), eq("HTML"));
        verify(telegramService).sendMessage(eq(2002L), anyString(), eq("HTML"));
        assertThat(ownerText.getValue())
                .contains("LIVE · Смена специалиста по нагрузке")
                .contains("Компания:</b> «Гипер&lt;Сервис&gt;» (#3004)")
                .contains("Специалист:</b> Максим Р. → Катя К.")
                .contains("Перенесено:</b> 1 заказ, 6 карточек отзывов")
                .contains("Заказы:</b> #101, #102")
                .contains("Workflow #41, execution #81, режим CANARY");
    }

    @Test
    void skippedWhenExecutionIsNotAppliedOrMissing() {
        when(repository.findAppliedNotification(82L)).thenReturn(Optional.empty());

        service.notifyApplied(82L);

        verifyNoInteractions(userService, telegramService);
    }

    private AppliedNotificationProjection execution() {
        AppliedNotificationProjection projection = mock(AppliedNotificationProjection.class);
        when(projection.getExecutionId()).thenReturn(81L);
        when(projection.getWorkflowId()).thenReturn(41L);
        when(projection.getMode()).thenReturn("CANARY");
        when(projection.getManagerName()).thenReturn("Алекс");
        when(projection.getSourceWorkerName()).thenReturn("Максим Р.");
        when(projection.getTargetWorkerName()).thenReturn("Катя К.");
        when(projection.getCompanyId()).thenReturn(3004L);
        when(projection.getCompanyTitle()).thenReturn("Гипер<Сервис>");
        when(projection.getOrderCount()).thenReturn(1);
        when(projection.getReviewCount()).thenReturn(6);
        when(projection.getBadTaskCount()).thenReturn(0);
        when(projection.getRecoveryTaskCount()).thenReturn(0);
        when(projection.getAppliedAt()).thenReturn(LocalDateTime.of(2026, 8, 20, 20, 29));
        when(projection.getRollbackDeadlineAt()).thenReturn(LocalDateTime.of(2026, 8, 20, 20, 59));
        when(projection.getOrderIds()).thenReturn("101, 102");
        return projection;
    }

    private User user(Long id, Long telegramChatId) {
        return User.builder()
                .id(id)
                .telegramChatId(telegramChatId)
                .active(true)
                .build();
    }
}