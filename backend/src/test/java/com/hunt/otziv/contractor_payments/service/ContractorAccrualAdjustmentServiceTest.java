package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common.events.ContractorAccrualAdjustedEvent;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorAccrualAdjustmentReversalRequest;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualBucket;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorAccrualAdjustmentRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorAccrualAdjustmentServiceTest {

    private static final long USER_ID = 42L;
    private static final long PROFILE_ID = 7L;

    @Mock private ContractorAccrualAdjustmentRepository adjustmentRepository;
    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorRewardLedgerRepository rewardLedgerRepository;
    @Mock private ContractorRewardLedgerService rewardLedgerService;
    @Mock private ContractorPaymentProfileService profileService;
    @Mock private ContractorPaymentAccountingPhaseService phaseService;
    @Mock private ContractorPaymentAccountingService accountingService;
    @Mock private ContractorPaymentAllocationRepository allocationRepository;
    @Mock private ManualPaymentTaskContractorCapacityService taskCapacityService;
    @Mock private ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    @Mock private ContractorPaymentBusinessClock businessClock;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ContractorBonusTelegramNotificationService bonusTelegramNotificationService;

    @InjectMocks private ContractorAccrualAdjustmentService service;

    private ContractorPaymentProfile profile;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(USER_ID);
        profile = new ContractorPaymentProfile();
        profile.setId(PROFILE_ID);
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "credential", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bonusPostsInCurrentBusinessMonthWhileRememberingAnEarlierReferenceMonth() {
        stubLockedProfile();
        profile.setRole(ContractorRole.MANAGER);
        LocalDate effectiveOn = LocalDate.of(2026, 11, 1);
        LocalDateTime createdAt = effectiveOn.atTime(8, 30);
        when(businessClock.today()).thenReturn(effectiveOn);
        when(businessClock.now()).thenReturn(createdAt);
        when(adjustmentRepository.saveAndFlush(any(ContractorAccrualAdjustment.class)))
                .thenAnswer(invocation -> savedWithId(invocation.getArgument(0), 71L));

        var response = service.create(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.BONUS, 5_000L, "2026-10", "bonus-1"
        ));

        ArgumentCaptor<ContractorAccrualAdjustment> entry = ArgumentCaptor.forClass(
                ContractorAccrualAdjustment.class
        );
        verify(adjustmentRepository).saveAndFlush(entry.capture());
        assertThat(entry.getValue().getSignedAmountKopecks()).isEqualTo(5_000L);
        assertThat(entry.getValue().getKind()).isEqualTo(ContractorAccrualAdjustmentKind.BONUS);
        assertThat(entry.getValue().getBucket()).isEqualTo(ContractorAccrualBucket.BONUS);
        assertThat(entry.getValue().getEffectiveOn()).isEqualTo(effectiveOn);
        assertThat(entry.getValue().getReferenceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(entry.getValue().getCreatedBy()).isEqualTo("admin");
        assertThat(response.referenceMonth()).isEqualTo("2026-10");
        assertThat(response.signedAmountKopecks()).isEqualTo(5_000L);
        ArgumentCaptor<ContractorAccrualAdjustedEvent> published = ArgumentCaptor.forClass(
                ContractorAccrualAdjustedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().effectiveOn()).isEqualTo(effectiveOn);
        verify(bonusTelegramNotificationService).enqueue(entry.getValue());
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                eq("CREATE_CONTRACTOR_ACCRUAL_ADJUSTMENT"), eq("CONTRACTOR_ACCRUAL_ADJUSTMENT"),
                eq(71L), any(), any(), any(), any(), any()
        );
        InOrder locks = inOrder(phaseService, profileRepository);
        locks.verify(phaseService).lockCurrent();
        locks.verify(profileRepository).findByIdForUpdate(PROFILE_ID);
    }

    @Test
    void deductionCannotConsumeMoneyAlreadyCommittedToInvoicesAndTasks() {
        stubLockedProfile();
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 10, 16));
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .thenReturn(4_999L);

        assertThatThrownBy(() -> service.create(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.DEDUCTION, 5_000L, "2026-10", "deduction-1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("свободную сумму");
        verify(adjustmentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(businessAuditService, eventPublisher);
        verifyNoInteractions(bonusTelegramNotificationService);
    }

    @Test
    void successfulDeductionDoesNotSendBonusNotification() {
        stubLockedProfile();
        LocalDate effectiveOn = LocalDate.of(2026, 10, 16);
        when(businessClock.today()).thenReturn(effectiveOn);
        when(businessClock.now()).thenReturn(effectiveOn.atStartOfDay());
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .thenReturn(10_000L);
        when(rewardLedgerService.totalAccrued(profile)).thenReturn(10_000L);
        when(adjustmentRepository.saveAndFlush(any(ContractorAccrualAdjustment.class)))
                .thenAnswer(invocation -> savedWithId(invocation.getArgument(0), 72L));

        service.create(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.DEDUCTION, 1_000L, "2026-10", "deduction-ok"
        ));

        verify(adjustmentRepository).saveAndFlush(any());
        verifyNoInteractions(bonusTelegramNotificationService);
    }

    @Test
    void retryWithSameKeyReturnsOriginalWithoutSecondPostingOrAudit() {
        stubLockedProfile();
        ContractorAccrualAdjustment original = entry(
                81L, ContractorAccrualAdjustmentKind.BONUS, ContractorAccrualBucket.BONUS,
                3_000L, LocalDate.of(2026, 10, 1), null, "bonus-2"
        );
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 10, 16));
        when(adjustmentRepository.findByProfileIdAndIdempotencyKey(PROFILE_ID, "bonus-2"))
                .thenReturn(Optional.of(original));

        var response = service.create(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.BONUS, 3_000L, "2026-10", "bonus-2"
        ));

        assertThat(response.id()).isEqualTo(81L);
        verify(adjustmentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(businessAuditService, eventPublisher);
        verifyNoInteractions(bonusTelegramNotificationService);
    }

    @Test
    void duplicateKeyWithDifferentAmountIsRejected() {
        stubLockedProfile();
        ContractorAccrualAdjustment original = entry(
                82L, ContractorAccrualAdjustmentKind.BONUS, ContractorAccrualBucket.BONUS,
                3_000L, LocalDate.of(2026, 10, 1), null, "bonus-3"
        );
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 10, 16));
        when(adjustmentRepository.findByProfileIdAndIdempotencyKey(PROFILE_ID, "bonus-3"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.create(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.BONUS, 4_000L, "2026-10", "bonus-3"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ключ операции уже использован");
        verify(adjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void reversingABonusNeedsFreeCapacityAndNeverRewritesTheOriginal() {
        stubLockedProfile();
        ContractorAccrualAdjustment original = entry(
                91L, ContractorAccrualAdjustmentKind.BONUS, ContractorAccrualBucket.BONUS,
                10_000L, LocalDate.of(2026, 10, 1), null, "bonus-4"
        );
        when(adjustmentRepository.findByIdAndProfileId(91L, PROFILE_ID)).thenReturn(Optional.of(original));
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .thenReturn(9_999L);

        assertThatThrownBy(() -> service.reverse(USER_ID, PROFILE_ID, 91L,
                new ContractorAccrualAdjustmentReversalRequest("Ошибка", "reverse-4")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("свободную сумму");
        verify(adjustmentRepository, never()).saveAndFlush(any());
        assertThat(original.getSignedAmountKopecks()).isEqualTo(10_000L);
    }

    @Test
    void reversingAnEarlierMonthsDeductionPostsPositiveDeltaThisMonth() {
        stubLockedProfile();
        LocalDate effectiveOn = LocalDate.of(2026, 11, 2);
        ContractorAccrualAdjustment original = entry(
                92L, ContractorAccrualAdjustmentKind.DEDUCTION, ContractorAccrualBucket.DEDUCTION,
                -4_000L, LocalDate.of(2026, 10, 1), null, "deduction-2"
        );
        when(adjustmentRepository.findByIdAndProfileId(92L, PROFILE_ID)).thenReturn(Optional.of(original));
        when(businessClock.today()).thenReturn(effectiveOn);
        when(businessClock.now()).thenReturn(effectiveOn.atStartOfDay());
        when(adjustmentRepository.saveAndFlush(any(ContractorAccrualAdjustment.class)))
                .thenAnswer(invocation -> savedWithId(invocation.getArgument(0), 93L));

        var response = service.reverse(USER_ID, PROFILE_ID, 92L,
                new ContractorAccrualAdjustmentReversalRequest("Исправление", "reverse-5"));

        ArgumentCaptor<ContractorAccrualAdjustment> reversal = ArgumentCaptor.forClass(
                ContractorAccrualAdjustment.class
        );
        verify(adjustmentRepository).saveAndFlush(reversal.capture());
        assertThat(reversal.getValue().getSignedAmountKopecks()).isEqualTo(4_000L);
        assertThat(reversal.getValue().getBucket()).isEqualTo(ContractorAccrualBucket.DEDUCTION);
        assertThat(reversal.getValue().getEffectiveOn()).isEqualTo(effectiveOn);
        assertThat(reversal.getValue().getReferenceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reversal.getValue().getReversalOf()).isSameAs(original);
        assertThat(response.reversalOfId()).isEqualTo(92L);
        assertThat(response.referenceMonth()).isEqualTo("2026-10");
        assertThat(response.signedAmountKopecks()).isEqualTo(4_000L);
        ArgumentCaptor<ContractorAccrualAdjustedEvent> published = ArgumentCaptor.forClass(
                ContractorAccrualAdjustedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().effectiveOn()).isEqualTo(effectiveOn);
        verifyNoInteractions(bonusTelegramNotificationService);
    }

    @Test
    void retryingAReversalReturnsItsExistingEntryWithoutPostingAgain() {
        stubLockedProfile();
        ContractorAccrualAdjustment original = entry(
                94L, ContractorAccrualAdjustmentKind.BONUS, ContractorAccrualBucket.BONUS,
                3_000L, LocalDate.of(2026, 10, 1), null, "bonus-5"
        );
        ContractorAccrualAdjustment reversal = entry(
                95L, ContractorAccrualAdjustmentKind.REVERSAL, ContractorAccrualBucket.BONUS,
                -3_000L, LocalDate.of(2026, 10, 1), original, "reverse-6"
        );
        when(adjustmentRepository.findByProfileIdAndIdempotencyKey(PROFILE_ID, "reverse-6"))
                .thenReturn(Optional.of(reversal));

        var response = service.reverse(USER_ID, PROFILE_ID, 94L,
                new ContractorAccrualAdjustmentReversalRequest("Основание", "reverse-6"));

        assertThat(response.id()).isEqualTo(95L);
        assertThat(response.reversalOfId()).isEqualTo(94L);
        verify(adjustmentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(businessAuditService, eventPublisher);
    }

    @Test
    void forbiddenTargetIsRejectedBeforeFinancialLocksOrProfileLookup() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"))
                .when(targetAccessPolicy).requireCanManageUser(USER_ID);

        assertThatThrownBy(() -> service.preview(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.BONUS, 1_000L, "2026-10", null
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Пользователь не найден");
        verifyNoInteractions(phaseService, profileRepository, adjustmentRepository);
    }

    @Test
    void previewSeparatesWorkBonusDeductionAndShowsCapacityAfterChange() {
        stubLockedProfile();
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 11, 1);
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 10, 16));
        when(rewardLedgerRepository.sumActiveByProfileIdAndPeriod(PROFILE_ID, from, to))
                .thenReturn(20_000L);
        when(adjustmentRepository.sumBucketInPeriod(PROFILE_ID, ContractorAccrualBucket.BONUS, from, to))
                .thenReturn(3_000L);
        when(adjustmentRepository.sumBucketInPeriod(PROFILE_ID, ContractorAccrualBucket.DEDUCTION, from, to))
                .thenReturn(-1_000L);
        when(taskCapacityService.ordinaryAvailable(profile, ContractorAllocationMode.LIVE))
                .thenReturn(7_000L);
        when(taskCapacityService.ordinaryAvailableAfterAccrualDelta(
                profile, ContractorAllocationMode.LIVE, -2_000L)).thenReturn(5_000L);
        when(accountingService.netReceived(profile, ContractorAllocationMode.LIVE))
                .thenReturn(8_000L);
        when(allocationRepository.sumOutstandingExposure(eq(PROFILE_ID),
                eq(ContractorAllocationMode.LIVE), anySet())).thenReturn(4_000L);
        profile.setManualTaskCommitmentKopecks(2_000L);

        var preview = service.preview(USER_ID, PROFILE_ID, request(
                ContractorAccrualAdjustmentKind.DEDUCTION, 2_000L, "2026-10", null
        ));

        assertThat(preview.workMonthKopecks()).isEqualTo(20_000L);
        assertThat(preview.bonusMonthKopecks()).isEqualTo(3_000L);
        assertThat(preview.deductionMonthKopecks()).isEqualTo(1_000L);
        assertThat(preview.accruedMonthBeforeKopecks()).isEqualTo(22_000L);
        assertThat(preview.accruedMonthAfterKopecks()).isEqualTo(20_000L);
        assertThat(preview.freeForNewPaymentsBeforeKopecks()).isEqualTo(7_000L);
        assertThat(preview.freeForNewPaymentsAfterKopecks()).isEqualTo(5_000L);
        assertThat(preview.paidTotalKopecks()).isEqualTo(8_000L);
        assertThat(preview.outstandingKopecks()).isEqualTo(4_000L);
        assertThat(preview.manualTaskCommitmentKopecks()).isEqualTo(2_000L);
    }

    @Test
    void historyPageReturnsAnOlderBoundedSliceWithMoreAvailable() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        var pageable = PageRequest.of(2, 50);
        var older = entry(101L, ContractorAccrualAdjustmentKind.DEDUCTION,
                ContractorAccrualBucket.DEDUCTION, -1_250L,
                LocalDate.of(2026, 8, 1), null, "older-adjustment");
        when(adjustmentRepository.findHistoryPage(PROFILE_ID, pageable))
                .thenReturn(new SliceImpl<>(List.of(older), pageable, true));

        var page = service.historyPage(USER_ID, PROFILE_ID, 2);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo(101L);
        assertThat(page.items().getFirst().signedAmountKopecks()).isEqualTo(-1_250L);
        assertThat(page.hasMore()).isTrue();
        verify(targetAccessPolicy).requireCanManageUser(USER_ID);
    }

    @Test
    void historyPageRejectsNegativePageBeforeRepositoryQuery() {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.historyPage(USER_ID, PROFILE_ID, -1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(adjustmentRepository, never()).findHistoryPage(any(), any());
    }

    private void stubLockedProfile() {
        when(phaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        when(profileRepository.findByIdForUpdate(PROFILE_ID)).thenReturn(Optional.of(profile));
    }

    private ContractorAccrualAdjustmentRequest request(
            ContractorAccrualAdjustmentKind kind, long amount, String referenceMonth, String key
    ) {
        return new ContractorAccrualAdjustmentRequest(kind, amount, "Основание", referenceMonth, null, key);
    }

    private ContractorAccrualAdjustment entry(
            long id, ContractorAccrualAdjustmentKind kind, ContractorAccrualBucket bucket,
            long amount, LocalDate referenceMonth, ContractorAccrualAdjustment reversalOf, String key
    ) {
        ContractorAccrualAdjustment entry = new ContractorAccrualAdjustment();
        entry.setId(id);
        entry.setProfile(profile);
        entry.setKind(kind);
        entry.setBucket(bucket);
        entry.setSignedAmountKopecks(amount);
        entry.setEffectiveOn(LocalDate.of(2026, 10, 16));
        entry.setReferenceMonth(referenceMonth);
        entry.setReason("Основание");
        entry.setCreatedBy("admin");
        entry.setCreatedAt(LocalDateTime.of(2026, 10, 16, 8, 30));
        entry.setReversalOf(reversalOf);
        entry.setIdempotencyKey(key);
        return entry;
    }

    private ContractorAccrualAdjustment savedWithId(ContractorAccrualAdjustment entry, long id) {
        entry.setId(id);
        return entry;
    }
}
