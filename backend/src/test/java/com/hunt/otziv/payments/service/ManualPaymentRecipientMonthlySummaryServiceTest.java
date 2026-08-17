package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryItem;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.ManualPaymentLegacyMonthlySourceProjection;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualPaymentRecipientMonthlySummaryServiceTest {

    @Mock
    private ContractorActualPaymentAttributionRepository attributionRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;

    private ManualPaymentRecipientMonthlySummaryService service;

    @BeforeEach
    void setUp() {
        service = new ManualPaymentRecipientMonthlySummaryService(
                attributionRepository,
                paymentLinkRepository,
                commonInvoiceRepository
        );
    }

    @Test
    void aggregatesRowsByActualDestinationAndCountsDistinctSources() {
        LocalDateTime first = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime second = LocalDateTime.of(2026, 8, 7, 12, 0);
        List<ContractorActualPaymentAttribution> rows = List.of(
                profileRow(ContractorActualPaymentSourceKind.PAYMENT_LINK, 10L, 1_000L, first),
                profileRow(ContractorActualPaymentSourceKind.PAYMENT_LINK, 10L, 500L, first),
                profileRow(ContractorActualPaymentSourceKind.COMMON_INVOICE, 20L, 2_000L, second),
                workerTaskRow(ContractorActualPaymentSourceKind.PAYMENT_LINK, 30L, 250L, second),
                taskRow(ContractorActualPaymentSourceKind.PAYMENT_LINK, 10L, 2_500L, second)
        );
        when(attributionRepository
                .findAllByEffectiveAtGreaterThanEqualAndEffectiveAtLessThanOrderByEffectiveAtAscIdAsc(
                        any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(rows);
        when(paymentLinkRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of());
        when(commonInvoiceRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of());

        var result = service.summary("2026-08");

        assertEquals(2, result.totalRecipients());
        assertEquals(3, result.totalPayments(), "split source must be counted once globally");
        assertEquals(6_250L, result.totalAmountKopecks());
        ManualPaymentRecipientMonthlySummaryItem profile = result.items().stream()
                .filter(item -> "PROFILE:7".equals(item.accountingRecipientKey()))
                .findFirst().orElseThrow();
        assertEquals(3_750L, profile.amountKopecks());
        assertEquals(3L, profile.paymentCount(), "direct and task receipts aggregate for one worker");
        assertEquals("Специалист Наталья", profile.accountingRecipientLabel());
        ManualPaymentRecipientMonthlySummaryItem task = result.items().stream()
                .filter(item -> "TASK:91:3".equals(item.accountingRecipientKey()))
                .findFirst().orElseThrow();
        assertEquals(1L, task.paymentCount());
        assertTrue(task.accountingRecipientLabel().contains("задание №91"));
        assertTrue(task.accountingRecipientLabel().contains("Имя из банковских реквизитов"));
        assertTrue(result.items().stream().allMatch(item -> item.manualPaymentUrl().isEmpty()));
        assertTrue(result.items().stream().allMatch(item -> item.manualPhone().isEmpty()));
    }

    @Test
    void aggregatesLegacyPaymentLinksAndCommonInvoicesIntoExplicitUnknownRecipient() {
        when(attributionRepository
                .findAllByEffectiveAtGreaterThanEqualAndEffectiveAtLessThanOrderByEffectiveAtAscIdAsc(
                        any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        ManualPaymentLegacyMonthlySourceProjection legacyLink =
                legacy(31L, 1_200L, LocalDateTime.of(2026, 8, 2, 9, 0));
        ManualPaymentLegacyMonthlySourceProjection legacyInvoice =
                legacy(44L, 2_300L, LocalDateTime.of(2026, 8, 3, 11, 0));
        when(paymentLinkRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(legacyLink));
        when(commonInvoiceRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(legacyInvoice));

        var result = service.summary("2026-08");

        assertEquals(1, result.totalRecipients());
        assertEquals(2, result.totalPayments());
        ManualPaymentRecipientMonthlySummaryItem item = result.items().getFirst();
        assertEquals(ManualPaymentRecipientMonthlySummaryService.LEGACY_UNKNOWN_KEY,
                item.accountingRecipientKey());
        assertEquals(ManualPaymentRecipientMonthlySummaryService.LEGACY_UNKNOWN_LABEL,
                item.accountingRecipientLabel());
        assertFalse(item.attributionKnown());
        assertEquals(2L, item.paymentCount());
        assertEquals(3_500L, item.amountKopecks());
        assertEquals("", item.manualPaymentUrl());
    }

    private ContractorActualPaymentAttribution profileRow(
            ContractorActualPaymentSourceKind sourceKind,
            long sourceId,
            long amountKopecks,
            LocalDateTime effectiveAt
    ) {
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getSourceKind()).thenReturn(sourceKind);
        when(row.getSourceId()).thenReturn(sourceId);
        when(row.getActualCashDestinationKind()).thenReturn(ContractorCashDestinationKind.CONTRACTOR_PROFILE);
        when(row.getActualRecipientType()).thenReturn(ContractorRecipientType.SPECIALIST);
        when(row.getActualRecipientProfileId()).thenReturn(7L);
        when(row.getActualRecipientNameSnapshot()).thenReturn("Специалист Наталья");
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        when(row.getEffectiveAt()).thenReturn(effectiveAt);
        return row;
    }

    private ContractorActualPaymentAttribution taskRow(
            ContractorActualPaymentSourceKind sourceKind,
            long sourceId,
            long amountKopecks,
            LocalDateTime effectiveAt
    ) {
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getSourceKind()).thenReturn(sourceKind);
        when(row.getSourceId()).thenReturn(sourceId);
        when(row.getActualCashDestinationKind()).thenReturn(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(row.getActualManualPaymentTaskId()).thenReturn(91L);
        when(row.getActualManualPaymentTaskGeneration()).thenReturn(3L);
        when(row.getActualManualPaymentTaskTargetKind())
                .thenReturn(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        when(row.getActualRecipientNameSnapshot()).thenReturn("Имя из банковских реквизитов");
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        when(row.getEffectiveAt()).thenReturn(effectiveAt);
        return row;
    }

    private ContractorActualPaymentAttribution workerTaskRow(
            ContractorActualPaymentSourceKind sourceKind,
            long sourceId,
            long amountKopecks,
            LocalDateTime effectiveAt
    ) {
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getSourceKind()).thenReturn(sourceKind);
        when(row.getSourceId()).thenReturn(sourceId);
        when(row.getActualCashDestinationKind()).thenReturn(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        when(row.getActualManualPaymentTaskId()).thenReturn(92L);
        when(row.getActualManualPaymentTaskGeneration()).thenReturn(1L);
        when(row.getActualManualPaymentTaskTargetKind())
                .thenReturn(ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        when(row.getActualRecipientProfileId()).thenReturn(7L);
        when(row.getActualRecipientNameSnapshot()).thenReturn("Специалист Наталья");
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        when(row.getEffectiveAt()).thenReturn(effectiveAt);
        return row;
    }

    private ManualPaymentLegacyMonthlySourceProjection legacy(
            long sourceId,
            long amountKopecks,
            LocalDateTime effectiveAt
    ) {
        ManualPaymentLegacyMonthlySourceProjection row = mock(ManualPaymentLegacyMonthlySourceProjection.class);
        when(row.getSourceId()).thenReturn(sourceId);
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        when(row.getEffectiveAt()).thenReturn(effectiveAt);
        return row;
    }
}
