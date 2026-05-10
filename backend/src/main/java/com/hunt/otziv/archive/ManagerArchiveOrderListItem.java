package com.hunt.otziv.archive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ManagerArchiveOrderListItem(
        Long id,
        Long companyId,
        String companyTitle,
        String companyTelephone,
        String companyCity,
        String filialTitle,
        String status,
        BigDecimal sum,
        Integer amount,
        Integer counter,
        boolean waitingForClient,
        String managerName,
        String workerName,
        LocalDate created,
        LocalDate changed,
        LocalDate payDay,
        LocalDateTime archivedAt,
        String archiveReason,
        Long archiveBatchId,
        LocalDateTime restoredAt,
        String restoredBy,
        Long restoreBatchId,
        long orderDetailsCount,
        long reviewsCount,
        BigDecimal paymentCheckSum,
        BigDecimal zpSum,
        String source
) {
}
