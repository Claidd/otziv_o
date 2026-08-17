package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardManualResolutionRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorLegacyRewardReconciliationResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.CandidateRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.DbNow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.ItemRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.RunRow;
import com.hunt.otziv.contractor_payments.repository.ContractorLegacyRewardReconciliationRepository.SnapshotItem;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorLegacyRewardReconciliationServiceTest {

    private static final long RUN_ID = 77L;
    private static final long ORDER_ID = 9001L;
    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 14);
    private static final LocalDateTime DB_NOW = LocalDateTime.of(2026, 8, 14, 12, 0);

    @Mock private ContractorLegacyRewardReconciliationRepository repository;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock private ContractorCompletionCutoverStateService cutoverStateService;
    @Mock private ReviewRecoveryGateService recoveryGateService;
    @Mock private BusinessAuditService businessAuditService;

    private ContractorLegacyRewardReconciliationService service;
    private AtomicReference<List<SnapshotItem>> preparedItems;
    private AtomicReference<RunRow> preparedRun;

    @BeforeEach
    void setUp() {
        service = new ContractorLegacyRewardReconciliationService(
                repository,
                zpRepository,
                rolloutStateService,
                accountingPhaseService,
                cutoverStateService,
                recoveryGateService,
                businessAuditService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "owner-auditor",
                        "",
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
                )
        );

        ContractorPaymentRolloutState rollout = mock(ContractorPaymentRolloutState.class);
        when(rollout.getAccountingAuthority()).thenReturn(ContractorPaymentAccountingAuthority.LEGACY);
        when(rolloutStateService.lockCurrent()).thenReturn(rollout);
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.empty());
        when(repository.dbNow()).thenReturn(new DbNow(START_DATE, DB_NOW));

        preparedItems = new AtomicReference<>();
        preparedRun = new AtomicReference<>();
        when(repository.insertRun(
                any(LocalDate.class), anyString(), anyInt(), anyInt(), anyInt(), anyInt(),
                any(LocalDateTime.class), anyString()
        )).thenAnswer(invocation -> {
            RunRow run = new RunRow(
                    RUN_ID,
                    invocation.getArgument(0),
                    "PREPARED",
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    DB_NOW,
                    invocation.getArgument(6),
                    invocation.getArgument(7),
                    null,
                    0L
            );
            preparedRun.set(run);
            return RUN_ID;
        });
        doAnswer(invocation -> {
            preparedItems.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(repository).insertItems(eq(RUN_ID), anyList());
        when(repository.lockRun(RUN_ID)).thenAnswer(invocation -> preparedRun.get());
        when(repository.findManualItems(RUN_ID)).thenAnswer(invocation ->
                preparedItems.get() == null ? List.of() : itemRows(preparedItems.get())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mixedNullAndApplicationCompletionSourcesPrepareAndResolveAsOneManualGroup() {
        CandidateRow legacyManager = candidate(
                101L, 501L, 601L, null, null, false, ContractorRole.MANAGER
        );
        CandidateRow completedSpecialist = candidate(
                102L, 502L, 602L,
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                ContractorRole.SPECIALIST.name(), true, ContractorRole.SPECIALIST
        );
        CandidateRow badReviewManager = candidate(
                103L, 503L, 603L,
                ContractorRewardSourceCodes.badReviewManager(55L),
                ContractorRole.MANAGER.name(), true, ContractorRole.MANAGER
        );
        when(repository.findCandidates(START_DATE)).thenReturn(List.of(
                legacyManager, completedSpecialist, badReviewManager
        ));

        ContractorLegacyRewardReconciliationResponse prepared = service.prepare();

        assertThat(prepared.runId()).isEqualTo(RUN_ID);
        assertThat(prepared.manualOrderCount()).isEqualTo(1);
        assertThat(prepared.manualRowCount()).isEqualTo(3);
        assertThat(prepared.manualGroups()).singleElement().satisfies(group -> {
            assertThat(group.orderId()).isEqualTo(ORDER_ID);
            assertThat(group.evidenceCategory()).isEqualTo("COMPLETION_DATE_REQUIRES_EVIDENCE");
        });
        List<SnapshotItem> snapshot = preparedItems.get();
        assertThat(snapshot).hasSize(3).allMatch(item -> "MANUAL".equals(item.kind()));
        assertThat(snapshot.get(0).targetSource())
                .isEqualTo(ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
        assertThat(snapshot.get(0).targetRole()).isEqualTo(ContractorRole.MANAGER);
        assertThat(snapshot.get(1).targetSource())
                .isEqualTo(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
        assertThat(snapshot.get(1).targetRole()).isEqualTo(ContractorRole.SPECIALIST);
        assertThat(snapshot.get(2).targetSource())
                .isEqualTo(ContractorRewardSourceCodes.badReviewManager(55L));
        assertThat(snapshot.get(2).targetRole()).isEqualTo(ContractorRole.MANAGER);

        List<ItemRow> lockedItems = itemRows(snapshot);
        when(repository.lockItems(RUN_ID, "MANUAL", ORDER_ID)).thenReturn(lockedItems);
        when(repository.lockExistingOrders(any())).thenReturn(1);
        when(repository.countActiveRows(ORDER_ID)).thenReturn(lockedItems.size());
        when(repository.casApply(lockedItems.get(0))).thenReturn(1);
        when(repository.exactOriginalSnapshot(any(ItemRow.class))).thenReturn(true);
        when(zpRepository.findByIdForContractorLedgerUpdate(anyLong()))
                .thenReturn(Optional.of(new Zp()));

        service.resolveManual(
                RUN_ID,
                ORDER_ID,
                new ContractorLegacyRewardManualResolutionRequest(
                        prepared.snapshotHash(),
                        snapshot.get(0).groupHash(),
                        START_DATE.minusDays(1),
                        "bank-statement-2026-08",
                        "Проверена исходная дата завершения",
                        ContractorLegacyRewardReconciliationService.MANUAL_CONFIRMATION
                )
        );

        verify(repository).casApply(lockedItems.get(0));
        verify(repository, never()).casApply(lockedItems.get(1));
        verify(repository, never()).casApply(lockedItems.get(2));
        verify(repository).exactOriginalSnapshot(lockedItems.get(1));
        verify(repository).exactOriginalSnapshot(lockedItems.get(2));
        verify(repository).markManualItemsApplied(
                RUN_ID,
                ORDER_ID,
                START_DATE.minusDays(1),
                "bank-statement-2026-08",
                "Проверена исходная дата завершения",
                "owner-auditor",
                DB_NOW
        );
    }

    @Test
    void unknownSourceStaysExplicitlyUnresolvedAndCannotBeManuallyApplied() {
        CandidateRow unknown = candidate(
                201L, 701L, 801L,
                "UNKNOWN_FINANCIAL_SOURCE",
                ContractorRole.SPECIALIST.name(), true, ContractorRole.SPECIALIST
        );
        when(repository.findCandidates(START_DATE)).thenReturn(List.of(unknown));

        ContractorLegacyRewardReconciliationResponse prepared = service.prepare();

        List<SnapshotItem> snapshot = preparedItems.get();
        assertThat(snapshot).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("MANUAL");
            assertThat(item.evidenceCategory()).isEqualTo("IDENTITY_OR_SOURCE_AMBIGUOUS");
            assertThat(item.targetSource()).isNull();
            assertThat(item.targetRole()).isNull();
        });
        assertThat(prepared.manualGroups()).singleElement().satisfies(group ->
                assertThat(group.evidenceCategory()).isEqualTo("IDENTITY_OR_SOURCE_AMBIGUOUS")
        );

        when(repository.lockItems(RUN_ID, "MANUAL", ORDER_ID)).thenReturn(itemRows(snapshot));
        when(repository.lockExistingOrders(any())).thenReturn(1);

        assertThatThrownBy(() -> service.resolveManual(
                RUN_ID,
                ORDER_ID,
                new ContractorLegacyRewardManualResolutionRequest(
                        prepared.snapshotHash(),
                        snapshot.get(0).groupHash(),
                        START_DATE.minusDays(1),
                        "operator-evidence",
                        "Попытка ручного подтверждения",
                        ContractorLegacyRewardReconciliationService.MANUAL_CONFIRMATION
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getReason()).contains("Получатель не выводится");
        });

        verify(repository, never()).casApply(any(ItemRow.class));
        verify(repository, never()).markManualItemsApplied(
                anyLong(), anyLong(), any(), anyString(), anyString(), anyString(), any()
        );
    }

    private CandidateRow candidate(
            long zpId,
            long userId,
            long professionId,
            String source,
            String role,
            boolean finalAttribution,
            ContractorRole inferredRole
    ) {
        return new CandidateRow(
                zpId,
                ORDER_ID,
                userId,
                professionId,
                new BigDecimal("500.00"),
                1,
                START_DATE.minusDays(10),
                DB_NOW.minusDays(10),
                true,
                source,
                role,
                finalAttribution,
                new BigDecimal("500.00"),
                "{\"orderId\":" + ORDER_ID + "}",
                inferredRole,
                true,
                false,
                false
        );
    }

    private List<ItemRow> itemRows(List<SnapshotItem> snapshot) {
        return snapshot.stream().map(item -> {
            CandidateRow row = item.row();
            return new ItemRow(
                    10_000L + row.zpId(),
                    RUN_ID,
                    row.orderId(),
                    row.zpId(),
                    item.kind(),
                    "PENDING",
                    item.evidenceCategory(),
                    item.groupHash(),
                    row.userId(),
                    row.professionId(),
                    row.amount(),
                    row.units(),
                    row.occurredOn(),
                    row.updatedAt(),
                    row.active(),
                    row.source(),
                    row.role(),
                    row.attributionFinal(),
                    row.rewardBasis(),
                    item.attributionSnapshotHash(),
                    item.targetSource(),
                    item.targetRole() == null ? null : item.targetRole().name(),
                    true,
                    null,
                    null
            );
        }).toList();
    }
}
