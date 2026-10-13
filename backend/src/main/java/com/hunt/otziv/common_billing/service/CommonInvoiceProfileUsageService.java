package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.CommonInvoiceProfileUsage;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommonInvoiceProfileUsageService implements CommonInvoiceProfileUsage {

    private static final Set<CommonInvoiceStatus> ACTIVE_MANUAL_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION
    );

    private final CommonInvoiceRepository invoiceRepository;

    @Transactional(readOnly = true)
    @Override
    public long used(Long profileId, LocalDateTime from, LocalDateTime to) {
        return invoiceRepository.sumReservedAndConfirmedMonthlyProfileRoutesForPeriod(
                profileId, from, to,
                ACTIVE_MANUAL_STATUSES, CommonInvoiceStatus.PAID);
    }

    @Transactional(readOnly = true)
    @Override
    public long confirmed(Long profileId, LocalDateTime from, LocalDateTime to) {
        return invoiceRepository.sumMonthlyProfilePaymentRoutesForPeriod(
                profileId, from, to,
                Set.of(CommonInvoiceStatus.PAID));
    }

    @Transactional(readOnly = true)
    @Override
    public long pendingAmount(Long profileId, LocalDateTime from, LocalDateTime to) {
        return invoiceRepository.sumMonthlyProfilePaymentRoutesForPeriod(
                profileId, from, to,
                ACTIVE_MANUAL_STATUSES);
    }

    @Transactional(readOnly = true)
    @Override
    public long pendingCount(Long profileId, LocalDateTime from, LocalDateTime to) {
        return invoiceRepository.countMonthlyProfilePaymentRoutesForPeriod(
                profileId, from, to,
                ACTIVE_MANUAL_STATUSES);
    }
}
