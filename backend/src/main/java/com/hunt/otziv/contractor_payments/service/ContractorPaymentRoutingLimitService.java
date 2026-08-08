package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deployment-level blast-radius limits for routing client invoices to a
 * self-employed contractor. These limits are intentionally independent from
 * the contractor's accrued/reconciled balance: both checks must pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractorPaymentRoutingLimitService {

    static final long DEFAULT_MAX_SINGLE_INVOICE_KOPECKS = 5_000_000L;
    static final long DEFAULT_MAX_DAILY_PROFILE_AMOUNT_KOPECKS = 15_000_000L;
    static final long DEFAULT_MAX_DAILY_PROFILE_COUNT = 50L;

    private final ContractorPaymentAllocationRepository allocationRepository;
    private final AtomicBoolean invalidConfigurationLogged = new AtomicBoolean();

    @Value("${otziv.contractor-payments.routing-limits.max-single-invoice-kopecks:5000000}")
    private String maxSingleInvoiceKopecksValue;

    @Value("${otziv.contractor-payments.routing-limits.max-daily-profile-amount-kopecks:15000000}")
    private String maxDailyProfileAmountKopecksValue;

    @Value("${otziv.contractor-payments.routing-limits.max-daily-profile-count:50}")
    private String maxDailyProfileCountValue;

    @Value("${otziv.contractor-payments.business-zone:Asia/Irkutsk}")
    private String businessZoneId;

    /**
     * Returns false on every invalid or exceeded limit. The routing caller then
     * evaluates the next candidate and ultimately persists OWNER_FALLBACK.
     */
    public boolean allows(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            long amountKopecks
    ) {
        return evaluate(profile, mode, amountKopecks).allowed();
    }

    public RoutingLimitDecision evaluate(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            long amountKopecks
    ) {
        Configuration configuration = configuration();
        if (configuration == null) {
            // configuration() emits one process-level error. Avoid a warning
            // for every candidate while the global fail-closed state remains.
            return RoutingLimitDecision.rejected(
                    ContractorRoutingDecisionReason.LIMIT_CONFIGURATION_INVALID
            );
        }
        if (profile == null || profile.getId() == null || mode == null || amountKopecks <= 0L) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_ROUTE_INPUT_INVALID
            );
        }
        if (amountKopecks > configuration.maxSingleInvoiceKopecks()) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_SINGLE_INVOICE_EXCEEDED
            );
        }

        TimeWindow day = currentBusinessDay(configuration.businessZone());
        if (day == null) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_DATABASE_CLOCK_INVALID
            );
        }
        ContractorPaymentAllocationRepository.DailyRoutingTotals totals = allocationRepository
                .dailyRoutingTotalsForUpdate(profile.getId(), mode.name(), day.from(), day.to());
        if (totals == null || totals.getAmountKopecks() == null || totals.getRouteCount() == null
                || totals.getAmountKopecks() < 0L || totals.getRouteCount() < 0L) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_DAILY_TOTALS_INVALID
            );
        }

        final long nextAmount;
        final long nextCount;
        try {
            nextAmount = Math.addExact(totals.getAmountKopecks(), amountKopecks);
            nextCount = Math.addExact(totals.getRouteCount(), 1L);
        } catch (ArithmeticException overflow) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_DAILY_TOTAL_OVERFLOW
            );
        }
        if (nextAmount > configuration.maxDailyProfileAmountKopecks()) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED
            );
        }
        if (nextCount > configuration.maxDailyProfileCount()) {
            return deny(
                    profile,
                    mode,
                    amountKopecks,
                    ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED
            );
        }
        return RoutingLimitDecision.permitted();
    }

    private Configuration configuration() {
        ZoneId businessZone;
        Long maxSingleInvoiceKopecks;
        Long maxDailyProfileAmountKopecks;
        Long maxDailyProfileCount;
        try {
            businessZone = ZoneId.of(businessZoneId == null ? "" : businessZoneId.trim());
            maxSingleInvoiceKopecks = parseLong(maxSingleInvoiceKopecksValue);
            maxDailyProfileAmountKopecks = parseLong(maxDailyProfileAmountKopecksValue);
            maxDailyProfileCount = parseLong(maxDailyProfileCountValue);
        } catch (RuntimeException invalidConfiguration) {
            logInvalidConfigurationOnce();
            return null;
        }
        if (maxSingleInvoiceKopecks == null
                || maxDailyProfileAmountKopecks == null
                || maxDailyProfileCount == null
                || maxSingleInvoiceKopecks <= 0L
                || maxSingleInvoiceKopecks > ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS
                || maxDailyProfileAmountKopecks <= 0L
                || maxDailyProfileAmountKopecks > ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS
                || maxDailyProfileCount <= 0L) {
            logInvalidConfigurationOnce();
            return null;
        }
        return new Configuration(
                maxSingleInvoiceKopecks,
                maxDailyProfileAmountKopecks,
                maxDailyProfileCount,
                businessZone
        );
    }

    private void logInvalidConfigurationOnce() {
        if (invalidConfigurationLogged.compareAndSet(false, true)) {
            log.error(
                    "Маршрутизация самозанятым закрыта: некорректны deployment-лимиты "
                            + "maxSingle={}, maxDailyAmount={}, maxDailyCount={}, businessZone={}",
                    maxSingleInvoiceKopecksValue,
                    maxDailyProfileAmountKopecksValue,
                    maxDailyProfileCountValue,
                    businessZoneId
            );
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value.trim());
    }

    private TimeWindow currentBusinessDay(ZoneId businessZone) {
        LocalDateTime databaseNow = allocationRepository.currentDatabaseTime();
        if (databaseNow == null) {
            return null;
        }
        ZoneId storageZone = ZoneId.systemDefault();
        java.time.LocalDate today = databaseNow.atZone(storageZone)
                .withZoneSameInstant(businessZone)
                .toLocalDate();
        LocalDateTime from = LocalDateTime.ofInstant(
                today.atStartOfDay(businessZone).toInstant(),
                storageZone
        );
        LocalDateTime to = LocalDateTime.ofInstant(
                today.plusDays(1L).atStartOfDay(businessZone).toInstant(),
                storageZone
        );
        return new TimeWindow(from, to);
    }

    private RoutingLimitDecision deny(
            ContractorPaymentProfile profile,
            ContractorAllocationMode mode,
            long amountKopecks,
            ContractorRoutingDecisionReason reason
    ) {
        Long profileId = profile == null ? null : profile.getId();
        Long userId = profile == null || profile.getUser() == null ? null : profile.getUser().getId();
        log.warn(
                "Получатель пропущен операционным лимитом: reason={}, profileId={}, userId={}, mode={}, amount={}",
                reason,
                profileId,
                userId,
                mode,
                amountKopecks
        );
        return RoutingLimitDecision.rejected(reason);
    }

    private record Configuration(
            long maxSingleInvoiceKopecks,
            long maxDailyProfileAmountKopecks,
            long maxDailyProfileCount,
            ZoneId businessZone
    ) {
    }

    private record TimeWindow(LocalDateTime from, LocalDateTime to) {
    }

    public record RoutingLimitDecision(
            boolean allowed,
            ContractorRoutingDecisionReason rejectionReason
    ) {
        public static RoutingLimitDecision permitted() {
            return new RoutingLimitDecision(true, null);
        }

        public static RoutingLimitDecision rejected(ContractorRoutingDecisionReason reason) {
            return new RoutingLimitDecision(false, java.util.Objects.requireNonNull(reason, "reason"));
        }
    }
}
