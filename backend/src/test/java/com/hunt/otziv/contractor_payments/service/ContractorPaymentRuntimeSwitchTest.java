package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentRuntimeSwitchTest {

    private static final LocalDate CUTOVER = LocalDate.of(2026, 8, 1);

    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ContractorCompletionCutoverStateService cutoverStateService;
    @Mock
    private ContractorCompletionRoutingReadinessService completionRoutingReadinessService;
    @Mock
    private ContractorPaymentRolloutStateService rolloutStateService;

    private ContractorPaymentRuntimeSwitch runtimeSwitch;

    @BeforeEach
    void setUp() {
        runtimeSwitch = new ContractorPaymentRuntimeSwitch(
                appSettingService,
                cutoverStateService,
                completionRoutingReadinessService,
                rolloutStateService
        );
        masters(false, false);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getStringFresh(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                ""
        )).thenReturn("TBANK_LINK");
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED,
                false
        )).thenReturn(true);
    }

    @Test
    void legacyAuthorityIgnoresEnabledDatabaseFlagsAndKeepsLegacyWriter() {
        masters(true, true);
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.LEGACY,
                true,
                null
        ));
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        )).thenReturn(true);

        assertThat(runtimeSwitch.rewardAttributionLiveEnabled()).isFalse();
        assertThat(runtimeSwitch.liveRoutingEnabled()).isFalse();
        verify(cutoverStateService, never()).lockOrVerify(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completionAuthorityNeverFallsBackToLegacyWhenMutableGatesAreOff() {
        masters(false, false);
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                false,
                CUTOVER
        ));

        assertThat(runtimeSwitch.rewardAttributionLiveEnabled()).isTrue();
        verify(appSettingService, never()).getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                false
        );
    }

    @Test
    void rolloutStateReadFailurePropagatesInsteadOfSelectingLegacyWriter() {
        IllegalStateException failure = new IllegalStateException("synthetic rollout read failure");
        when(rolloutStateService.freshSnapshot()).thenThrow(failure);

        assertThatThrownBy(runtimeSwitch::rewardAttributionLiveEnabled).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(MissingLiveGate.class)
    void liveRoutingRequiresEveryDurableDeploymentDatabaseAndReadinessGate(MissingLiveGate missing) {
        masters(true, true);
        lenient().when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                CUTOVER
        ));
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                false
        )).thenReturn(true);
        lenient().when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        )).thenReturn(true);
        lenient().when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.of(CUTOVER));
        lenient().when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);

        switch (missing) {
            case ROUTING_REQUEST -> when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                    ContractorPaymentAccountingAuthority.COMPLETION,
                    false,
                    CUTOVER
            ));
            case ROUTING_MASTER -> ReflectionTestUtils.setField(
                    runtimeSwitch,
                    "liveRoutingMasterEnabled",
                    false
            );
            case ATTRIBUTION_MASTER -> ReflectionTestUtils.setField(
                    runtimeSwitch,
                    "rewardAttributionMasterEnabled",
                    false
            );
            case ROUTING_DATABASE -> when(appSettingService.getBooleanFreshFailClosed(
                    AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                    false
            )).thenReturn(false);
            case ATTRIBUTION_DATABASE -> when(appSettingService.getBooleanFreshFailClosed(
                    AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                    false
            )).thenReturn(false);
            case CUTOVER -> when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.empty());
            case READINESS_CONFIRMATION -> when(appSettingService.getBooleanFreshFailClosed(
                    AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                    false
            )).thenReturn(false);
            case READINESS_BACKLOG -> when(completionRoutingReadinessService.readyForLiveRouting())
                    .thenReturn(false);
        }

        assertThat(runtimeSwitch.liveRoutingEnabled()).isFalse();
    }

    @Test
    void liveRoutingIsEnabledOnlyWhenEveryGateIsSatisfied() {
        masters(true, true);
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                CUTOVER
        ));
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        )).thenReturn(true);
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                false
        )).thenReturn(true);
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        )).thenReturn(true);
        when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.of(CUTOVER));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);

        assertThat(runtimeSwitch.liveRoutingEnabled()).isTrue();
    }

    @Test
    void clientFacingRoutingFailsClosedWhenImmutableSnapshotOrMessageRouteIsNotReady() {
        masters(true, true);
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                CUTOVER
        ));
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        )).thenReturn(true);
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                false
        )).thenReturn(true);
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED,
                false
        )).thenReturn(true);
        when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.of(CUTOVER));
        when(completionRoutingReadinessService.readyForLiveRouting()).thenReturn(true);
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                false
        )).thenReturn(false);
        when(appSettingService.getStringFresh(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                ""
        )).thenReturn("MANAGER_TEXT");

        assertThat(runtimeSwitch.liveRoutingEnabled()).isFalse();
        assertThat(runtimeSwitch.routingConfigurationBlockers())
                .contains(
                        "Подготовка неизменяемого снимка маршрута выключена",
                        "Клиентские сообщения не настроены на платежную ссылку"
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"TBANK_LINK", "BANK_LINK", "TOCHKA_LINK"})
    void clientFacingRoutingAcceptsEveryBankLinkAlias(String source) {
        when(appSettingService.getStringFresh(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                ""
        )).thenReturn(source);

        assertThat(runtimeSwitch.routingConfigurationBlockers())
                .doesNotContain("Клиентские сообщения не настроены на платежную ссылку");
    }

    @Test
    void immutableCutoverMismatchBlocksCompletionDateWithoutReactivatingLegacy() {
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                false,
                CUTOVER
        ));
        when(cutoverStateService.lockOrVerify(CUTOVER)).thenReturn(Optional.empty());

        assertThat(runtimeSwitch.rewardAttributionLiveEnabled()).isTrue();
        assertThat(runtimeSwitch.completionAttributionStartDate()).isEmpty();
    }

    @Test
    void databaseReadFailureFailsLiveRoutingClosedButKeepsCompletionAuthority() {
        masters(true, true);
        when(rolloutStateService.freshSnapshot()).thenReturn(snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                CUTOVER
        ));
        when(appSettingService.getBooleanFreshFailClosed(
                AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED,
                false
        )).thenThrow(new IllegalStateException("synthetic database failure"));

        assertThat(runtimeSwitch.liveRoutingEnabled()).isFalse();
        assertThat(runtimeSwitch.rewardAttributionLiveEnabled()).isTrue();
    }

    private ContractorPaymentRolloutStateService.Snapshot snapshot(
            ContractorPaymentAccountingAuthority authority,
            boolean routingRequested,
            LocalDate startDate
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        return new ContractorPaymentRolloutStateService.Snapshot(
                authority,
                routingRequested,
                startDate,
                authority == ContractorPaymentAccountingAuthority.COMPLETION ? now.minusDays(1) : null,
                authority == ContractorPaymentAccountingAuthority.COMPLETION ? "owner" : null,
                now,
                "owner",
                3L
        );
    }

    private void masters(boolean routing, boolean attribution) {
        ReflectionTestUtils.setField(runtimeSwitch, "liveRoutingMasterEnabled", routing);
        ReflectionTestUtils.setField(runtimeSwitch, "rewardAttributionMasterEnabled", attribution);
    }

    private enum MissingLiveGate {
        ROUTING_REQUEST,
        ROUTING_MASTER,
        ATTRIBUTION_MASTER,
        ROUTING_DATABASE,
        ATTRIBUTION_DATABASE,
        CUTOVER,
        READINESS_CONFIRMATION,
        READINESS_BACKLOG
    }
}
