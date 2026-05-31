package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentInvoiceRetryScheduler {

    private final ScheduledClientMessageStateRepository stateRepository;
    private final AppSettingService appSettingService;
    private final ClientMessageSlotPlanner slotPlanner;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional
    public void scheduleRetry(Order order) {
        if (!canSchedule(order)) {
            return;
        }
        scheduleOrderRetry(
                order,
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                orderTargetKey(order),
                retryDelayHours(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_INVOICE_RETRY_DELAY_HOURS,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_INVOICE_RETRY_DELAY_HOURS
                )
        );
    }

    @Transactional
    public void scheduleInitialInvoice(Order order) {
        if (!canSchedule(order)) {
            return;
        }
        scheduleOrderRetry(
                order,
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                orderTargetKey(order),
                0
        );
    }

    @Transactional
    public void scheduleReviewCheckRetry(Order order) {
        if (!canSchedule(order)) {
            return;
        }
        scheduleOrderRetry(
                order,
                ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY,
                orderTargetKey(order),
                retryDelayHours(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_RETRY_DELAY_HOURS,
                        ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_RETRY_DELAY_HOURS
                )
        );
    }

    @Transactional
    public void scheduleBadReviewInvoiceRetry(Order order) {
        if (!canSchedule(order)) {
            return;
        }
        scheduleOrderRetry(
                order,
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                badReviewInvoiceTargetKey(order),
                retryDelayHours(
                        AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS,
                        ScheduledClientMessageService.DEFAULT_BAD_REVIEW_INVOICE_RETRY_DELAY_HOURS
                )
        );
    }

    @Transactional
    public void scheduleBadReviewAutoBan(Order order) {
        if (!canSchedule(order)
                || !appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)) {
            return;
        }
        scheduleOrderState(
                order,
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                badReviewAutoBanTargetKey(order),
                nextAttemptAtDays(badReviewAutoBanDelayDays()),
                true
        );
    }

    @Transactional
    public void cancelBadReviewAutoBan(Order order, String reason) {
        if (!canSchedule(order)) {
            return;
        }
        stateRepository.findByScenarioAndTargetKey(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                badReviewAutoBanTargetKey(order)
        ).ifPresent(state -> {
            if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
                return;
            }
            state.setStatus(ScheduledMessageStateStatus.DONE);
            state.setNextAttemptAt(null);
            state.setLockedUntil(null);
            state.setLastErrorCode("bad_review_auto_ban_canceled");
            state.setLastErrorMessage(reason == null || reason.isBlank() ? "Автобан после плохих отменен" : reason);
            stateRepository.save(state);
        });
    }

    private void scheduleOrderRetry(
            Order order,
            ClientMessageScenario scenario,
            String targetKey,
            int delayHours
    ) {
        scheduleOrderState(
                order,
                scenario,
                targetKey,
                nextAttemptAtHours(delayHours),
                false
        );
    }

    private void scheduleOrderState(
            Order order,
            ClientMessageScenario scenario,
            String targetKey,
            LocalDateTime nextAttemptAt,
            boolean replaceNextAttempt
    ) {
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKey(scenario, targetKey);
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            state.setCompanyId(order.getCompany().getId());
            state.setOrderId(order.getId());
            state.setArchiveOrderId(null);
            state.setTargetType(ClientMessageTargetType.ORDER);
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            state.setLockedUntil(null);
            state.setLastErrorCode(null);
            state.setLastErrorMessage(null);
            state.setConsecutiveFailures(0);
            if (replaceNextAttempt || state.getNextAttemptAt() == null || state.getNextAttemptAt().isAfter(nextAttemptAt)) {
                state.setNextAttemptAt(nextAttemptAt);
            }
            stateRepository.save(state);
            return;
        }

        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .scenario(scenario)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .companyId(order.getCompany().getId())
                .orderId(order.getId())
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nextAttemptAt)
                .build();
        stateRepository.save(state);
        log.info("Scheduled client message retry scenario={} orderId={} targetKey={}", scenario, order.getId(), targetKey);
    }

    private boolean canSchedule(Order order) {
        return order != null
                && order.getId() != null
                && order.getCompany() != null
                && order.getCompany().getId() != null;
    }

    private LocalDateTime nextAttemptAtHours(int delayHours) {
        LocalDateTime desired = LocalDateTime.now(clock)
                .plusHours(delayHours)
                .withNano(0);
        return scheduleAtStorage(desired);
    }

    private LocalDateTime nextAttemptAtDays(int delayDays) {
        LocalDateTime desired = LocalDateTime.now(clock)
                .plusDays(delayDays)
                .withNano(0);
        return scheduleAtStorage(desired);
    }

    private int retryDelayHours(String settingKey, int fallback) {
        return Math.max(1, Math.min(168, appSettingService.getInt(
                settingKey,
                fallback
        )));
    }

    private int badReviewAutoBanDelayDays() {
        return Math.max(1, Math.min(365, appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                ScheduledClientMessageService.DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS
        )));
    }

    private LocalDateTime scheduleAtStorage(LocalDateTime desiredStorageTime) {
        LocalDateTime desiredIrkutsk = toIrkutskTime(desiredStorageTime);
        LocalDateTime allowedIrkutsk = slotPlanner.nextAllowedAt(desiredIrkutsk, businessWindows());
        return toStorageTime(allowedIrkutsk);
    }

    private String businessWindows() {
        String windows = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        );
        return ClientMessageSlotPlanner.isValidWindowsSpec(windows)
                ? windows
                : ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC;
    }

    private LocalDateTime toIrkutskTime(LocalDateTime storageTime) {
        ZoneId storageZone = clock.getZone();
        return storageTime.atZone(storageZone)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime();
    }

    private LocalDateTime toStorageTime(LocalDateTime irkutskTime) {
        ZoneId storageZone = clock.getZone();
        return irkutskTime.atZone(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .withZoneSameInstant(storageZone)
                .toLocalDateTime();
    }

    private String orderTargetKey(Order order) {
        return orderTargetKey(order.getId(), orderStatusChangedAt(order));
    }

    private String orderTargetKey(Long orderId, LocalDateTime statusChangedAt) {
        return "order:" + orderId + ":" + statusChangedAt.withNano(0);
    }

    private String badReviewInvoiceTargetKey(Order order) {
        return "bad-review-invoice:order:" + order.getId();
    }

    private String badReviewAutoBanTargetKey(Order order) {
        return "bad-review-auto-ban:order:" + order.getId();
    }

    private LocalDateTime orderStatusChangedAt(Order order) {
        if (order.getStatusChangedAt() != null) {
            return order.getStatusChangedAt();
        }
        if (order.getChanged() != null) {
            return order.getChanged().atStartOfDay();
        }
        if (order.getCreated() != null) {
            return order.getCreated().atStartOfDay();
        }
        return LocalDateTime.now(clock);
    }
}
