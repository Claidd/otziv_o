package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorDirectSettlementResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlement;
import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlementType;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorDirectSettlementRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorDirectSettlementServiceTest {

    @Mock
    private ContractorPaymentProfileRepository profileRepository;
    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorDirectSettlementRepository settlementRepository;
    @Mock
    private com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService taskCapacityService;
    @Mock
    private ContractorPaymentAccountingService accountingService;
    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ContractorPaymentTargetAccessPolicy targetAccessPolicy;

    @InjectMocks
    private ContractorDirectSettlementService service;

    private ContractorPaymentProfile profile;
    private LocalDateTime effectiveAt;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(42L);
        profile = new ContractorPaymentProfile();
        profile.setId(7L);
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(false);
        effectiveAt = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MICROS);
        lenient().when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        lenient().when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
    }

    @Test
    void createsConfirmedShadowPaymentForDisabledHistoricalProfileWithoutClientPii() {
        stubNewPayment(ContractorAllocationMode.SHADOW, 2_000L);

        ContractorDirectSettlementResponse response = service.createPayment(42L, 7L, request(1_000L, "pay-1"));

        assertThat(response.id()).isEqualTo(90L);
        assertThat(response.mode()).isEqualTo(ContractorAllocationMode.SHADOW);
        assertThat(response.type()).isEqualTo(ContractorDirectSettlementType.PAYMENT);
        assertThat(response.allocationId()).isEqualTo(91L);
        ArgumentCaptor<ContractorPaymentAllocation> allocationCaptor =
                ArgumentCaptor.forClass(ContractorPaymentAllocation.class);
        verify(allocationRepository, atLeastOnce()).saveAndFlush(allocationCaptor.capture());
        ContractorPaymentAllocation allocation = allocationCaptor.getValue();
        assertThat(allocation.getSourceType()).isEqualTo(ContractorAllocationSourceType.DIRECT_SETTLEMENT);
        assertThat(allocation.getSourceId()).isEqualTo(90L);
        assertThat(allocation.getRecipientType()).isEqualTo(ContractorRecipientType.SPECIALIST);
        assertThat(allocation.getRecipientNameSnapshot()).isNull();
        assertThat(allocation.getPaymentPhoneSnapshot()).isNull();
        assertThat(allocation.getBankNameSnapshot()).isNull();
        assertThat(allocation.getPaymentCommentSnapshot()).isNull();
        assertThat(allocation.getReconcileClaimToken()).isNull();
        verify(accountingService).recordReservation(allocation);
        verify(accountingService).recordConfirmation(
                allocation,
                1_000L,
                effectiveAt,
                "Перевод по реестру",
                "DIRECT_SETTLEMENT:PAYMENT:90",
                true,
                false
        );
    }

    @Test
    void usesLiveModeWhenLiveAccountingAlreadyExists() {
        stubNewPayment(ContractorAllocationMode.LIVE, 1_000L);

        ContractorDirectSettlementResponse response = service.createPayment(
                42L, 7L, request(1_000L, "pay-live", ContractorAllocationMode.LIVE)
        );

        assertThat(response.mode()).isEqualTo(ContractorAllocationMode.LIVE);
        verify(accountingService).recordConfirmation(
                any(ContractorPaymentAllocation.class),
                eq(1_000L),
                eq(effectiveAt),
                eq("Перевод по реестру"),
                eq("DIRECT_SETTLEMENT:PAYMENT:90"),
                eq(false),
                eq(false)
        );
    }

    @Test
    void rejectsFutureEffectiveTimeBeforeTakingCapacity() {
        ContractorDirectSettlementRequest future = new ContractorDirectSettlementRequest(
                ContractorAllocationMode.SHADOW,
                1_000L,
                LocalDateTime.now().plusDays(1),
                "Причина",
                "Документ",
                "future"
        );

        assertThatThrownBy(() -> service.createPayment(42L, 7L, future))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("будущем");
        verify(taskCapacityService, never()).ordinaryAvailable(any(), any());
    }

    @Test
    void rejectsAmountAboveCurrentAvailableBalance() {
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(7L, "too-much"))
                .thenReturn(Optional.empty());
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.SHADOW)).thenReturn(999L);

        assertThatThrownBy(() -> service.createPayment(42L, 7L, request(1_000L, "too-much")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("доступный остаток");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    void liveDirectPaymentCannotConsumeCapacityPromisedToTasksIncludingShadowCarry() {
        // Canonical LIVE availability is 20k from a 100k position after an
        // 80k task commitment/foreign SHADOW task exposure.
        stubNewPayment(ContractorAllocationMode.LIVE, 20_000L);

        assertThatThrownBy(() -> service.createPayment(
                42L, 7L, request(30_000L, "task-priority-reject", ContractorAllocationMode.LIVE)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("доступный остаток");

        ContractorDirectSettlementResponse response = service.createPayment(
                42L, 7L, request(20_000L, "task-priority-exact", ContractorAllocationMode.LIVE)
        );
        assertThat(response.amountKopecks()).isEqualTo(20_000L);
    }

    @Test
    void staleExpectedModeConflictsAfterPhaseThenProfileMutexes() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(7L, "stale-mode"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(42L, 7L, request(1_000L, "stale-mode")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Режим учёта изменился");

        var locks = org.mockito.Mockito.inOrder(accountingPhaseService, profileRepository);
        locks.verify(accountingPhaseService).lockCurrent();
        locks.verify(profileRepository).findByIdForUpdate(7L);
        verify(taskCapacityService, never()).ordinaryAvailable(any(), any());
    }

    @Test
    void rejectsAmountAboveSafeBusinessLimit() {
        ContractorDirectSettlementRequest excessive = request(100_000_000_001L, "excessive");

        assertThatThrownBy(() -> service.createPayment(42L, 7L, excessive))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("допустимый предел");
        verify(taskCapacityService, never()).ordinaryAvailable(any(), any());
    }

    @Test
    void idempotentReplayReturnsExistingPaymentAndDifferentPayloadConflicts() {
        ContractorDirectSettlement existing = payment(1_000L, "same", null);
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(7L, "same"))
                .thenReturn(Optional.of(existing));

        ContractorDirectSettlementResponse replay = service.createPayment(
                42L, 7L, request(1_000L, "same", ContractorAllocationMode.LIVE)
        );
        assertThat(replay.id()).isEqualTo(20L);
        verify(taskCapacityService, never()).ordinaryAvailable(any(), any());

        assertThatThrownBy(() -> service.createPayment(
                42L, 7L, request(999L, "same", ContractorAllocationMode.LIVE)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("другими данными");
    }

    @Test
    void recordsPartialReversalAsImmutableChild() {
        ReversalFixture fixture = stubReversal(0L, List.of(), 400L, "reverse-part");

        ContractorDirectSettlementResponse response = service.createReversal(
                42L,
                7L,
                20L,
                request(400L, "reverse-part", ContractorAllocationMode.LIVE)
        );

        assertThat(response.type()).isEqualTo(ContractorDirectSettlementType.REVERSAL);
        assertThat(response.originalSettlementId()).isEqualTo(20L);
        assertThat(response.allocationId()).isEqualTo(21L);
        assertThat(fixture.allocation().getReturnedKopecks()).isEqualTo(400L);
        assertThat(fixture.allocation().getStatus()).isEqualTo(ContractorAllocationStatus.PARTIALLY_RETURNED);
    }

    @Test
    void recordsFullReversalAfterPriorPartialReversal() {
        ContractorDirectSettlement original = payment(1_000L, "original", 21L);
        ContractorDirectSettlement prior = ContractorDirectSettlement.reversal(
                original,
                400L,
                effectiveAt,
                "Ранее",
                "Документ",
                "old-reversal",
                "admin"
        );
        ReflectionTestUtils.setField(prior, "id", 22L);
        ReversalFixture fixture = stubReversal(original, 400L, List.of(prior), 600L, "reverse-full");

        service.createReversal(
                42L, 7L, 20L, request(600L, "reverse-full", ContractorAllocationMode.LIVE)
        );

        assertThat(fixture.allocation().getReturnedKopecks()).isEqualTo(1_000L);
        assertThat(fixture.allocation().getStatus()).isEqualTo(ContractorAllocationStatus.RETURNED);
    }

    @Test
    void replayedReversalMustMatchOriginalAndPayload() {
        ContractorDirectSettlement original = payment(1_000L, "original", 21L);
        ContractorDirectSettlement existing = ContractorDirectSettlement.reversal(
                original,
                300L,
                effectiveAt,
                "Перевод по реестру",
                "Документ-1",
                "reverse-replay",
                "admin"
        );
        ReflectionTestUtils.setField(existing, "id", 30L);
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(7L, "reverse-replay"))
                .thenReturn(Optional.of(existing));

        assertThat(service.createReversal(
                42L, 7L, 20L, request(300L, "reverse-replay", ContractorAllocationMode.LIVE)
        ).id())
                .isEqualTo(30L);
        assertThatThrownBy(() -> service.createReversal(
                42L, 7L, 999L, request(300L, "reverse-replay", ContractorAllocationMode.LIVE)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("другими данными");
    }

    private void stubNewPayment(ContractorAllocationMode mode, long available) {
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(eq(7L), any()))
                .thenReturn(Optional.empty());
        when(accountingPhaseService.lockCurrent()).thenReturn(mode);
        when(taskCapacityService.ordinaryAvailable(profile, mode)).thenReturn(available);
        when(settlementRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ContractorDirectSettlement value = invocation.getArgument(0);
            if (value.getId() == null) {
                ReflectionTestUtils.setField(value, "id", 90L);
                ReflectionTestUtils.setField(value, "createdAt", effectiveAt.plusMinutes(1));
            }
            return value;
        });
        when(allocationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ContractorPaymentAllocation value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(91L);
            }
            return value;
        });
        when(accountingService.recordConfirmation(
                any(), anyLong(), any(), any(), any(), anyBoolean(), anyBoolean()
        ))
                .thenAnswer(invocation -> {
                    ContractorPaymentAllocation allocation = invocation.getArgument(0);
                    long confirmed = invocation.getArgument(1);
                    allocation.setConfirmedKopecks(confirmed);
                    allocation.setStatus(mode == ContractorAllocationMode.SHADOW
                            ? ContractorAllocationStatus.SIMULATED_PAID
                            : ContractorAllocationStatus.CONFIRMED);
                    return true;
                });
    }

    private ReversalFixture stubReversal(
            long returned,
            List<ContractorDirectSettlement> reversals,
            long delta,
            String key
    ) {
        return stubReversal(payment(1_000L, "original", 21L), returned, reversals, delta, key);
    }

    private ReversalFixture stubReversal(
            ContractorDirectSettlement original,
            long returned,
            List<ContractorDirectSettlement> reversals,
            long delta,
            String key
    ) {
        ContractorPaymentAllocation allocation = original.getAllocation();
        allocation.setReturnedKopecks(returned);
        allocation.setStatus(returned == 0L
                ? ContractorAllocationStatus.CONFIRMED
                : ContractorAllocationStatus.PARTIALLY_RETURNED);
        when(settlementRepository.findByProfileIdAndIdempotencyKeyForUpdate(7L, key))
                .thenReturn(Optional.empty());
        when(settlementRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(original));
        when(allocationRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(allocation));
        when(settlementRepository.findAllReversalsForUpdate(20L)).thenReturn(reversals);
        AtomicLong ids = new AtomicLong(40L);
        when(settlementRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ContractorDirectSettlement value = invocation.getArgument(0);
            if (value.getId() == null) {
                ReflectionTestUtils.setField(value, "id", ids.getAndIncrement());
                ReflectionTestUtils.setField(value, "createdAt", effectiveAt.plusMinutes(1));
            }
            return value;
        });
        when(accountingService.recordReturnTotal(
                eq(allocation),
                eq(returned + delta),
                eq(effectiveAt),
                eq("Перевод по реестру"),
                any()
        )).thenAnswer(invocation -> {
            allocation.setReturnedKopecks(returned + delta);
            allocation.setStatus(returned + delta == allocation.getConfirmedKopecks()
                    ? ContractorAllocationStatus.RETURNED
                    : ContractorAllocationStatus.PARTIALLY_RETURNED);
            return true;
        });
        when(allocationRepository.saveAndFlush(allocation)).thenReturn(allocation);
        return new ReversalFixture(allocation);
    }

    private ContractorDirectSettlement payment(long amount, String key, Long allocationId) {
        ContractorDirectSettlement settlement = ContractorDirectSettlement.payment(
                profile,
                ContractorAllocationMode.LIVE,
                amount,
                effectiveAt,
                "Перевод по реестру",
                "Документ-1",
                key,
                "admin"
        );
        ReflectionTestUtils.setField(settlement, "id", 20L);
        ReflectionTestUtils.setField(settlement, "createdAt", effectiveAt.plusMinutes(1));
        if (allocationId != null) {
            ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
            allocation.setId(allocationId);
            allocation.setMode(ContractorAllocationMode.LIVE);
            allocation.setSourceType(ContractorAllocationSourceType.DIRECT_SETTLEMENT);
            allocation.setSourceId(20L);
            allocation.setAttemptNo(1);
            allocation.setRecipientProfile(profile);
            allocation.setRecipientUserId(42L);
            allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
            allocation.setAmountKopecks(amount);
            allocation.setConfirmedKopecks(amount);
            allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
            settlement.attachAllocation(allocation);
        }
        return settlement;
    }

    private ContractorDirectSettlementRequest request(long amount, String key) {
        return request(amount, key, ContractorAllocationMode.SHADOW);
    }

    private ContractorDirectSettlementRequest request(
            long amount,
            String key,
            ContractorAllocationMode expectedMode
    ) {
        return new ContractorDirectSettlementRequest(
                expectedMode,
                amount,
                effectiveAt,
                "Перевод по реестру",
                "Документ-1",
                key
        );
    }

    private record ReversalFixture(ContractorPaymentAllocation allocation) {
    }
}
