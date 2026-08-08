package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAllocationJournalItemResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSummaryResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ContractorPaymentVisibilityServiceTest {

    private final ContractorPaymentProfileRepository profileRepository =
            mock(ContractorPaymentProfileRepository.class);
    private final ContractorPaymentAllocationRepository allocationRepository =
            mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentAllocationEventRepository eventRepository =
            mock(ContractorPaymentAllocationEventRepository.class);
    private final ContractorRewardLedgerService ledgerService = mock(ContractorRewardLedgerService.class);
    private final ContractorPaymentAccountingService accountingService =
            mock(ContractorPaymentAccountingService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final ContractorPaymentRuntimeSwitch runtimeSwitch = mock(ContractorPaymentRuntimeSwitch.class);
    private final ContractorPaymentAccountingPhaseService accountingPhaseService =
            mock(ContractorPaymentAccountingPhaseService.class);
    private final ContractorPaymentProfileService profileService = mock(ContractorPaymentProfileService.class);
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy =
            mock(ContractorPaymentTargetAccessPolicy.class);
    private final ContractorPaymentVisibilityService service = new ContractorPaymentVisibilityService(
            profileRepository,
            allocationRepository,
            eventRepository,
            ledgerService,
            accountingService,
            runtimeSwitch,
            accountingPhaseService,
            profileService,
            userRepository,
            appSettingService,
            targetAccessPolicy
    );

    @BeforeEach
    void useEffectiveShadowMode() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(true);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, true, false, false, false, false
        ));
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.SHADOW);
    }

    @Test
    void ownSummaryKeepsEveryPermanentHistoricalProfileAfterRoleRemoval() {
        User user = user(17L, "worker", "ROLE_WORKER");
        ContractorPaymentProfile specialist = profile(31L, user, ContractorRole.SPECIALIST);
        ContractorPaymentProfile staleManager = profile(32L, user, ContractorRole.MANAGER);
        when(userRepository.findByUsername("worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(17L)).thenReturn(List.of(specialist, staleManager));
        when(ledgerService.totalAccrued(specialist)).thenReturn(10_000L);
        when(ledgerService.accruedInPeriod(any(), any(), any())).thenReturn(5_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(2_000L, 1_000L, 500L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.LIVE)).thenReturn(3_000L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.SHADOW)).thenReturn(4_000L);
        when(accountingService.returned(specialist, ContractorAllocationMode.SHADOW)).thenReturn(500L);
        when(accountingService.confirmedGrossInPeriod(any(), any(), any(), any())).thenReturn(700L, 800L);
        when(accountingService.returnedInPeriod(any(), any(), any(), any())).thenReturn(100L);
        when(accountingService.closedWithoutPaymentInPeriod(any(), any(), any(), any())).thenReturn(900L);
        when(accountingService.closedWithoutPayment(specialist, ContractorAllocationMode.SHADOW))
                .thenReturn(1_500L);

        List<ContractorPaymentSummaryResponse> response = service.ownSummary(
                new UsernamePasswordAuthenticationToken("worker", "", List.of())
        );

        assertEquals(2, response.size());
        ContractorPaymentSummaryResponse summary = response.getFirst();
        assertEquals(ContractorRole.SPECIALIST, summary.role());
        assertEquals(17L, summary.userId());
        assertEquals(2_000L, summary.reservedKopecks());
        assertEquals(1_000L, summary.clientReportedKopecks());
        assertEquals(500L, summary.partiallyConfirmedOutstandingKopecks());
        assertEquals(4_000L, summary.grossConfirmedTotalKopecks());
        assertEquals(500L, summary.returnedTotalKopecks());
        assertEquals(3_500L, summary.netReceivedTotalKopecks());
        assertEquals(3_000L, summary.availableKopecks());
        assertEquals(900L, summary.closedWithoutPaymentMonthKopecks());
        assertEquals(1_500L, summary.closedWithoutPaymentTotalKopecks());
        assertEquals(0L, summary.creditKopecks());
        assertTrue(summary.shadowMode());
        var creationOrder = inOrder(profileService, profileRepository);
        creationOrder.verify(profileService).ensureForUser(17L);
        creationOrder.verify(profileRepository).findAllByUserIdForUpdate(17L);
    }

    @Test
    void ownSummaryMarksMidMonthTrackingAsPartialCoverage() {
        User user = user(23L, "coverage-worker", "ROLE_WORKER");
        LocalDate monthStart = LocalDate.now(ZoneId.of("Asia/Irkutsk")).withDayOfMonth(1);
        ContractorPaymentProfile partial = profile(38L, user, ContractorRole.SPECIALIST);
        partial.setTrackingStartedAt(monthStart.atStartOfDay().plusDays(1));
        ContractorPaymentProfile complete = profile(39L, user, ContractorRole.MANAGER);
        complete.setTrackingStartedAt(monthStart.atStartOfDay());
        when(userRepository.findByUsername("coverage-worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(23L)).thenReturn(List.of(partial, complete));

        List<ContractorPaymentSummaryResponse> response = service.ownSummary(
                new UsernamePasswordAuthenticationToken("coverage-worker", "", List.of())
        );

        assertEquals(partial.getTrackingStartedAt(), response.get(0).trackingStartedAt());
        assertFalse(response.get(0).currentMonthCoverageComplete());
        assertEquals(complete.getTrackingStartedAt(), response.get(1).trackingStartedAt());
        assertTrue(response.get(1).currentMonthCoverageComplete());
    }

    @Test
    void ownSummaryExposesPersonalRoutingFlagsWithoutHidingFinancialStatistics() {
        User user = user(24L, "routing-disabled-worker", "ROLE_WORKER");
        ContractorPaymentProfile specialist = profile(40L, user, ContractorRole.SPECIALIST);
        specialist.setEnabled(true);
        specialist.setLiveEnabled(false);
        when(userRepository.findByUsername("routing-disabled-worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(24L)).thenReturn(List.of(specialist));
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                true, true, true, true, true, true
        ));
        when(ledgerService.totalAccrued(specialist)).thenReturn(10_000L);
        when(ledgerService.accruedInPeriod(any(), any(), any())).thenReturn(6_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(2_000L, 0L, 0L);

        ContractorPaymentSummaryResponse summary = service.ownSummary(
                new UsernamePasswordAuthenticationToken("routing-disabled-worker", "", List.of())
        ).getFirst();

        assertTrue(summary.profileEnabled());
        assertFalse(summary.liveEnabled());
        assertTrue(summary.liveRouting());
        assertEquals(6_000L, summary.accruedMonthKopecks());
        assertEquals(10_000L, summary.accruedTotalKopecks());
        assertEquals(2_000L, summary.reservedKopecks());
        assertEquals(8_000L, summary.availableKopecks());
    }

    @Test
    void journalReturnsAllocationHistoryAndCapsRequestedPageSize() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(41L);
        allocation.setAttemptNo(2);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(51L);
        allocation.setOrderId(61L);
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientUserId(17L);
        allocation.setRecipientNameSnapshot("Иванов Иван Иванович");
        allocation.setAmountKopecks(12_000L);
        allocation.setConfirmedKopecks(12_000L);
        allocation.setReturnedKopecks(2_000L);
        allocation.setStatus(ContractorAllocationStatus.PARTIALLY_RETURNED);
        allocation.setCreatedAt(LocalDateTime.of(2026, 8, 7, 10, 0));
        allocation.setUpdatedAt(LocalDateTime.of(2026, 8, 7, 11, 0));

        ContractorPaymentAllocationEvent event = new ContractorPaymentAllocationEvent();
        event.setId(71L);
        event.setAllocation(allocation);
        event.setEventType(ContractorAllocationEventType.RETURNED);
        event.setAmountKopecks(2_000L);
        event.setEffectiveAt(LocalDateTime.of(2026, 8, 7, 10, 55));
        event.setObservedAt(LocalDateTime.of(2026, 8, 7, 11, 0));

        when(allocationRepository.findJournal(
                17L,
                ContractorAllocationStatus.PARTIALLY_RETURNED,
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                51L,
                false,
                PageRequest.of(0, 100)
        )).thenReturn(new PageImpl<>(List.of(allocation), PageRequest.of(0, 100), 1));
        when(eventRepository.findAllByAllocationIdInOrderByEffectiveAtAscIdAsc(List.of(41L)))
                .thenReturn(List.of(event));

        ContractorPaymentAllocationJournalItemResponse item = service.journal(
                17L,
                ContractorAllocationStatus.PARTIALLY_RETURNED,
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                51L,
                -2,
                500
        ).getContent().getFirst();

        assertEquals(41L, item.id());
        assertEquals(2, item.attemptNo());
        assertEquals(2_000L, item.returnedKopecks());
        assertEquals(1, item.events().size());
        assertEquals(ContractorAllocationEventType.RETURNED, item.events().getFirst().eventType());
    }

    @Test
    void broadJournalPassesRestrictedOwnerFilterToRepository() {
        when(targetAccessPolicy.excludePrivilegedTargetsFromJournal()).thenReturn(true);
        when(allocationRepository.findJournal(
                null, null, null, null, null, true, PageRequest.of(0, 25)
        )).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        service.journal(null, null, null, null, null, 0, 25);

        verify(allocationRepository).findJournal(
                null, null, null, null, null, true, PageRequest.of(0, 25)
        );
    }

    @Test
    void formerContractorWithoutCurrentRoleStillSeesHistoricalProfile() {
        User user = user(22L, "former-worker", "ROLE_OPERATOR");
        ContractorPaymentProfile historical = profile(37L, user, ContractorRole.SPECIALIST);
        when(userRepository.findByUsername("former-worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(22L)).thenReturn(List.of(historical));

        List<ContractorPaymentSummaryResponse> response = service.ownSummary(
                new UsernamePasswordAuthenticationToken("former-worker", "", List.of())
        );

        assertEquals(1, response.size());
        assertEquals(ContractorRole.SPECIALIST, response.getFirst().role());
        verify(profileService).ensureForUser(22L);
    }

    @Test
    void reservationsCannotTurnIntoCreditAndOnlyNetOverpaymentCreatesIt() {
        User user = user(18L, "manager", "ROLE_MANAGER");
        ContractorPaymentProfile manager = profile(33L, user, ContractorRole.MANAGER);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(18L)).thenReturn(List.of(manager));
        when(ledgerService.totalAccrued(manager)).thenReturn(1_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(800L, 0L, 0L);
        when(accountingService.confirmedGross(manager, ContractorAllocationMode.SHADOW)).thenReturn(500L);

        ContractorPaymentSummaryResponse reserved = service.ownSummary(
                new UsernamePasswordAuthenticationToken("manager", "", List.of())
        ).getFirst();

        assertEquals(0L, reserved.availableKopecks());
        assertEquals(0L, reserved.creditKopecks());

        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet())).thenReturn(0L, 0L, 0L);
        when(accountingService.confirmedGross(manager, ContractorAllocationMode.SHADOW)).thenReturn(1_500L);

        ContractorPaymentSummaryResponse overpaid = service.ownSummary(
                new UsernamePasswordAuthenticationToken("manager", "", List.of())
        ).getFirst();

        assertEquals(0L, overpaid.availableKopecks());
        assertEquals(500L, overpaid.creditKopecks());
    }

    @Test
    void emergencyCreationOffDoesNotHideExistingLiveAccounting() {
        User user = user(19L, "worker-live", "ROLE_WORKER");
        ContractorPaymentProfile specialist = profile(34L, user, ContractorRole.SPECIALIST);
        when(userRepository.findByUsername("worker-live")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(19L)).thenReturn(List.of(specialist));
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.LIVE);
        when(ledgerService.totalAccrued(specialist)).thenReturn(10_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(2_000L, 1_000L, 0L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.LIVE)).thenReturn(4_000L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.SHADOW)).thenReturn(99_000L);
        when(accountingService.returned(specialist, ContractorAllocationMode.LIVE)).thenReturn(500L);

        ContractorPaymentSummaryResponse summary = service.ownSummary(
                new UsernamePasswordAuthenticationToken("worker-live", "", List.of())
        ).getFirst();

        assertEquals(2_000L, summary.reservedKopecks());
        assertEquals(500L, summary.returnedTotalKopecks());
        assertEquals(3_500L, summary.availableKopecks());
    }

    @Test
    void latePaymentPlusExistingReservationIsVisibleAsExposureOverrunAndBlocksMoreRoutes() {
        User user = user(20L, "late-worker", "ROLE_WORKER");
        ContractorPaymentProfile specialist = profile(35L, user, ContractorRole.SPECIALIST);
        when(userRepository.findByUsername("late-worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(20L)).thenReturn(List.of(specialist));
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.LIVE);
        when(ledgerService.totalAccrued(specialist)).thenReturn(1_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(1_000L, 0L, 0L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.LIVE)).thenReturn(1_000L);

        ContractorPaymentSummaryResponse summary = service.ownSummary(
                new UsernamePasswordAuthenticationToken("late-worker", "", List.of())
        ).getFirst();

        assertEquals(0L, summary.availableKopecks());
        assertEquals(0L, summary.creditKopecks());
        assertEquals(1_000L, summary.exposureOverrunKopecks());
    }

    @Test
    void returnOfPriorMonthConfirmationKeepsCurrentMonthNetNegative() {
        User user = user(21L, "return-worker", "ROLE_WORKER");
        ContractorPaymentProfile specialist = profile(36L, user, ContractorRole.SPECIALIST);
        when(userRepository.findByUsername("return-worker")).thenReturn(Optional.of(user));
        when(profileRepository.findAllByUserIdForUpdate(21L)).thenReturn(List.of(specialist));
        when(ledgerService.totalAccrued(specialist)).thenReturn(10_000L);
        when(allocationRepository.sumOutstandingExposure(any(), any(), anySet()))
                .thenReturn(0L, 0L, 0L);
        when(accountingService.confirmedGross(specialist, ContractorAllocationMode.SHADOW))
                .thenReturn(5_000L);
        when(accountingService.returned(specialist, ContractorAllocationMode.SHADOW))
                .thenReturn(2_000L);
        when(accountingService.confirmedGrossInPeriod(any(), any(), any(), any()))
                .thenReturn(0L);
        when(accountingService.returnedInPeriod(any(), any(), any(), any()))
                .thenReturn(2_000L);

        ContractorPaymentSummaryResponse summary = service.ownSummary(
                new UsernamePasswordAuthenticationToken("return-worker", "", List.of())
        ).getFirst();

        assertEquals(0L, summary.grossConfirmedMonthKopecks());
        assertEquals(2_000L, summary.returnedMonthKopecks());
        assertEquals(-2_000L, summary.netReceivedMonthKopecks());
        assertEquals(3_000L, summary.netReceivedTotalKopecks());
    }

    private User user(Long id, String username, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRoles(List.of(role));
        return user;
    }

    private ContractorPaymentProfile profile(Long id, User user, ContractorRole role) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setRole(role);
        return profile;
    }
}
