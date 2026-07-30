package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.RollbackContextProjection;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferRollbackServiceTest {

    @Mock private WorkloadTransferExecutionRepository repository;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private WorkloadTransferRollbackService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferRollbackService(
                repository,
                shadowSettingsService
        );
    }

    @Test
    void requiresTheExactOwnerConfirmationBeforeClaimingAnything() {
        assertThatThrownBy(() -> service.rollback(71L, "да"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(WorkloadTransferRollbackService.CONFIRMATION);
        verifyNoInteractions(repository, shadowSettingsService);
    }

    @Test
    void rejectsRollbackWhenWorkHasStartedAtTheRecipient() {
        clock();
        RollbackContextProjection context = context();
        when(repository.claimRollback(eq(71L), any())).thenReturn(1);
        when(repository.findRollbackContext(71L))
                .thenReturn(Optional.of(context));
        when(repository.countRollbackUnsafeEntities(71L, 22L)).thenReturn(1L);

        assertThatThrownBy(() -> service.rollback(
                71L,
                WorkloadTransferRollbackService.CONFIRMATION
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("после передачи уже начата работа");
        verify(repository).countRollbackUnsafeEntities(71L, 22L);
    }

    @Test
    void restoresEveryAuditedEntityAndOnlyTheLinkCreatedByTransfer() {
        clock();
        RollbackContextProjection context = context();
        when(repository.claimRollback(eq(71L), any())).thenReturn(1);
        when(repository.findRollbackContext(71L))
                .thenReturn(Optional.of(context));
        when(repository.countRollbackUnsafeEntities(71L, 22L)).thenReturn(0L);
        when(repository.findAuditEntityIds(71L, "ORDER")).thenReturn(List.of(101L));
        when(repository.findAuditEntityIds(71L, "REVIEW"))
                .thenReturn(List.of(201L, 202L));
        when(repository.findAuditEntityIds(71L, "BAD_TASK")).thenReturn(List.of(301L));
        when(repository.findAuditEntityIds(71L, "RECOVERY_TASK"))
                .thenReturn(List.of(401L));
        when(repository.findAuditEntityIds(71L, "COMPANY_LINK"))
                .thenReturn(List.of(51L));
        when(repository.rollbackOrders(
                71L,
                List.of(101L),
                11L,
                22L,
                51L
        )).thenReturn(1);
        when(repository.rollbackReviews(
                71L,
                List.of(201L, 202L),
                11L,
                22L
        )).thenReturn(2);
        when(repository.rollbackBadTasks(
                71L,
                List.of(301L),
                11L,
                22L
        )).thenReturn(1);
        when(repository.rollbackRecoveryTasks(
                eq(71L),
                eq(List.of(401L)),
                eq(11L),
                eq(22L),
                any()
        )).thenReturn(1);
        // MySQL reports execution + workflow for one logical rollback.
        when(repository.markRolledBack(eq(71L), any())).thenReturn(2);

        var response = service.rollback(
                71L,
                "  " + WorkloadTransferRollbackService.CONFIRMATION + "  "
        );

        assertThat(response.id()).isEqualTo(71L);
        assertThat(response.status()).isEqualTo("ROLLED_BACK");
        verify(repository).ensureSourceCompanyLink(51L, 11L);
        verify(repository).clearCredentialPreparations(List.of(201L, 202L));
        verify(repository).removeTargetCompanyLinkIfUnused(51L, 22L);
        verify(repository).markRolledBack(eq(71L), any());
    }

    private RollbackContextProjection context() {
        RollbackContextProjection context = mock(RollbackContextProjection.class);
        when(context.getSourceWorkerId()).thenReturn(11L);
        when(context.getTargetWorkerId()).thenReturn(22L);
        when(context.getCompanyId()).thenReturn(51L);
        return context;
    }

    private void clock() {
        when(shadowSettingsService.current()).thenReturn(null);
        when(shadowSettingsService.zone(null)).thenReturn(ZoneId.of("Asia/Irkutsk"));
    }
}
