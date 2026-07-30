package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.DeliveryProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferWorkflowRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferOfferService {

    private static final int DELIVERY_BATCH_SIZE = 25;
    private static final int DELIVERY_LEASE_MINUTES = 5;
    private static final int DELIVERY_DEADLINE_MINUTES = 30;

    private final WorkloadTransferOfferRepository offerRepository;
    private final WorkloadTransferWorkflowRepository workflowRepository;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional
    public OfferStageResult stageNextOffers() {
        WorkloadLiveSettingsResponse settings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(settings)) {
            return new OfferStageResult(false, 0, 0, "Боевой контур выключен");
        }
        LocalDateTime now = now();
        /*
         * The expiry statement updates offer, workflow and candidate in one
         * guarded MySQL transition, so its JDBC affected-row count is a physical
         * row count rather than the number of expired offers. Lock the matching
         * offer IDs first and expose that logical count to monitoring.
         */
        int expired = offerRepository.lockDueOfferIds(now).size();
        offerRepository.expireDueOffers(now);
        offerRepository.releaseUnavailableUndeliveredOffers(now);
        offerRepository.expireUndeliveredOffers(now);
        offerRepository.releaseDeliveryFailedWorkflows(now);
        offerRepository.skipUnavailableWaitingCandidates(now);
        workflowRepository.markExhaustedWorkflows(now);
        if (!insideOfferWindow(settings, now.toLocalTime())) {
            return new OfferStageResult(true, 0, expired, "Вне окна предложений");
        }

        int staged = stageCandidateOffers(settings, now);
        workflowRepository.markExhaustedWorkflows(now);
        return new OfferStageResult(
                true,
                staged,
                expired,
                "Очередь предложений актуализирована"
        );
    }

    @Transactional
    public ClaimedOffers claimDueOffers() {
        WorkloadLiveSettingsResponse settings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(settings)) {
            return new ClaimedOffers(null, List.of(), 0);
        }
        LocalDateTime now = now();
        int unavailable =
                offerRepository.releaseUnavailableUndeliveredOffers(now);
        if (unavailable > 0) {
            offerRepository.skipUnavailableWaitingCandidates(now);
            workflowRepository.markExhaustedWorkflows(now);
            if (insideOfferWindow(settings, now.toLocalTime())) {
                stageCandidateOffers(settings, now);
                workflowRepository.markExhaustedWorkflows(now);
            }
        }
        String token = UUID.randomUUID().toString();
        boolean allManagers = "LIVE".equals(settings.mode());
        String managerIdsJson = managerIdsJson(settings);
        int claimed = offerRepository.claimDueOffers(
                token,
                allManagers,
                managerIdsJson,
                now,
                now.plusMinutes(DELIVERY_LEASE_MINUTES),
                DELIVERY_BATCH_SIZE
        );
        if (claimed == 0) {
            return new ClaimedOffers(
                    token,
                    List.of(),
                    settings.offerTimeoutMinutes()
            );
        }
        return new ClaimedOffers(
                token,
                List.copyOf(offerRepository.findClaimedOffers(token)),
                settings.offerTimeoutMinutes()
        );
    }

    @Transactional
    public void markDelivered(
            long offerId,
            String processingToken,
            int messageId
    ) {
        markDelivered(
                offerId,
                processingToken,
                messageId,
                liveSettingsService.current().offerTimeoutMinutes()
        );
    }

    @Transactional
    public void markDelivered(
            long offerId,
            String processingToken,
            int messageId,
            int responseTimeoutMinutes
    ) {
        LocalDateTime deliveredAt = now();
        int safeTimeout = Math.max(1, responseTimeoutMinutes);
        if (offerRepository.markDelivered(
                offerId,
                processingToken,
                messageId,
                deliveredAt,
                deliveredAt.plusMinutes(safeTimeout)
        ) != 1) {
            throw new IllegalStateException(
                    "Предложение " + offerId + " потеряло delivery lease"
            );
        }
    }

    @Transactional
    public void markDeliveryFailure(
            long offerId,
            String processingToken,
            String errorCode,
            String errorMessage
    ) {
        LocalDateTime now = now();
        var shadow = shadowSettingsService.current();
        int attempt = Math.max(1, shadow.notificationRetryBaseMinutes());
        offerRepository.markDeliveryFailure(
                offerId,
                processingToken,
                shadow.notificationMaxAttempts(),
                now.plusMinutes(attempt),
                limited(errorCode, 80),
                limited(errorMessage, 1000),
                now
        );
        offerRepository.releaseDeliveryFailedWorkflows(now);
        workflowRepository.markExhaustedWorkflows(now);
    }

    private int stageCandidateOffers(
            WorkloadLiveSettingsResponse settings,
            LocalDateTime now
    ) {
        boolean allManagers = "LIVE".equals(settings.mode());
        String managerIdsJson = managerIdsJson(settings);
        String stagingBatchToken = UUID.randomUUID().toString();
        int inserted = offerRepository.insertEligibleOfferBatch(
                allManagers,
                managerIdsJson,
                stagingBatchToken,
                now,
                now.plusMinutes(DELIVERY_DEADLINE_MINUTES),
                DELIVERY_BATCH_SIZE
        );
        if (inserted == 0) {
            return 0;
        }
        /*
         * This guarded multi-table transition handles every freshly inserted
         * READY offer from this exact staging batch in one statement. MySQL
         * reports two changed physical rows per logical offer: one workflow
         * and one candidate. Any mismatch rolls the surrounding transaction
         * back, including the preceding INSERT.
         */
        int marked = offerRepository.markReadyOfferBatchOffered(
                allManagers,
                managerIdsJson,
                stagingBatchToken,
                now
        );
        int expectedMarked = inserted * 2;
        if (marked != expectedMarked) {
            throw new IllegalStateException(
                    "Нарушена атомарность staging предложений: inserted="
                            + inserted
                            + ", marked="
                            + marked
                            + ", expected="
                            + expectedMarked
            );
        }
        return inserted;
    }

    private String managerIdsJson(WorkloadLiveSettingsResponse settings) {
        return settings.canaryManagerIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private LocalDateTime now() {
        var shadow = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(shadow));
    }

    private boolean insideOfferWindow(
            WorkloadLiveSettingsResponse settings,
            LocalTime now
    ) {
        LocalTime start = LocalTime.parse(settings.offerStartTime());
        LocalTime end = LocalTime.parse(settings.offerEndTime());
        return !now.isBefore(start) && now.isBefore(end);
    }

    private String limited(String value, int maximum) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record OfferStageResult(
            boolean enabled,
            int staged,
            int expired,
            String message
    ) {
    }

    public record ClaimedOffers(
            String processingToken,
            List<DeliveryProjection> offers,
            int responseTimeoutMinutes
    ) {
    }
}
