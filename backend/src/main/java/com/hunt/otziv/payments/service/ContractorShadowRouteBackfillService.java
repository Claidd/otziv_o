package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorShadowBackfillClaimService;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Repairs best-effort contractor shadow writes from durable payment-domain
 * rows. Immediate afterCommit callbacks keep the normal path fast; this worker
 * makes process restarts and temporary shadow-storage failures recoverable
 * without ever changing or rolling back the real client invoice.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractorShadowRouteBackfillService {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final String PAYMENT_LINK_QUEUE = "PAYMENT_LINK";
    private static final String COMMON_INVOICE_QUEUE = "COMMON_INVOICE";
    private static final String MANUAL_EVIDENCE_QUEUE = "MANUAL_EVIDENCE";

    private final PaymentLinkRepository paymentLinkRepository;
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final ContractorPaymentShadowService contractorPaymentShadowService;
    private final ContractorShadowBackfillClaimService claimService;
    private final AppSettingService appSettingService;

    @Scheduled(fixedDelayString = "${otziv.contractor-payments.backfill-delay-ms:60000}")
    public void backfillMissingShadowRoutes() {
        boolean shadowEnabled = appSettingService.getBoolean(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED,
                true
        );
        LocalDateTime startedAt = rolloutBoundary();
        LocalDateTime preparationStartedAt = preparationBoundary();
        if (startedAt == null || preparationStartedAt == null) {
            return;
        }

        int batchSize = Math.min(
                MAX_BATCH_SIZE,
                Math.max(1, appSettingService.getInt(
                        "contractor-payments.shadow-backfill-batch-size",
                        DEFAULT_BATCH_SIZE
                ))
        );
        PageRequest batch = PageRequest.of(0, batchSize);
        LocalDateTime now = LocalDateTime.now();
        int paymentLinks = shadowEnabled
                ? backfillPaymentLinks(startedAt, preparationStartedAt, now, batch)
                : 0;
        int commonInvoices = shadowEnabled
                ? backfillCommonInvoices(startedAt, preparationStartedAt, now, batch)
                : 0;
        // Existing LIVE obligations are reconciled even when SHADOW is off.
        // A rollout/emergency switch may stop new routes but must never make
        // already received manual evidence disappear after a process crash.
        int manualEvidence = backfillManualEvidence(startedAt, shadowEnabled, now, batch);
        if (paymentLinks + commonInvoices + manualEvidence > 0) {
            log.info(
                    "Восстановлены тестовые платежные маршруты: links={}, commonInvoices={}, manualEvidence={}",
                    paymentLinks,
                    commonInvoices,
                    manualEvidence
            );
        }
    }

    private int backfillPaymentLinks(
            LocalDateTime startedAt,
            LocalDateTime preparationStartedAt,
            LocalDateTime now,
            PageRequest batch
    ) {
        List<Long> ids = paymentLinkRepository.findMissingContractorShadowRouteIds(
                startedAt, preparationStartedAt, now, batch
        );
        int repaired = 0;
        for (Long id : ids) {
            Optional<String> claim = claimService.tryClaim(PAYMENT_LINK_QUEUE, id, now);
            if (claim.isEmpty()) {
                continue;
            }
            try {
                ContractorPaymentShadowService.ShadowReservationResult result =
                        contractorPaymentShadowService.reserveForPaymentLinkIdOutcome(id);
                if (!result.outcome().completed()) {
                    throw new IllegalStateException("SHADOW_ROUTE_" + result.outcome().name());
                }
                if (result.outcome()
                        == ContractorPaymentShadowService.ShadowReservationOutcome.CREATED) {
                    repaired++;
                }
                claimService.succeeded(PAYMENT_LINK_QUEUE, id, claim.get(), LocalDateTime.now());
            } catch (RuntimeException exception) {
                claimService.failed(PAYMENT_LINK_QUEUE, id, claim.get(), exception, LocalDateTime.now());
                log.warn(
                        "Повтор записи тестового маршрута ссылки не выполнен: sourceId={}, code={}",
                        id,
                        exception.getClass().getSimpleName()
                );
            }
        }
        return repaired;
    }

    private int backfillCommonInvoices(
            LocalDateTime startedAt,
            LocalDateTime preparationStartedAt,
            LocalDateTime now,
            PageRequest batch
    ) {
        List<Long> ids = commonInvoiceRepository.findMissingContractorShadowRouteIds(
                startedAt, preparationStartedAt, now, batch
        );
        int repaired = 0;
        for (Long id : ids) {
            Optional<String> claim = claimService.tryClaim(COMMON_INVOICE_QUEUE, id, now);
            if (claim.isEmpty()) {
                continue;
            }
            try {
                ContractorPaymentShadowService.ShadowReservationResult result =
                        contractorPaymentShadowService.reserveForCommonInvoiceIdOutcome(id);
                if (!result.outcome().completed()) {
                    throw new IllegalStateException("SHADOW_ROUTE_" + result.outcome().name());
                }
                if (result.outcome()
                        == ContractorPaymentShadowService.ShadowReservationOutcome.CREATED) {
                    repaired++;
                }
                claimService.succeeded(COMMON_INVOICE_QUEUE, id, claim.get(), LocalDateTime.now());
            } catch (RuntimeException exception) {
                claimService.failed(COMMON_INVOICE_QUEUE, id, claim.get(), exception, LocalDateTime.now());
                log.warn(
                        "Повтор записи тестового маршрута общего счета не выполнен: sourceId={}, code={}",
                        id,
                        exception.getClass().getSimpleName()
                );
            }
        }
        return repaired;
    }

    private int backfillManualEvidence(
            LocalDateTime startedAt,
            boolean includeShadow,
            LocalDateTime now,
            PageRequest batch
    ) {
        List<PaymentLinkRepository.ManualCardShadowEvidenceView> evidence =
                paymentLinkRepository.findUnrecordedContractorManualCardEvidence(
                        startedAt,
                        includeShadow,
                        now,
                        batch
                );
        int repaired = 0;
        for (PaymentLinkRepository.ManualCardShadowEvidenceView item : evidence) {
            Long evidenceId = item.getEvidenceLinkId();
            Optional<String> claim = claimService.tryClaim(MANUAL_EVIDENCE_QUEUE, evidenceId, now);
            if (claim.isEmpty()) {
                continue;
            }
            try {
                if (contractorPaymentShadowService.recordManualCardPaymentEvidence(
                        item.getOriginalLinkId(),
                        item.getEvidenceLinkId(),
                        item.getPaidAt()
                )) {
                    repaired++;
                }
                claimService.succeeded(
                        MANUAL_EVIDENCE_QUEUE, evidenceId, claim.get(), LocalDateTime.now()
                );
            } catch (RuntimeException exception) {
                claimService.failed(
                        MANUAL_EVIDENCE_QUEUE, evidenceId, claim.get(), exception, LocalDateTime.now()
                );
                log.warn(
                        "Повтор записи ручной оплаты не выполнен: originalLinkId={}, evidenceLinkId={}, code={}",
                        item.getOriginalLinkId(),
                        item.getEvidenceLinkId(),
                        exception.getClass().getSimpleName()
                );
            }
        }
        return repaired;
    }

    private LocalDateTime rolloutBoundary() {
        return boundary(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_BACKFILL_STARTED_AT,
                "граница восстановления тестовых платёжных маршрутов"
        );
    }

    private LocalDateTime preparationBoundary() {
        return boundary(
                AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_PREPARATION_STARTED_AT,
                "граница неизменяемых снимков тестовых маршрутов"
        );
    }

    private LocalDateTime boundary(String settingKey, String description) {
        String raw = appSettingService.getString(settingKey, "");
        if (raw == null || raw.isBlank()) {
            log.error("Не задана {}; backfill остановлен", description);
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim().replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            log.error("Некорректная {}: {}", description, raw);
            return null;
        }
    }
}
