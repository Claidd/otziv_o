package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.hunt.otziv.u_users.api.WorkerRiskReviewerDirectory.Reviewer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerRiskReviewerDirectoryServiceTest {
    private final UserService users = mock(UserService.class);
    private final WorkerRiskReviewerDirectoryService directory = new WorkerRiskReviewerDirectoryService(users);

    @Test
    void exportsImmutableActiveRecipientSnapshotsInManagerOwnerAdminOrderWithoutSelfOrDuplicates() {
        User worker = user(1L, true, 101L);
        User manager = user(2L, true, 102L);
        User inactive = user(3L, false, 103L);
        User owner = user(4L, true, null);
        User admin = user(5L, true, 105L);
        worker.setManagers(new LinkedHashSet<>(Arrays.asList(
                Manager.builder().user(manager).build(), Manager.builder().user(inactive).build(), null)));
        when(users.findByIdToUserInfo(1L)).thenReturn(worker);
        when(users.getAllOwners("ROLE_OWNER")).thenReturn(Arrays.asList(manager, owner, worker, null));
        when(users.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(inactive, owner, admin));

        List<Reviewer> result = directory.reviewersForWorker(1L);

        assertThat(result).containsExactly(new Reviewer(2L, 102L), new Reviewer(4L, null), new Reviewer(5L, 105L));
        manager.setTelegramChatId(999L);
        assertThat(result.getFirst().telegramChatId()).isEqualTo(102L);
        assertThatThrownBy(() -> result.add(new Reviewer(6L, 106L))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsEmptyRoleLookupsAndRejectsMismatchedWorkerWithoutLookingUpReviewers() {
        User worker = user(1L, true, null);
        when(users.findByIdToUserInfo(1L)).thenReturn(worker);
        assertThat(directory.reviewersForWorker(1L)).isEmpty();

        UserService other = mock(UserService.class);
        when(other.findByIdToUserInfo(1L)).thenReturn(user(99L, true, null));
        assertThat(new WorkerRiskReviewerDirectoryService(other).reviewersForWorker(1L)).isEmpty();
        verify(other, never()).getAllOwners(any());
    }

    private User user(Long id, boolean active, Long chatId) {
        User user = new User();
        user.setId(id);
        user.setActive(active);
        user.setTelegramChatId(chatId);
        return user;
    }
}
