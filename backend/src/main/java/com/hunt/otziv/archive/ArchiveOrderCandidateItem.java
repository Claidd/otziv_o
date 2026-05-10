package com.hunt.otziv.archive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ArchiveOrderCandidateItem(
        Long id,
        Long companyId,
        String companyTitle,
        String companyPhone,
        String companyCity,
        String filialTitle,
        String status,
        BigDecimal sum,
        Integer amount,
        Integer counter,
        String managerName,
        String workerName,
        LocalDate created,
        LocalDate changed,
        LocalDate payDay,
        LocalDate candidateDate,
        long orderDetailsCount,
        long reviewsCount
) {
}
