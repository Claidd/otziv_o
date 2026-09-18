package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.business_audit.api.CredentialRevealEvidence;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerAccountCredentialGuardTest {
    private final CredentialRevealEvidence audit = mock(CredentialRevealEvidence.class);
    private final WorkerActivityEventRepository activity = mock(WorkerActivityEventRepository.class);
    private final WorkerAccountCredentialGuard guard = new WorkerAccountCredentialGuard(audit, activity);
    private final LocalDateTime blockedAt = LocalDateTime.of(2026, 9, 18, 13, 21, 54);

    @Test
    void eighteenMinuteOldCopiesRemainValidUntilTheAccountChanges() {
        WorkerActivityEvent assignment = new WorkerActivityEvent();
        assignment.setCreatedAt(blockedAt.minusMinutes(19));
        when(activity.findTopByEntityTypeAndEntityIdAndActionInAndIdNotAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                eq("review"), eq(197623L), any(), eq(0L), eq(blockedAt))).thenReturn(Optional.of(assignment));
        LocalDateTime copiedAt = blockedAt.minusMinutes(18);
        when(audit.hasBothCredentials(eq("maks"), eq("review"), eq(197623L), eq(871819L), any(), eq(blockedAt)))
                .thenAnswer(call -> copiedAt.isAfter(call.getArgument(4)));
        assertTrue(guard.hasBothCredentials("maks", "review", 197623L, 871819L, blockedAt));
        verify(audit).hasBothCredentials("maks", "review", 197623L, 871819L,
                assignment.getCreatedAt(), blockedAt);
    }

    @Test
    void copiesFromThePreviousAssignmentCannotUnlockTheSameBotAgain() {
        WorkerActivityEvent assignment = new WorkerActivityEvent();
        assignment.setCreatedAt(blockedAt.minusMinutes(1));
        when(activity.findTopByEntityTypeAndEntityIdAndActionInAndIdNotAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                eq("review"), eq(197623L), any(), eq(0L), eq(blockedAt))).thenReturn(Optional.of(assignment));
        when(audit.hasBothCredentials(eq("maks"), eq("review"), eq(197623L), eq(871819L), any(), eq(blockedAt)))
                .thenAnswer(call -> blockedAt.minusMinutes(18).isAfter(call.getArgument(4)));
        assertFalse(guard.hasBothCredentials("maks", "review", 197623L, 871819L, blockedAt));
    }

    @Test
    void anotherActorsCardOrAccountCannotUnlockBlock() {
        when(audit.hasBothCredentials(eq("maks"), eq("review"), eq(197623L),
                eq(871819L), any(), any())).thenReturn(true);
        assertTrue(guard.hasBothCredentials("maks", "review", 197623L, 871819L, blockedAt));
        assertFalse(guard.hasBothCredentials("other", "review", 197623L, 871819L, blockedAt));
        assertFalse(guard.hasBothCredentials("maks", "recovery_task", 197623L, 871819L, blockedAt));
        assertFalse(guard.hasBothCredentials("maks", "review", 197624L, 871819L, blockedAt));
        assertFalse(guard.hasBothCredentials("maks", "review", 197623L, 871820L, blockedAt));
    }

    @Test
    void rejectsWorkerBeforeMutationButPreservesManagerOverride() {
        var worker = new UsernamePasswordAuthenticationToken("maks", "unused", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        var error = assertThrows(ResponseStatusException.class,
                () -> guard.assertCanBlock(worker, "review", 197623L, 871819L));
        assertEquals(409, error.getStatusCode().value());
        var manager = new UsernamePasswordAuthenticationToken("manager", "unused", List.of(
                new SimpleGrantedAuthority("ROLE_WORKER"), new SimpleGrantedAuthority("ROLE_MANAGER")));
        clearInvocations(audit, activity);
        assertDoesNotThrow(() -> guard.assertCanBlock(manager, "review", 197623L, 871819L));
        verifyNoInteractions(audit, activity);
    }
}
