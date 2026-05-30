package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
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
public class ReviewRecoveryNoticeScheduler {

    private final ScheduledClientMessageStateRepository stateRepository;
    private final AppSettingService appSettingService;
    private final ClientMessageSlotPlanner slotPlanner;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional
    public boolean scheduleNotice(ReviewRecoveryBatch batch) {
        if (batch == null
                || batch.getId() == null
                || batch.getOrder() == null
                || batch.getOrder().getId() == null
                || batch.getOrder().getCompany() == null
                || batch.getOrder().getCompany().getId() == null
                || !appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_ENABLED, true)) {
            return false;
        }

        Order order = batch.getOrder();
        String targetKey = targetKey(batch.getId());
        LocalDateTime nextAttemptAt = nextAttemptAtHours(retryDelayHours());
        Optional<ScheduledClientMessageState> existing = stateRepository.findByScenarioAndTargetKey(
                ClientMessageScenario.REVIEW_RECOVERY_NOTICE,
                targetKey
        );
        if (existing.isPresent()) {
            ScheduledClientMessageState state = existing.get();
            state.setTargetType(ClientMessageTargetType.ORDER);
            state.setCompanyId(order.getCompany().getId());
            state.setOrderId(order.getId());
            state.setArchiveOrderId(null);
            state.setStatus(ScheduledMessageStateStatus.ACTIVE);
            state.setLockedUntil(null);
            state.setLastErrorCode(null);
            state.setLastErrorMessage(null);
            state.setConsecutiveFailures(0);
            if (state.getNextAttemptAt() == null || state.getNextAttemptAt().isAfter(nextAttemptAt)) {
                state.setNextAttemptAt(nextAttemptAt);
            }
            stateRepository.save(state);
            return true;
        }

        stateRepository.save(ScheduledClientMessageState.builder()
                .scenario(ClientMessageScenario.REVIEW_RECOVERY_NOTICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .companyId(order.getCompany().getId())
                .orderId(order.getId())
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(nextAttemptAt)
                .build());
        log.info("Scheduled review recovery notice batchId={} orderId={}", batch.getId(), order.getId());
        return true;
    }

    public static String targetKey(Long batchId) {
        return "review-recovery:batch:" + batchId;
    }

    public static Long batchIdFromTargetKey(String targetKey) {
        if (targetKey == null || !targetKey.startsWith("review-recovery:batch:")) {
            return null;
        }
        try {
            return Long.parseLong(targetKey.substring("review-recovery:batch:".length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int retryDelayHours() {
        return Math.max(1, Math.min(168, appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_RETRY_DELAY_HOURS,
                2
        )));
    }

    private LocalDateTime nextAttemptAtHours(int delayHours) {
        LocalDateTime desired = LocalDateTime.now(clock)
                .plusHours(delayHours)
                .withNano(0);
        return scheduleAtStorage(desired);
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
}
