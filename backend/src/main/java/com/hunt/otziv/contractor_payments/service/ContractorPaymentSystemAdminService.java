package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemStatusResponse;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** OWNER workflow for one-way accounting activation and reversible route pause. */
@Service
@RequiredArgsConstructor
public class ContractorPaymentSystemAdminService {

    public static final String ACTIVATE_CONFIRMATION = "ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ";
    public static final String ENABLE_ROUTING_CONFIRMATION = "ВКЛЮЧИТЬ РЕКВИЗИТЫ";
    public static final String PAUSE_ROUTING_CONFIRMATION = "ПРИОСТАНОВИТЬ РЕКВИЗИТЫ";
    private static final String SETTINGS_PREFIX = "contractor-payments.";

    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorPaymentAccountingPhaseService accountingPhaseService;
    private final ContractorCompletionCutoverStateService cutoverStateService;
    private final ContractorCompletionRoutingReadinessService completionRoutingReadinessService;
    private final ContractorCompletionCutoverPreflightService cutoverPreflightService;
    private final ContractorPaymentRuntimeSwitch runtimeSwitch;
    private final ContractorPaymentBusinessClock businessClock;
    private final AppSettingService appSettingService;
    private final BusinessAuditService businessAuditService;

    @Transactional(readOnly = true)
    public ContractorPaymentSystemStatusResponse status() {
        ContractorPaymentRolloutStateService.Snapshot rollout = rolloutStateService.currentSnapshot();
        ContractorAllocationMode accountingPhase = accountingPhaseService.current();
        ContractorPaymentRuntimeSwitch.RuntimeStatus runtime = runtimeSwitch.status();
        boolean backlogReady = safeBacklogReady();
        LocalDate lockedCutover = cutoverStateService.lockedStartDate().orElse(null);
        boolean cutoverConsistent = rollout.legacyAccountingActive()
                ? lockedCutover == null
                : rollout.attributionStartDate() != null
                        && Objects.equals(rollout.attributionStartDate(), lockedCutover);
        boolean completionEffective = rollout.completionAccountingActive()
                && cutoverConsistent
                && accountingPhase == ContractorAllocationMode.LIVE;
        boolean legacyBehavior = rollout.legacyAccountingActive()
                && accountingPhase == ContractorAllocationMode.SHADOW
                && lockedCutover == null;
        boolean activationPreflightReady = !legacyBehavior
                || safeActivationPreflightReady(businessClock.today());

        List<String> blockers = blockers(
                rollout,
                accountingPhase,
                runtime,
                backlogReady,
                cutoverConsistent,
                lockedCutover,
                activationPreflightReady
        );
        boolean activationAvailable = legacyBehavior
                && runtime.rewardAttributionMasterEnabled()
                && activationPreflightReady;
        String mode;
        if (!cutoverConsistent
                || (rollout.legacyAccountingActive() && accountingPhase == ContractorAllocationMode.LIVE)
                || (rollout.completionAccountingActive() && accountingPhase != ContractorAllocationMode.LIVE)) {
            mode = "CONFIGURATION_ERROR";
        } else if (rollout.legacyAccountingActive()) {
            mode = "LEGACY";
        } else if (runtime.liveRoutingEnabled()) {
            mode = "ROUTING_LIVE";
        } else if (!rollout.routingRequested() && accountingPhase == ContractorAllocationMode.LIVE) {
            mode = "ROUTING_PAUSED";
        } else {
            mode = "COMPLETION_ACTIVE";
        }

        return new ContractorPaymentSystemStatusResponse(
                mode,
                rollout.completionAccountingActive(),
                legacyBehavior,
                rollout.completionAccountingActive(),
                rollout.routingRequested(),
                completionEffective,
                runtime.liveRoutingEnabled(),
                backlogReady,
                activationAvailable,
                List.copyOf(blockers),
                rollout.attributionStartDate(),
                rollout.revision(),
                runtime.liveRoutingMasterEnabled(),
                runtime.rewardAttributionMasterEnabled()
        );
    }

    @Transactional
    public void activate(ContractorPaymentSystemActivationRequest request) {
        requireConfirmation(request.confirmation(), ACTIVATE_CONFIRMATION);
        LocalDate startDate = request.attributionStartDate();
        if (startDate == null || !startDate.equals(businessClock.today())) {
            throw badRequest("Дата начала должна совпадать с текущей рабочей датой");
        }

        ContractorPaymentRuntimeSwitch.RuntimeStatus runtime = runtimeSwitch.status();
        if (!runtime.rewardAttributionMasterEnabled()) {
            throw conflict("Запуск не разрешён конфигурацией сервера");
        }

        ContractorPaymentRolloutState state = rolloutStateService.lockCurrent();
        if (state.completionAccountingActive()) {
            if (!Objects.equals(state.getAttributionStartDate(), startDate)) {
                throw conflict("Дата начала нового учёта уже зафиксирована и не может быть изменена");
            }
            return;
        }
        requireRevision(state, request.expectedRevision());
        if (accountingPhaseService.lockCurrent() != ContractorAllocationMode.SHADOW) {
            throw conflict("Финансовая фаза уже содержит боевые маршруты; автоматический запуск запрещён");
        }
        if (cutoverStateService.lockedStartDate().isPresent()) {
            throw conflict("Граница нового учёта уже существует; требуется ручная сверка состояния");
        }
        if (!cutoverPreflightService.readyForActivation(startDate)) {
            throw conflict("Финансовая подготовка не завершена; устраните legacy-пересечения и очереди синхронизации");
        }
        if (cutoverStateService.lockOrVerify(startDate).isEmpty()) {
            throw conflict("Не удалось необратимо зафиксировать дату начала нового учёта");
        }

        String actor = currentActor();
        String oldValue = state.getAccountingAuthority() + ";routing=" + state.isRoutingRequested();
        state.activateCompletionAccounting(startDate, actor, businessClock.now());
        // Direct transfers become real accounting operations at the same
        // boundary, even while client-facing requisites remain paused.
        accountingPhaseService.lockAndPromoteForLiveRoute();
        appSettingService.setString(
                AppSettingService.CONTRACTOR_PAYMENTS_COMPLETION_ATTRIBUTION_START_DATE,
                startDate.toString()
        );
        appSettingService.setBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_REWARD_ATTRIBUTION_LIVE_ENABLED,
                true
        );
        // Accounting starts first. Client-facing requisites remain on the
        // owner until repair is clean and OWNER signs the routing command.
        appSettingService.setBoolean(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED, false);
        appSettingService.setBoolean(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED, false);
        appSettingService.invalidateByPrefix(SETTINGS_PREFIX);
        businessAuditService.recordRequiredInCurrentTransaction(
                "CONTRACTOR_PAYMENT_SYSTEM_ACTIVATED",
                "CONTRACTOR_PAYMENT_ROLLOUT",
                ContractorPaymentRolloutState.SINGLETON_ID,
                null,
                null,
                oldValue,
                "COMPLETION;startDate=" + startDate + ";routing=false",
                request.reason().trim()
        );
    }

    @Transactional
    public void updateRouting(ContractorPaymentRoutingCommandRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        requireConfirmation(
                request.confirmation(),
                enabled ? ENABLE_ROUTING_CONFIRMATION : PAUSE_ROUTING_CONFIRMATION
        );
        ContractorPaymentRolloutState state = rolloutStateService.lockCurrent();
        if (!state.completionAccountingActive() || state.getAttributionStartDate() == null) {
            throw conflict("Сначала необходимо активировать новый учёт выполненных работ");
        }
        if (state.isRoutingRequested() == enabled) {
            return;
        }
        requireRevision(state, request.expectedRevision());

        if (enabled) {
            ContractorPaymentRuntimeSwitch.RuntimeStatus runtime = runtimeSwitch.status();
            if (!runtime.rewardAttributionMasterEnabled() || !runtime.liveRoutingMasterEnabled()) {
                throw conflict("Подстановка реквизитов не разрешена конфигурацией сервера");
            }
            if (!Objects.equals(
                    cutoverStateService.lockedStartDate().orElse(null),
                    state.getAttributionStartDate()
            )) {
                throw conflict("Зафиксированная дата начала не прошла проверку");
            }
            if (!completionRoutingReadinessService.readyForLiveRouting()) {
                throw conflict("Подготовка начислений ещё не завершена; устраните очередь repair");
            }
            List<String> configurationBlockers = runtimeSwitch.routingConfigurationBlockers();
            if (!configurationBlockers.isEmpty()) {
                throw conflict("Конфигурация клиентских платежей не готова: "
                        + String.join("; ", configurationBlockers));
            }
        }

        String actor = currentActor();
        state.updateRoutingRequested(enabled, actor, businessClock.now());
        appSettingService.setBoolean(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_ROUTING_ENABLED, enabled);
        appSettingService.setBoolean(AppSettingService.CONTRACTOR_PAYMENTS_LIVE_READINESS_CONFIRMED, enabled);
        appSettingService.invalidateByPrefix(SETTINGS_PREFIX);
        businessAuditService.recordRequiredInCurrentTransaction(
                enabled
                        ? "CONTRACTOR_PAYMENT_ROUTING_ENABLED"
                        : "CONTRACTOR_PAYMENT_ROUTING_PAUSED",
                "CONTRACTOR_PAYMENT_ROLLOUT",
                ContractorPaymentRolloutState.SINGLETON_ID,
                null,
                null,
                "routing=" + !enabled,
                "routing=" + enabled,
                request.reason().trim()
        );
    }

    private List<String> blockers(
            ContractorPaymentRolloutStateService.Snapshot rollout,
            ContractorAllocationMode accountingPhase,
            ContractorPaymentRuntimeSwitch.RuntimeStatus runtime,
            boolean backlogReady,
            boolean cutoverConsistent,
            LocalDate lockedCutover,
            boolean activationPreflightReady
    ) {
        List<String> result = new ArrayList<>();
        if (rollout.legacyAccountingActive()) {
            if (!runtime.rewardAttributionMasterEnabled()) {
                result.add("Запуск закрыт конфигурацией сервера");
            }
            if (accountingPhase != ContractorAllocationMode.SHADOW) {
                result.add("Финансовая фаза не находится в тестовом режиме");
            }
            if (lockedCutover != null) {
                result.add("Обнаружена ранее зафиксированная граница учёта");
            }
            if (!activationPreflightReady) {
                result.add("Финансовая подготовка за текущую дату не завершена");
            }
            return result;
        }
        if (!cutoverConsistent) {
            result.add("Дата начала не совпадает с необратимой границей учёта");
        }
        if (accountingPhase != ContractorAllocationMode.LIVE) {
            result.add("Финансовая фаза не активирована вместе с новым учётом");
        }
        result.addAll(runtimeSwitch.routingConfigurationBlockers());
        if (!runtime.rewardAttributionDatabaseEnabled()) {
            result.add("DB-флаг нового учёта отличается от зафиксированного состояния");
        }
        if (!runtime.rewardAttributionMasterEnabled()) {
            result.add("Deployment master учёта выключен; обратный переход всё равно запрещён");
        }
        if (rollout.routingRequested()) {
            if (!runtime.liveRoutingMasterEnabled()) {
                result.add("Подстановка реквизитов закрыта конфигурацией сервера");
            }
            if (!runtime.liveRoutingDatabaseEnabled()) {
                result.add("DB-флаг подстановки реквизитов выключен");
            }
            if (!backlogReady) {
                result.add("Очередь подготовки начислений ещё не пуста");
            }
        }
        return result;
    }

    private boolean safeBacklogReady() {
        try {
            return completionRoutingReadinessService.readyForLiveRouting();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean safeActivationPreflightReady(LocalDate startDate) {
        try {
            return cutoverPreflightService.readyForActivation(startDate);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void requireRevision(ContractorPaymentRolloutState state, Long expectedRevision) {
        if (expectedRevision == null || expectedRevision != state.getRowVersion()) {
            throw conflict("Состояние изменилось в другой сессии; обновите страницу и повторите команду");
        }
    }

    private void requireConfirmation(String actual, String required) {
        if (actual == null || !required.equals(actual.trim())) {
            throw badRequest("Текст подтверждения не совпадает");
        }
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "system";
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
