package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentRoutingLimitServiceTest {

    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;

    private ContractorPaymentRoutingLimitService service;
    private ContractorPaymentProfile profile;

    @BeforeEach
    void setUp() {
        service = new ContractorPaymentRoutingLimitService(allocationRepository);
        lenient().when(allocationRepository.currentDatabaseTime())
                .thenReturn(LocalDateTime.of(2026, 8, 7, 12, 0));
        configure(
                ContractorPaymentRoutingLimitService.DEFAULT_MAX_SINGLE_INVOICE_KOPECKS,
                ContractorPaymentRoutingLimitService.DEFAULT_MAX_DAILY_PROFILE_AMOUNT_KOPECKS,
                ContractorPaymentRoutingLimitService.DEFAULT_MAX_DAILY_PROFILE_COUNT,
                "Asia/Irkutsk"
        );
        profile = new ContractorPaymentProfile();
        profile.setId(17L);
    }

    @Test
    void allowsInvoiceExactlyAtAllOperationalLimits() {
        when(allocationRepository.dailyRoutingTotalsForUpdate(
                anyLong(),
                any(),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(totals(10_000_000L, 49L));

        assertTrue(service.allows(profile, ContractorAllocationMode.SHADOW, 5_000_000L));
        verify(allocationRepository).dailyRoutingTotalsForUpdate(
                eq(17L),
                eq("SHADOW"),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
    }

    @Test
    void rejectsInvoiceAboveSingleLimitWithoutReadingDailyTotals() {
        var decision = service.evaluate(profile, ContractorAllocationMode.LIVE, 5_000_001L);

        assertFalse(decision.allowed());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_SINGLE_INVOICE_EXCEEDED,
                decision.rejectionReason()
        );

        verify(allocationRepository, never()).dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void rejectsInvoiceThatWouldExceedDailyAmount() {
        when(allocationRepository.dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(totals(14_000_001L, 1L));

        var decision = service.evaluate(profile, ContractorAllocationMode.LIVE, 1_000_000L);

        assertFalse(decision.allowed());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED,
                decision.rejectionReason()
        );
    }

    @Test
    void rejectsInvoiceWhenDailyCountIsAlreadyFull() {
        when(allocationRepository.dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(totals(100_000L, 50L));

        var decision = service.evaluate(profile, ContractorAllocationMode.LIVE, 100_000L);

        assertFalse(decision.allowed());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED,
                decision.rejectionReason()
        );
    }

    @Test
    void failsClosedForInvalidLimitConfiguration() {
        configure(0L, 15_000_000L, 50L, "Asia/Irkutsk");

        var decision = service.evaluate(profile, ContractorAllocationMode.LIVE, 100_000L);

        assertFalse(decision.allowed());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_CONFIGURATION_INVALID,
                decision.rejectionReason()
        );
        verify(allocationRepository, never()).dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void failsClosedForNonNumericLimitWithoutBreakingOwnerFallback() {
        ReflectionTestUtils.setField(service, "maxSingleInvoiceKopecksValue", "not-a-number");

        assertFalse(service.allows(profile, ContractorAllocationMode.LIVE, 100_000L));
        verify(allocationRepository, never()).dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void failsClosedForInvalidBusinessZone() {
        configure(5_000_000L, 15_000_000L, 50L, "not/a-zone");

        assertFalse(service.allows(profile, ContractorAllocationMode.LIVE, 100_000L));
        verify(allocationRepository, never()).dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void failsClosedForMissingDailyTotals() {
        when(allocationRepository.dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(null);

        assertFalse(service.allows(profile, ContractorAllocationMode.LIVE, 100_000L));
    }

    @Test
    void failsClosedWhenDatabaseClockCannotBeRead() {
        when(allocationRepository.currentDatabaseTime()).thenReturn(null);

        assertFalse(service.allows(profile, ContractorAllocationMode.LIVE, 100_000L));
        verify(allocationRepository, never()).dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void failsClosedWhenDailyTotalArithmeticOverflows() {
        when(allocationRepository.dailyRoutingTotalsForUpdate(
                anyLong(), any(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(totals(Long.MAX_VALUE, 1L));

        assertFalse(service.allows(profile, ContractorAllocationMode.LIVE, 100_000L));
    }

    private void configure(long maxSingle, long maxDailyAmount, long maxDailyCount, String zone) {
        ReflectionTestUtils.setField(service, "maxSingleInvoiceKopecksValue", Long.toString(maxSingle));
        ReflectionTestUtils.setField(service, "maxDailyProfileAmountKopecksValue", Long.toString(maxDailyAmount));
        ReflectionTestUtils.setField(service, "maxDailyProfileCountValue", Long.toString(maxDailyCount));
        ReflectionTestUtils.setField(service, "businessZoneId", zone);
    }

    private ContractorPaymentAllocationRepository.DailyRoutingTotals totals(long amount, long count) {
        return new ContractorPaymentAllocationRepository.DailyRoutingTotals() {
            @Override
            public Long getAmountKopecks() {
                return amount;
            }

            @Override
            public Long getRouteCount() {
                return count;
            }
        };
    }
}
