package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Central fail-closed rollout gate for every contractor-payment write path.
 *
 * <p>A database flag can only enable a feature when the corresponding
 * deployment-level hard master was explicitly enabled as well. This prevents
 * an accidental app_settings edit from changing production payment routing.
 * Disabling either side stops creation of new LIVE decisions; already-created
 * LIVE allocations remain authoritative and are handled independently.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractorPaymentRuntimeSwitch {

    private final AppSettingService appSettingService;
    private final ContractorCompletionCutoverStateService cutoverStateService;
    private final ContractorCompletionRoutingReadinessService completionRoutingReadinessService;
    private final ContractorPaymentRolloutStateService rolloutStateService;

    @Value("${otziv.contractor-payments.live-routing-master-enabled:false}")
    private boolean liveRoutingMasterEnabled;

    @Value("${otziv.contractor-payments.reward-attribution-master-enabled:false}")
    private boolean rewardAttributionMasterEnabled;

    public boolean liveRoutingEnabled() {
        return liveRoutingMasterEnabled
                && safeRoutingRequested()
                && safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED)
                && rewardAttributionConfigurationReady()
                && safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED)
                && safeCompletionRoutingReadiness()
                && routingConfigurationBlockers().isEmpty();
    }

    public boolean rewardAttributionLiveEnabled() {
        // Accounting authority is deliberately independent from mutable
        // deployment/app-setting gates once cutover happened. Returning to
        // the legacy writer after completion sources exist would double count
        // the same work. A state-read failure therefore propagates and blocks
        // the business mutation instead of silently selecting legacy.
        return rolloutStateService.freshSnapshot().completionAccountingActive();
    }

    /** Explicit accounting boundary signed off together with imported opening balances. */
    public Optional<LocalDate> completionAttributionStartDate() {
        ContractorPaymentRolloutStateService.Snapshot rollout = rolloutStateService.freshSnapshot();
        if (!rollout.completionAccountingActive() || rollout.attributionStartDate() == null) {
            return Optional.empty();
        }
        return verifiedCompletionAttributionStartDate(rollout.attributionStartDate());
    }

    private Optional<LocalDate> verifiedCompletionAttributionStartDate(LocalDate configuredStartDate) {
        if (configuredStartDate == null) {
            return Optional.empty();
        }
        try {
            return cutoverStateService.lockOrVerify(configuredStartDate);
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor completion attribution boundary read failed: failure={}",
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }
    }

    public RuntimeStatus status() {
        boolean routingDatabaseEnabled = safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED);
        boolean attributionDatabaseEnabled = safeFresh(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED
        );
        Optional<ContractorPaymentRolloutStateService.Snapshot> rollout = safeRolloutSnapshot();
        boolean attributionEnabled = rollout
                .filter(ContractorPaymentRolloutStateService.Snapshot::completionAccountingActive)
                .map(ContractorPaymentRolloutStateService.Snapshot::attributionStartDate)
                .flatMap(this::verifiedCompletionAttributionStartDate)
                .isPresent();
        boolean readinessConfirmed = safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED);
        boolean routingEnabled = liveRoutingMasterEnabled
                && rollout.map(ContractorPaymentRolloutStateService.Snapshot::routingRequested).orElse(false)
                && routingDatabaseEnabled
                && rewardAttributionMasterEnabled
                && attributionDatabaseEnabled
                && attributionEnabled
                && readinessConfirmed
                && safeCompletionRoutingReadiness()
                && routingConfigurationBlockers().isEmpty();
        return new RuntimeStatus(
                liveRoutingMasterEnabled,
                routingDatabaseEnabled,
                routingEnabled,
                rewardAttributionMasterEnabled,
                attributionDatabaseEnabled,
                attributionEnabled
        );
    }

    private boolean rewardAttributionConfigurationReady() {
        if (!rewardAttributionMasterEnabled
                || !safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED)) {
            return false;
        }
        return safeRolloutSnapshot()
                .filter(ContractorPaymentRolloutStateService.Snapshot::completionAccountingActive)
                .map(ContractorPaymentRolloutStateService.Snapshot::attributionStartDate)
                .flatMap(this::verifiedCompletionAttributionStartDate)
                .isPresent();
    }

    private boolean safeRoutingRequested() {
        return safeRolloutSnapshot()
                .filter(ContractorPaymentRolloutStateService.Snapshot::completionAccountingActive)
                .map(ContractorPaymentRolloutStateService.Snapshot::routingRequested)
                .orElse(false);
    }

    private Optional<ContractorPaymentRolloutStateService.Snapshot> safeRolloutSnapshot() {
        try {
            return Optional.of(rolloutStateService.freshSnapshot());
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor payment rollout state read failed: failure={}",
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }
    }

    private boolean safeFresh(String key) {
        try {
            return appSettingService.getBooleanFreshFailClosed(key, false);
        } catch (RuntimeException exception) {
            // A routing gate must never fail open. Avoid including database
            // messages because they can contain connection details.
            log.error(
                    "Contractor payment runtime switch read failed: key={}, failure={}",
                    key,
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    /** Fresh fail-closed deployment/runtime prerequisites for client-facing routes. */
    public List<String> routingConfigurationBlockers() {
        List<String> blockers = new ArrayList<>();
        if (!safeFresh(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED)) {
            blockers.add("Подготовка неизменяемого снимка маршрута выключена");
        }
        if (!safeFresh(AppSettingService.PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED)) {
            blockers.add("Создание платежных ссылок выключено");
        }
        String instructionSource = safeStringFresh(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                ""
        );
        if (!"TBANK_LINK".equals(instructionSource.trim().toUpperCase(Locale.ROOT))) {
            blockers.add("Клиентские сообщения не настроены на платежную ссылку");
        }
        if (!safeFresh(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED)
                || !safeFresh(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED)) {
            blockers.add("Боевая отправка клиентских сообщений выключена");
        }
        return List.copyOf(blockers);
    }

    private String safeStringFresh(String key, String fallback) {
        try {
            return Optional.ofNullable(appSettingService.getStringFresh(key, fallback)).orElse(fallback);
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor payment runtime string read failed: key={}, failure={}",
                    key,
                    exception.getClass().getSimpleName()
            );
            return fallback;
        }
    }

    private boolean safeCompletionRoutingReadiness() {
        try {
            return completionRoutingReadinessService.readyForLiveRouting();
        } catch (RuntimeException exception) {
            log.error(
                    "Contractor completion routing readiness failed: failure={}",
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    public record RuntimeStatus(
            boolean liveRoutingMasterEnabled,
            boolean liveRoutingDatabaseEnabled,
            boolean liveRoutingEnabled,
            boolean rewardAttributionMasterEnabled,
            boolean rewardAttributionDatabaseEnabled,
            boolean rewardAttributionLiveEnabled
    ) {
    }
}
