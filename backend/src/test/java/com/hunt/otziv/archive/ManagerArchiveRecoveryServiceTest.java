package com.hunt.otziv.archive;

import com.hunt.otziv.archive.dto.*;
import com.hunt.otziv.archive.repository.ManagerArchiveRepository;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.archive.service.OrderArchiveRestoreService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerArchiveRecoveryServiceTest {
    ManagerArchiveRepository repository = mock(ManagerArchiveRepository.class);
    ReviewRecoveryTaskService recovery = mock(ReviewRecoveryTaskService.class);
    UserService users = mock(UserService.class);
    ManagerArchiveService service = new ManagerArchiveService(repository, mock(OrderArchiveRestoreService.class),
            recovery, new ManagerPermissionService(), users, mock(ManagerService.class));
    TestingAuthenticationToken admin = new TestingAuthenticationToken("admin", "unused", "ROLE_ADMIN");
    ArchiveAccessScope scope = ArchiveAccessScope.all();
    ArchiveReviewRecoverySource source = mock(ArchiveReviewRecoverySource.class);
    User actor = new User();

    @BeforeEach
    void setup() {
        ManagerArchiveOrderListItem order = mock(ManagerArchiveOrderListItem.class);
        when(order.source()).thenReturn("archive");
        when(order.companyId()).thenReturn(20L);
        when(repository.findOrder(scope, 10L)).thenReturn(Optional.of(order));
        when(repository.findReviewRecoverySource(10L, 11L)).thenReturn(Optional.of(source));
        when(users.findByUserName("admin")).thenReturn(Optional.of(actor));
    }

    @Test
    void creationUsesOnlyCurrentCompanyWorkerInsteadOfHistoricalAssignment() {
        when(repository.findRecoveryWorkers(scope, 20L)).thenReturn(List.of(
                new ArchiveRecoveryWorkerOption(72L, "Новый специалист", true),
                new ArchiveRecoveryWorkerOption(73L, "Другой специалист", false)));
        service.createReviewRecoveryTask(10L, 11L, null, admin, admin);
        verify(recovery).createArchiveTask(source, actor, 72L);
    }

    @Test
    void ambiguousCompanyAssignmentRequiresChoiceAndCreatesNothing() {
        when(repository.findRecoveryWorkers(scope, 20L)).thenReturn(List.of(
                new ArchiveRecoveryWorkerOption(72L, "Первый", true), new ArchiveRecoveryWorkerOption(73L, "Второй", true)));
        assertThatThrownBy(() -> service.createReviewRecoveryTask(10L, 11L, null, admin, admin))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Выберите действующего специалиста");
        verifyNoInteractions(recovery);
    }

    @Test
    void noCompanyWorkerRequiresExplicitChoiceEvenWhenOnlyOneWorkerIsAvailable() {
        when(repository.findRecoveryWorkers(scope, 20L)).thenReturn(List.of(new ArchiveRecoveryWorkerOption(73L, "Другой", false)));
        assertThatThrownBy(() -> service.createReviewRecoveryTask(10L, 11L, null, admin, admin))
                .isInstanceOf(ResponseStatusException.class);
        service.createReviewRecoveryTask(10L, 11L, 73L, admin, admin);
        verify(recovery).createArchiveTask(source, actor, 73L);
    }

    @Test
    void formerOrForeignWorkerCannotBeSelectedForCreationOrTransfer() {
        when(repository.findRecoveryWorkers(scope, 20L)).thenReturn(List.of(new ArchiveRecoveryWorkerOption(72L, "Новый", true)));
        assertThatThrownBy(() -> service.createReviewRecoveryTask(10L, 11L, 60L, admin, admin))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("недоступен");
        assertThatThrownBy(() -> service.reassignReviewRecoveryTasks(10L, 60L, null, admin, admin))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("недоступен");
        verifyNoInteractions(recovery);
    }

    @Test
    void transferCarriesArchiveOrderTaskAndExplicitActorWithoutRestoringOrder() {
        when(repository.findRecoveryWorkers(scope, 20L)).thenReturn(List.of(new ArchiveRecoveryWorkerOption(72L, "Новый", true)));
        service.reassignReviewRecoveryTasks(10L, 72L, 1238L, admin, admin);
        verify(recovery).reassignArchiveTasks(10L, 1238L, 72L, admin);
    }

    @Test
    void inaccessibleArchiveOrderCannotBeTransferred() {
        when(repository.findOrder(scope, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reassignReviewRecoveryTasks(10L, 72L, null, admin, admin))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(recovery);
    }
}
