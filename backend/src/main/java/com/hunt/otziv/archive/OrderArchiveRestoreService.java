package com.hunt.otziv.archive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderArchiveRestoreService {

    private static final String DEFAULT_TARGET_STATUS = "Новый";
    private static final Set<String> ALLOWED_TARGET_STATUSES = Set.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Архив"
    );

    private final OrderArchiveRestoreRepository repository;

    @Transactional
    public ArchiveRestoreResult restoreOrder(
            Long orderId,
            String targetStatus,
            String restoredBy,
            boolean confirm
    ) {
        if (!confirm) {
            throw new IllegalArgumentException("Archive restore requires confirm=true");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("Order id is required");
        }

        String resolvedStatus = normalizeStatus(targetStatus);
        if (!ALLOWED_TARGET_STATUSES.contains(resolvedStatus)) {
            throw new IllegalArgumentException("Unsupported archive restore target status: " + resolvedStatus);
        }

        Long targetStatusId = repository.findStatusId(resolvedStatus);
        if (targetStatusId == null) {
            throw new IllegalArgumentException("Target order status not found: " + resolvedStatus);
        }

        ArchiveCandidateCounts selected = repository.countArchiveRows(orderId);
        if (selected.orders() != 1) {
            throw new IllegalArgumentException("Archive order not found: " + orderId);
        }
        if (repository.isAlreadyRestored(orderId)) {
            throw new ArchiveRestoreConflictException("Archive order has already been restored: " + orderId);
        }

        ArchiveCandidateCounts conflicts = repository.countLiveConflicts(orderId);
        if (!conflicts.isEmpty()) {
            throw new ArchiveRestoreConflictException("Live rows already exist for archive order " + orderId + ": " + conflicts);
        }

        ArchiveCandidateCounts restored = repository.restoreOrder(orderId, targetStatusId);
        if (!selected.equals(restored)) {
            throw new IllegalStateException("Archive restore verification failed: selected=" + selected + ", restored=" + restored);
        }

        LocalDateTime restoredAt = LocalDateTime.now();
        String actor = restoredBy == null || restoredBy.isBlank() ? "unknown" : restoredBy.trim();
        String message = "restore completed; targetStatus=" + resolvedStatus + "; selected=" + selected;
        Long batchId = repository.insertRestoreBatch(
                orderId,
                restoredAt,
                actor,
                resolvedStatus,
                restored,
                message
        );
        repository.markArchiveOrderRestored(orderId, batchId, restoredAt, actor);

        ArchiveRestoreResult result = new ArchiveRestoreResult(
                batchId,
                orderId,
                restoredAt,
                actor,
                resolvedStatus,
                selected,
                restored,
                message
        );
        log.info("Archive order restored: {}", result);
        return result;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_TARGET_STATUS;
        }
        String trimmed = status.trim();
        return trimmed.length() <= 100 ? trimmed : trimmed.substring(0, 100);
    }
}
