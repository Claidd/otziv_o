package com.hunt.otziv.payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
            LocalDate to,
            boolean excludePrivilegedTargets
    ) {
        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(10, Math.min(size, 100));
        PaymentLinkAdminSummary summary = repository.summarizeArchived(
                statusFilter,
                search,
                searchId,
                from,
                to,
                excludePrivilegedTargets
        );
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
                        excludePrivilegedTargets,
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
        List<Long> snapshotIds = repository.findArchiveCandidateIds(paidCutoff, finalCutoff, batchSize);
        if (dryRun || snapshotIds.isEmpty()) {
            return new PaymentLinkArchiveRunResponse(
                    snapshotIds.size(),
                    0,
                    0,
                    true,
                    snapshotIds.isEmpty() ? "Нет закрытых платежей для архива" : "Проверка без переноса"
            );
        }

        // Auto archive follows the same global lock order as every payment
        // mutation: parent Orders first, then revalidated PaymentLinks.
        List<Long> snapshotOrderIds = repository.findOrderIdsForPaymentLinkIds(snapshotIds);
        List<Long> lockedOrderIds = repository.lockOrderIdsForArchive(snapshotOrderIds);
        List<Long> candidateIds = repository.findArchiveCandidateIdsForUpdate(
                snapshotIds,
                lockedOrderIds,
                paidCutoff,
                finalCutoff
        );
        if (candidateIds.isEmpty()) {
            return new PaymentLinkArchiveRunResponse(
                    0,
                    0,
                    0,
                    false,
                    "Кандидаты изменились во время проверки; архивирование безопасно пропущено"
            );
        }

        // Orders and PaymentLinks are already locked in canonical order. Only
        // now may an expired, no-longer-retryable notification claim be removed.
        // Final copy/delete predicates still reject every claim that remains.
        repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(candidateIds);

        long batchId = System.currentTimeMillis();
        repository.archiveIds(candidateIds, now, "AUTO_CLOSED_PAYMENT_LINK", batchId);
        int archived = repository.countArchivedIds(candidateIds);
        if (archived != candidateIds.size()) {
            throw new IllegalStateException(
                    "Payment link auto-archive verification failed: selected="
                            + candidateIds.size() + ", archived=" + archived
            );
        }
        int deleted = repository.deleteLiveIds(candidateIds);
        if (deleted != candidateIds.size()) {
            throw new IllegalStateException(
                    "Payment link auto-delete verification failed: selected="
                            + candidateIds.size() + ", deleted=" + deleted
            );
        }
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
        List<Long> ids = repository.findLiveIdsByOrderIdForUpdate(orderId);
        if (ids.isEmpty()) {
            return 0;
        }
        repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(ids);
        if (repository.hasLiveArchiveBlockerForOrder(orderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ нельзя удалить: платежная операция или уведомление еще не завершены"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        repository.archiveIds(ids, now, "ORDER_DELETED", System.currentTimeMillis());
        int archived = repository.countArchivedIds(ids);
        if (archived != ids.size()) {
            throw new IllegalStateException(
                    "Deleted order payment archive verification failed: selected="
                            + ids.size() + ", archived=" + archived
            );
        }
        int deleted = repository.deleteLiveIds(ids);
        if (deleted != ids.size()) {
            throw new IllegalStateException(
                    "Deleted order payment delete verification failed: selected="
                            + ids.size() + ", deleted=" + deleted
            );
        }
        return deleted;
    }

    /**
     * Archives payment links that belong to the temporary order-archive candidate set.
     * This must run before the live orders are deleted so financial history and its
     * company/manager snapshots remain available independently of the order archive.
     */
    @Transactional
    public int archiveForPreparedOrderArchiveCandidates(Long archiveBatchId) {
        List<Long> ids = repository.findLiveIdsForPreparedOrderArchiveCandidatesForUpdate();
        if (ids.isEmpty()) {
            return 0;
        }
        repository.deleteExpiredIneligibleNotificationClaimsForLockedPaymentLinks(ids);
        if (repository.hasPreparedOrderArchiveBlocker()) {
            throw new IllegalStateException(
                    "Order archive blocked: a payment operation or notification is still pending"
            );
        }

        LocalDateTime now = LocalDateTime.now();
        repository.deleteArchivedSnapshotsForPreparedRearchive(ids);
        repository.archivePreparedOrderIds(ids, now, "ORDER_ARCHIVED", archiveBatchId);
        int archived = repository.countArchivedIds(ids);
        if (archived != ids.size()) {
            throw new IllegalStateException(
                    "Payment link archive verification failed: selected=" + ids.size() + ", archived=" + archived
            );
        }

        int deleted = repository.deletePreparedOrderLiveIds(ids);
        if (deleted != ids.size()) {
            throw new IllegalStateException(
                    "Payment link delete verification failed: selected=" + ids.size() + ", deleted=" + deleted
            );
        }
        return deleted;
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
                ? new PaymentLinkAdminSummary(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
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
                safe.safeRejected(),
                safe.safeReceiptPending(),
                safe.safeReceiptOverdue()
        );
    }

    private BigDecimal amountRubles(long kopecks) {
        return BigDecimal.valueOf(kopecks).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
