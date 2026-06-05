package com.hunt.otziv.payments.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinkSummaryResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.PaymentLinkArchiveRunResponse;
import com.hunt.otziv.payments.repository.PaymentLinkArchiveRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentLinkArchiveService {

    private static final int DEFAULT_PAID_RETENTION_DAYS = 90;
    private static final int DEFAULT_FINAL_RETENTION_DAYS = 60;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 5000;

    private final PaymentLinkArchiveRepository repository;
    private final AppSettingService appSettingService;
    private final TbankPaymentProperties properties;

    @Transactional(readOnly = true)
    public AdminPaymentLinksPageResponse archivedLinks(
            int page,
            int size,
            String statusFilter,
            String search,
            Long searchId,
            LocalDate from,
            LocalDate to
    ) {
        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(10, Math.min(size, 100));
        PaymentLinkAdminSummary summary = repository.summarizeArchived(statusFilter, search, searchId, from, to);
        long total = summary == null ? 0 : summary.safeTotalElements();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / resolvedSize);
        return new AdminPaymentLinksPageResponse(
                repository.findArchivedPage(
                        resolvedPage,
                        resolvedSize,
                        statusFilter,
                        search,
                        searchId,
                        from,
                        to,
                        properties.getPublicBaseUrl()
                ),
                resolvedPage,
                resolvedSize,
                total,
                totalPages,
                "ARCHIVE",
                toSummaryResponse(summary)
        );
    }

    @Transactional
    public PaymentLinkArchiveRunResponse run(boolean dryRun, Integer requestedBatchSize) {
        int batchSize = resolvedBatchSize(requestedBatchSize);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime paidCutoff = now.minusDays(retentionDays(
                AppSettingService.PAYMENT_LINKS_ARCHIVE_PAID_RETENTION_DAYS,
                DEFAULT_PAID_RETENTION_DAYS
        ));
        LocalDateTime finalCutoff = now.minusDays(retentionDays(
                AppSettingService.PAYMENT_LINKS_ARCHIVE_FINAL_RETENTION_DAYS,
                DEFAULT_FINAL_RETENTION_DAYS
        ));
        List<Long> candidateIds = repository.findArchiveCandidateIds(paidCutoff, finalCutoff, batchSize);
        if (dryRun || candidateIds.isEmpty()) {
            return new PaymentLinkArchiveRunResponse(
                    candidateIds.size(),
                    0,
                    0,
                    true,
                    candidateIds.isEmpty() ? "Нет закрытых платежей для архива" : "Проверка без переноса"
            );
        }

        long batchId = System.currentTimeMillis();
        int archived = repository.archiveIds(candidateIds, now, "AUTO_CLOSED_PAYMENT_LINK", batchId);
        int deleted = archived > 0 ? repository.deleteLiveIds(candidateIds) : 0;
        return new PaymentLinkArchiveRunResponse(
                candidateIds.size(),
                archived,
                deleted,
                false,
                "Закрытые платежи перенесены в archive_payment_links"
        );
    }

    @Transactional
    public int archiveForDeletedOrder(Long orderId) {
        List<Long> ids = repository.findLiveIdsByOrderId(orderId);
        if (ids.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        repository.archiveIds(ids, now, "ORDER_DELETED", System.currentTimeMillis());
        return repository.deleteLiveIds(ids);
    }

    @Scheduled(cron = "0 35 3 * * *", zone = "Asia/Irkutsk")
    @Transactional
    public void scheduledArchive() {
        if (!appSettingService.getBoolean(AppSettingService.PAYMENT_LINKS_ARCHIVE_ENABLED, false)) {
            return;
        }
        run(false, null);
    }

    private int resolvedBatchSize(Integer requestedBatchSize) {
        int value = requestedBatchSize == null
                ? appSettingService.getInt(AppSettingService.PAYMENT_LINKS_ARCHIVE_BATCH_SIZE, DEFAULT_BATCH_SIZE)
                : requestedBatchSize;
        return Math.max(1, Math.min(value, MAX_BATCH_SIZE));
    }

    private int retentionDays(String key, int fallback) {
        return Math.max(1, appSettingService.getInt(key, fallback));
    }

    private AdminPaymentLinkSummaryResponse toSummaryResponse(PaymentLinkAdminSummary summary) {
        PaymentLinkAdminSummary safe = summary == null
                ? new PaymentLinkAdminSummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
                : summary;
        return new AdminPaymentLinkSummaryResponse(
                safe.safeTotalElements(),
                amountRubles(safe.safeTotalAmountKopecks()),
                safe.safeTotalAmountKopecks(),
                safe.safePaid(),
                safe.safeManualPending(),
                safe.safeConfirmed(),
                safe.safeNotificationsSent(),
                safe.safeNotificationErrors(),
                safe.safeRefundable(),
                safe.safeRefunded(),
                safe.safeRejected()
        );
    }

    private BigDecimal amountRubles(long kopecks) {
        return BigDecimal.valueOf(kopecks).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
