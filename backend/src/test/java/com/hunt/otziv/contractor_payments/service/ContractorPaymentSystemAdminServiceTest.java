package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentSystemAdminServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final LocalDate CUTOVER = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 10, 30);

    @Mock
    private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private ContractorCompletionCutoverStateService cutoverStateService;
    @Mock
    private ContractorCompletionRoutingReadinessService completionRoutingReadinessService;
    @Mock
    private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock
    private ContractorPaymentBusinessClock businessClock;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private BusinessAuditService businessAuditService;

    @InjectMocks
    private ContractorPaymentSystemAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(businessClock.today()).thenReturn(TODAY);
        lenient().when(businessClock.now()).thenReturn(NOW);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "owner@example.test",
                        "n/a",
                        java.util.List.of()
                )
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerActivationIrreversiblySetsCutoverAndKeepsClientRoutingOff() {
        ContractorPaymentRolloutState state = legacyState(4L);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, true));
        when(rolloutStateService.lockCurrent()).thenReturn(state);
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.empty());
        when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.of(CUTOVER));

        service.activate(activation(CUTOVER, 4L));

        assertThat(state.completionAccountingActive()).isTrue();
        assertThat(state.isRoutingRequested()).isFalse();
        assertThat(state.getAttributionStartDate()).isEqualTo(CUTOVER);
        assertThat(state.getActivatedAt()).isEqualTo(NOW);
        assertThat(state.getActivatedBy()).isEqualTo("owner@example.test");
        verify(appSettingService).setString(
                AppSettingService.CONTRACTOR_PAYMENTS_COMPLETION_ATTRIBUTION_START_DATE,
                CUTOVER.toString()
        );
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                true
        );
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        );
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        );
        verify(appSettingService).invalidateByPrefix("contractor-payments.");
        verify(accountingPhaseService).lockAndPromoteForLiveRoute();
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                "CONTRACTOR_PAYMENT_SYSTEM_ACTIVATED",
                "CONTRACTOR_PAYMENT_ROLLOUT",
                ContractorPaymentRolloutState.SINGLETON_ID,
                null,
                null,
                "LEGACY;routing=false",
                "COMPLETION;startDate=2026-08-01;routing=false",
                "Сверка выполнена"
        );
    }

    @Test
    void activatedAccountingIsReportedAsEffectiveLiveAccountingWithRoutingPaused() {
        when(rolloutStateService.currentSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                false,
                CUTOVER,
                7L
        ));
        when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.LIVE);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                true,
                false,
                false,
                true,
                true,
                true
        ));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.of(CUTOVER));

        var response = service.status();

        assertThat(response.mode()).isEqualTo("ROUTING_PAUSED");
        assertThat(response.systemEnabled()).isTrue();
        assertThat(response.legacyBehavior()).isFalse();
        assertThat(response.irreversible()).isTrue();
        assertThat(response.routingRequested()).isFalse();
        assertThat(response.completionAccountingEffective()).isTrue();
        assertThat(response.liveRoutingEffective()).isFalse();
        assertThat(response.completionBacklogReady()).isTrue();
        assertThat(response.activationBlockedReasons()).isEmpty();
    }

    @Test
    void activationRejectsWrongTypedConfirmationBeforeReadingFinancialState() {
        ContractorPaymentSystemActivationRequest request = new ContractorPaymentSystemActivationRequest(
                CUTOVER,
                "включить",
                "Сверка выполнена",
                0L
        );

        assertBadRequest(() -> service.activate(request));

        verifyNoInteractions(runtimeSwitch, rolloutStateService, accountingPhaseService, cutoverStateService);
    }

    @Test
    void activationRejectsMidMonthAndFutureBoundaries() {
        assertBadRequest(() -> service.activate(activation(LocalDate.of(2026, 8, 2), 0L)));
        assertBadRequest(() -> service.activate(activation(LocalDate.of(2026, 9, 1), 0L)));

        verifyNoInteractions(runtimeSwitch, rolloutStateService, accountingPhaseService, cutoverStateService);
    }

    @Test
    void activationRequiresRewardDeploymentMaster() {
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, false));

        assertConflict(() -> service.activate(activation(CUTOVER, 0L)));

        verify(rolloutStateService, never()).lockCurrent();
        verifyNoInteractions(accountingPhaseService, cutoverStateService);
    }

    @Test
    void activationRejectsStaleRevisionBeforeCutoverWrite() {
        ContractorPaymentRolloutState state = legacyState(9L);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, true));
        when(rolloutStateService.lockCurrent()).thenReturn(state);

        assertConflict(() -> service.activate(activation(CUTOVER, 8L)));

        verifyNoInteractions(accountingPhaseService, cutoverStateService, appSettingService);
    }

    @Test
    void repeatedActivationWithSameDateIsIdempotentEvenAfterRevisionChanged() {
        ContractorPaymentRolloutState state = completionState(false, 12L);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, true));
        when(rolloutStateService.lockCurrent()).thenReturn(state);

        service.activate(activation(CUTOVER, 1L));

        assertThat(state.completionAccountingActive()).isTrue();
        assertThat(state.getAttributionStartDate()).isEqualTo(CUTOVER);
        verifyNoInteractions(accountingPhaseService, cutoverStateService, appSettingService, businessAuditService);
    }

    @Test
    void repeatedActivationCannotMoveImmutableCutover() {
        ContractorPaymentRolloutState state = completionState(false, 12L);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, true));
        when(rolloutStateService.lockCurrent()).thenReturn(state);

        assertConflict(() -> service.activate(activation(LocalDate.of(2026, 7, 1), 12L)));

        verifyNoInteractions(accountingPhaseService, cutoverStateService, appSettingService);
    }

    @Test
    void enablingRoutingRequiresBothMastersMatchingCutoverAndCleanBacklog() {
        ContractorPaymentRolloutState state = completionState(false, 5L);
        when(rolloutStateService.lockCurrent()).thenReturn(state);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(true, true));
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.of(CUTOVER));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);

        service.updateRouting(routing(true, 5L));

        assertThat(state.completionAccountingActive()).isTrue();
        assertThat(state.isRoutingRequested()).isTrue();
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                true
        );
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                true
        );
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                "CONTRACTOR_PAYMENT_ROUTING_ENABLED",
                "CONTRACTOR_PAYMENT_ROLLOUT",
                ContractorPaymentRolloutState.SINGLETON_ID,
                null,
                null,
                "routing=false",
                "routing=true",
                "Окно запуска согласовано"
        );
    }

    @Test
    void enablingRoutingFailsClosedWithoutMastersOrReadiness() {
        ContractorPaymentRolloutState noMasterState = completionState(false, 1L);
        when(rolloutStateService.lockCurrent()).thenReturn(noMasterState);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(false, true));

        assertConflict(() -> service.updateRouting(routing(true, 1L)));

        ContractorPaymentRolloutState notReadyState = completionState(false, 2L);
        when(rolloutStateService.lockCurrent()).thenReturn(notReadyState);
        when(runtimeSwitch.status()).thenReturn(runtimeStatus(true, true));
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.of(CUTOVER));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(false);

        assertConflict(() -> service.updateRouting(routing(true, 2L)));

        assertThat(noMasterState.isRoutingRequested()).isFalse();
        assertThat(notReadyState.isRoutingRequested()).isFalse();
        verify(appSettingService, never()).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                true
        );
    }

    @Test
    void routingCommandRequiresTypedPhraseAndCurrentRevision() {
        ContractorPaymentRolloutState state = completionState(false, 6L);
        when(rolloutStateService.lockCurrent()).thenReturn(state);

        assertBadRequest(() -> service.updateRouting(new ContractorPaymentRoutingCommandRequest(
                true,
                ContractorPaymentSystemAdminService.PAUSE_ROUTING_CONFIRMATION,
                "Окно запуска согласовано",
                6L
        )));
        assertConflict(() -> service.updateRouting(routing(true, 5L)));

        verifyNoInteractions(runtimeSwitch, cutoverStateService, completionRoutingReadinessService, appSettingService);
    }

    @Test
    void routingPauseIsReversibleAndNeverDeactivatesCompletionAccounting() {
        ContractorPaymentRolloutState state = completionState(true, 8L);
        when(rolloutStateService.lockCurrent()).thenReturn(state);

        service.updateRouting(routing(false, 8L));

        assertThat(state.completionAccountingActive()).isTrue();
        assertThat(state.getAttributionStartDate()).isEqualTo(CUTOVER);
        assertThat(state.isRoutingRequested()).isFalse();
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        );
        verify(appSettingService).setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        );
        verify(runtimeSwitch, never()).status();
        verify(completionRoutingReadinessService, never()).readyForLiveRouting();

        when(runtimeSwitch.status()).thenReturn(runtimeStatus(true, true));
        when(cutoverStateService.lockedStartDate()).thenReturn(Optional.of(CUTOVER));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);

        service.updateRouting(routing(true, 8L));

        assertThat(state.completionAccountingActive()).isTrue();
        assertThat(state.isRoutingRequested()).isTrue();
        verify(runtimeSwitch).status();
        verify(completionRoutingReadinessService).readyForLiveRouting();
    }

    private ContractorPaymentSystemActivationRequest activation(LocalDate date, long revision) {
        return new ContractorPaymentSystemActivationRequest(
                date,
                ContractorPaymentSystemAdminService.ACTIVATE_CONFIRMATION,
                "Сверка выполнена",
                revision
        );
    }

    private ContractorPaymentRoutingCommandRequest routing(boolean enabled, long revision) {
        return new ContractorPaymentRoutingCommandRequest(
                enabled,
                enabled
                        ? ContractorPaymentSystemAdminService.ENABLE_ROUTING_CONFIRMATION
                        : ContractorPaymentSystemAdminService.PAUSE_ROUTING_CONFIRMATION,
                "Окно запуска согласовано",
                revision
        );
    }

    private ContractorPaymentRolloutState legacyState(long revision) {
        return state(ContractorPaymentAccountingAuthority.LEGACY, false, null, revision);
    }

    private ContractorPaymentRolloutState completionState(boolean routingRequested, long revision) {
        return state(ContractorPaymentAccountingAuthority.COMPLETION, routingRequested, CUTOVER, revision);
    }

    private ContractorPaymentRolloutState state(
            ContractorPaymentAccountingAuthority authority,
            boolean routingRequested,
            LocalDate cutover,
            long revision
    ) {
        ContractorPaymentRolloutState state = new ContractorPaymentRolloutState();
        ReflectionTestUtils.setField(state, "id", ContractorPaymentRolloutState.SINGLETON_ID);
        ReflectionTestUtils.setField(state, "accountingAuthority", authority);
        ReflectionTestUtils.setField(state, "routingRequested", routingRequested);
        ReflectionTestUtils.setField(state, "attributionStartDate", cutover);
        ReflectionTestUtils.setField(state, "activatedAt", cutover == null ? null : NOW.minusDays(1));
        ReflectionTestUtils.setField(state, "activatedBy", cutover == null ? null : "owner@example.test");
        ReflectionTestUtils.setField(state, "updatedAt", NOW.minusDays(1));
        ReflectionTestUtils.setField(state, "updatedBy", "owner@example.test");
        ReflectionTestUtils.setField(state, "rowVersion", revision);
        return state;
    }

    private ContractorPaymentRolloutStateService.Snapshot snapshot(
            ContractorPaymentAccountingAuthority authority,
            boolean routingRequested,
            LocalDate cutover,
            long revision
    ) {
        return new ContractorPaymentRolloutStateService.Snapshot(
                authority,
                routingRequested,
                cutover,
                cutover == null ? null : NOW.minusDays(1),
                cutover == null ? null : "owner@example.test",
                NOW,
                "owner@example.test",
                revision
        );
    }

    private ContractorPaymentRuntimeSwitch.RuntimeStatus runtimeStatus(
            boolean liveRoutingMaster,
            boolean rewardAttributionMaster
    ) {
        return new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                liveRoutingMaster,
                false,
                false,
                rewardAttributionMaster,
                false,
                false
        );
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
