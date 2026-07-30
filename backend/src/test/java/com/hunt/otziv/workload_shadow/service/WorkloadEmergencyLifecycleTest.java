package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.EmergencyCaseProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.EmergencyRecipientProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.EmergencyRollbackProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.PreparedProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.WorkflowCandidatePairProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyAssignmentTransactionService.ApplyResult;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyAssignmentTransactionService.EmergencyCase;
import com.hunt.otziv.workload_shadow.service.WorkloadEmergencyAssignmentTransactionService.Recipient;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadEmergencyLifecycleTest {

    private static final long SHADOW_CASE_ID = 101L;
    private static final long WORKFLOW_ID = 201L;
    private static final long ASSIGNMENT_ID = 301L;
    private static final long SOURCE_MANAGER_ID = 21L;
    private static final long SOURCE_WORKER_ID = 11L;
    private static final long TARGET_WORKER_ID = 13L;
    private static final long COMPANY_ID = 31L;
    private static final long REVIEW_ID = 41L;
    private static final long AUDIT_CHAT_ID = -500L;

    @Mock private WorkloadEmergencyAssignmentRepository repository;
    @Mock private WorkloadTransferWorkflowRepository workflowRepository;
    @Mock private WorkloadTransferExecutionRepository executionRepository;
    @Mock private WorkloadEmergencyAssignmentTransactionService transactionService;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;

    private WorkloadShadowSettingsResponse shadow;

    @BeforeEach
    void setUp() {
        shadow = mock(WorkloadShadowSettingsResponse.class);
    }

    @Test
    void staffingFallbackExcludesSourceAndPriorCandidatesThenUsesOneGoodWorker() {
        clock();
        WorkloadLiveSettingsResponse live = live();
        when(liveSettingsService.current()).thenReturn(live);
        when(liveSettingsService.applicationAllowed(live)).thenReturn(true);
        when(liveSettingsService.managerAllowed(live, SOURCE_MANAGER_ID))
                .thenReturn(true);
        when(shadow.groupNotificationsEnabled()).thenReturn(true);
        when(shadow.notificationGroupChatId()).thenReturn(AUDIT_CHAT_ID);
        when(shadow.recipientMinimumRating()).thenReturn(85);
        when(workflowRepository.reservedByManagerSince(any()))
                .thenReturn(List.of());

        EmergencyCaseProjection emergencyCase = emergencyCase();
        when(repository.findReadyCases()).thenReturn(List.of(emergencyCase));
        EmergencyRecipientProjection source =
                mock(EmergencyRecipientProjection.class);
        when(source.getWorkerId()).thenReturn(SOURCE_WORKER_ID);
        EmergencyRecipientProjection alreadyOffered =
                mock(EmergencyRecipientProjection.class);
        when(alreadyOffered.getWorkerId()).thenReturn(12L);
        EmergencyRecipientProjection reserve = recipient(
                TARGET_WORKER_ID,
                99L,
                -13L,
                0L
        );
        when(repository.findEligibleRecipients(
                eq(BigDecimal.valueOf(85)),
                any()
        ))
                .thenReturn(List.of(source, alreadyOffered, reserve));
        WorkflowCandidatePairProjection prior =
                mock(WorkflowCandidatePairProjection.class);
        when(prior.getShadowCaseId()).thenReturn(SHADOW_CASE_ID);
        when(prior.getWorkerId()).thenReturn(12L);
        when(repository.findWorkflowCandidatePairs(List.of(SHADOW_CASE_ID)))
                .thenReturn(List.of(prior));
        when(transactionService.apply(
                any(EmergencyCase.class),
                any(Recipient.class),
                eq(BigDecimal.valueOf(85)),
                eq(AUDIT_CHAT_ID)
        )).thenReturn(new ApplyResult(ASSIGNMENT_ID, "APPLIED", "ok"));

        WorkloadEmergencyAssignmentService service =
                new WorkloadEmergencyAssignmentService(
                        repository,
                        workflowRepository,
                        transactionService,
                        liveSettingsService,
                        shadowSettingsService
                );
        List<ApplyResult> results = service.applyStaffingFallbacks();

        assertThat(results).extracting(ApplyResult::assignmentId)
                .containsExactly(ASSIGNMENT_ID);
        ArgumentCaptor<Recipient> selected = ArgumentCaptor.forClass(Recipient.class);
        verify(transactionService).apply(
                eq(new EmergencyCase(
                        SHADOW_CASE_ID,
                        SOURCE_MANAGER_ID,
                        SOURCE_WORKER_ID,
                        COMPANY_ID,
                        "Компания",
                        REVIEW_ID,
                        WORKFLOW_ID
                )),
                selected.capture(),
                eq(BigDecimal.valueOf(85)),
                eq(AUDIT_CHAT_ID)
        );
        assertThat(selected.getValue().workerId()).isEqualTo(TARGET_WORKER_ID);
        assertThat(selected.getValue().managerId()).isEqualTo(99L);
    }

    @Test
    void emergencyTransactionMovesOnlyTheSelectedCardAndClosesExhaustedWorkflow() {
        clock();
        WorkloadLiveSettingsResponse live = live();
        when(liveSettingsService.current()).thenReturn(live);
        when(liveSettingsService.applicationAllowed(live)).thenReturn(true);
        when(liveSettingsService.managerAllowed(live, SOURCE_MANAGER_ID))
                .thenReturn(true);
        when(repository.insertPrepared(
                anyString(),
                eq(SHADOW_CASE_ID),
                eq(WORKFLOW_ID),
                eq(REVIEW_ID),
                eq(TARGET_WORKER_ID),
                eq(-13L),
                eq(AUDIT_CHAT_ID),
                eq(BigDecimal.valueOf(85)),
                eq("LIVE"),
                anyString(),
                any(),
                any(),
                any()
        )).thenReturn(1);
        PreparedProjection prepared = prepared();
        when(repository.findPrepared(anyString()))
                .thenReturn(Optional.of(prepared));
        when(executionRepository.ensureTargetCompanyLink(
                COMPANY_ID,
                TARGET_WORKER_ID
        )).thenReturn(1);
        when(repository.transferReview(
                REVIEW_ID,
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID,
                COMPANY_ID
        )).thenReturn(1);
        when(repository.markApplied(eq(ASSIGNMENT_ID), eq(true), any()))
                .thenReturn(1);
        when(repository.markExhaustedWorkflowEmergencyApplied(
                eq(WORKFLOW_ID),
                any()
        )).thenReturn(1);

        WorkloadEmergencyAssignmentTransactionService service =
                new WorkloadEmergencyAssignmentTransactionService(
                        repository,
                        executionRepository,
                        liveSettingsService,
                        shadowSettingsService
                );
        ApplyResult result = service.apply(
                new EmergencyCase(
                        SHADOW_CASE_ID,
                        SOURCE_MANAGER_ID,
                        SOURCE_WORKER_ID,
                        COMPANY_ID,
                        "Компания",
                        REVIEW_ID,
                        WORKFLOW_ID
                ),
                new Recipient(TARGET_WORKER_ID, 99L, -13L, "Резерв"),
                BigDecimal.valueOf(85),
                AUDIT_CHAT_ID
        );

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.assignmentId()).isEqualTo(ASSIGNMENT_ID);
        verify(executionRepository).clearCredentialPreparations(List.of(REVIEW_ID));
        verify(repository).transferReview(
                REVIEW_ID,
                SOURCE_WORKER_ID,
                TARGET_WORKER_ID,
                COMPANY_ID
        );
        verify(repository).markExhaustedWorkflowEmergencyApplied(
                eq(WORKFLOW_ID),
                any()
        );
    }

    @Test
    void emergencyRollbackRestoresCardAndRemovesOnlyLinkCreatedByFallback() {
        clock();
        when(repository.claimRollback(eq(ASSIGNMENT_ID), any())).thenReturn(1);
        EmergencyRollbackProjection context =
                mock(EmergencyRollbackProjection.class);
        when(context.getSourceWorkerId()).thenReturn(SOURCE_WORKER_ID);
        when(context.getTargetWorkerId()).thenReturn(TARGET_WORKER_ID);
        when(context.getCompanyId()).thenReturn(COMPANY_ID);
        when(context.getTargetCompanyLinkAdded()).thenReturn(true);
        when(context.getRollbackSafe()).thenReturn(true);
        when(repository.findRollbackContext(ASSIGNMENT_ID))
                .thenReturn(Optional.of(context));
        when(repository.rollbackReview(ASSIGNMENT_ID)).thenReturn(1);
        when(repository.markRolledBack(eq(ASSIGNMENT_ID), any())).thenReturn(1);

        WorkloadEmergencyRollbackService service =
                new WorkloadEmergencyRollbackService(
                        repository,
                        executionRepository,
                        shadowSettingsService
                );
        var result = service.rollback(
                ASSIGNMENT_ID,
                "  " + WorkloadEmergencyRollbackService.CONFIRMATION + " "
        );

        assertThat(result.status()).isEqualTo("ROLLED_BACK");
        verify(executionRepository).ensureSourceCompanyLink(
                COMPANY_ID,
                SOURCE_WORKER_ID
        );
        verify(executionRepository).removeTargetCompanyLinkIfUnused(
                COMPANY_ID,
                TARGET_WORKER_ID
        );
        verify(repository).rollbackReview(ASSIGNMENT_ID);
        verify(repository).markRolledBack(eq(ASSIGNMENT_ID), any());
    }

    @Test
    void emergencyRollbackRequiresExactConfirmationBeforeClaim() {
        WorkloadEmergencyRollbackService service =
                new WorkloadEmergencyRollbackService(
                        repository,
                        executionRepository,
                        shadowSettingsService
                );

        assertThatThrownBy(() -> service.rollback(ASSIGNMENT_ID, "да"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(WorkloadEmergencyRollbackService.CONFIRMATION);
        verifyNoInteractions(repository, executionRepository);
    }

    private EmergencyCaseProjection emergencyCase() {
        EmergencyCaseProjection value = mock(EmergencyCaseProjection.class);
        when(value.getShadowCaseId()).thenReturn(SHADOW_CASE_ID);
        when(value.getSourceManagerId()).thenReturn(SOURCE_MANAGER_ID);
        when(value.getSourceWorkerId()).thenReturn(SOURCE_WORKER_ID);
        when(value.getCompanyId()).thenReturn(COMPANY_ID);
        when(value.getCompanyTitle()).thenReturn("Компания");
        when(value.getReviewId()).thenReturn(REVIEW_ID);
        when(value.getExhaustedWorkflowId()).thenReturn(WORKFLOW_ID);
        return value;
    }

    private EmergencyRecipientProjection recipient(
            long workerId,
            long managerId,
            long groupChatId,
            long assignedToday
    ) {
        EmergencyRecipientProjection value =
                mock(EmergencyRecipientProjection.class);
        when(value.getWorkerId()).thenReturn(workerId);
        when(value.getManagerId()).thenReturn(managerId);
        when(value.getTargetGroupChatId()).thenReturn(groupChatId);
        when(value.getEmergencyAssignmentsToday()).thenReturn(assignedToday);
        when(value.getWorkerName()).thenReturn("Специалист " + workerId);
        return value;
    }

    private PreparedProjection prepared() {
        PreparedProjection value = mock(PreparedProjection.class);
        when(value.getAssignmentId()).thenReturn(ASSIGNMENT_ID);
        when(value.getSourceWorkerId()).thenReturn(SOURCE_WORKER_ID);
        when(value.getTargetWorkerId()).thenReturn(TARGET_WORKER_ID);
        when(value.getCompanyId()).thenReturn(COMPANY_ID);
        when(value.getReviewId()).thenReturn(REVIEW_ID);
        when(value.getExhaustedWorkflowId()).thenReturn(WORKFLOW_ID);
        return value;
    }

    private WorkloadLiveSettingsResponse live() {
        return new WorkloadLiveSettingsResponse(
                "LIVE",
                true,
                "2026-07-01",
                14,
                168,
                1,
                List.of(SOURCE_MANAGER_ID),
                15,
                "00:00",
                "23:59",
                1,
                3,
                30,
                5,
                true,
                1
        );
    }

    private void clock() {
        when(shadowSettingsService.current()).thenReturn(shadow);
        when(shadowSettingsService.zone(shadow))
                .thenReturn(ZoneId.of("Asia/Irkutsk"));
    }
}
