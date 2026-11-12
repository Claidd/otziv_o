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
import com.hunt.otziv.payments.repository.ManualPaymentPaperInvoiceReceiptProjection;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
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

    @Test
    void creditsBankDocumentPaymentsToTheOwnerOfTheManagerInsteadOfTheUnknownRecipient() {
        when(attributionRepository
                .findAllByEffectiveAtGreaterThanEqualAndEffectiveAtLessThanOrderByEffectiveAtAscIdAsc(
                        any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(paymentLinkRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of());
        ManualPaymentLegacyMonthlySourceProjection invoiceOfFirstManager =
                legacy(4L, 2_300_000L, LocalDateTime.of(2026, 11, 11, 17, 52));
        ManualPaymentLegacyMonthlySourceProjection invoiceOfSecondManager =
                legacy(5L, 600_000L, LocalDateTime.of(2026, 11, 12, 9, 0));
        ManualPaymentLegacyMonthlySourceProjection invoiceWithoutOwner =
                legacy(6L, 100_000L, LocalDateTime.of(2026, 11, 13, 9, 0));
        ManualPaymentLegacyMonthlySourceProjection oldManualInvoice =
                legacy(7L, 50_000L, LocalDateTime.of(2026, 11, 14, 9, 0));
        when(commonInvoiceRepository.findLegacyManualConfirmedForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(List.of(invoiceOfFirstManager, invoiceOfSecondManager, invoiceWithoutOwner, oldManualInvoice));
        // Invoice 7 is not a bank-document invoice: the repository does not return it.
        // As the database returns them: numbers of any width, an empty owner for a manager without one.
        List<Object[]> paperInvoices = List.of(
                new Object[] {4L, 10L, "Иван Хант"},
                new Object[] {java.math.BigInteger.valueOf(5), 9, "Мия Ригз"},
                new Object[] {6L, null, null}
        );
        when(commonInvoiceRepository.findPaperInvoiceOwnersForMonthlyRecipientSummary(
                Set.of(4L, 5L, 6L, 7L)
        )).thenReturn(paperInvoices);
        // An order of the first manager paid by bank documents joins the same owner.
        List<ManualPaymentPaperInvoiceReceiptProjection> paperOrders = List.of(
                paperReceipt(81L, 400_000L, LocalDateTime.of(2026, 11, 20, 12, 0), 10L, "Иван Хант"));
        when(paymentLinkRepository.findPaperInvoiceReceiptsForMonthlyRecipientSummary(
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(paperOrders);

        var result = service.summary("2026-11");

        assertEquals(4, result.totalRecipients());
        assertEquals(5, result.totalPayments());
        assertEquals(3_450_000L, result.totalAmountKopecks());
        ManualPaymentRecipientMonthlySummaryItem firstOwner = item(result.items(), "OWNER:PAPER_INVOICE:10");
        assertEquals("Владелец · Иван Хант (счёт и акт Точки)", firstOwner.accountingRecipientLabel());
        assertEquals(2_700_000L, firstOwner.amountKopecks());
        assertEquals(2L, firstOwner.paymentCount(), "the common invoice and the order of one owner add up");
        assertTrue(firstOwner.attributionKnown());
        assertEquals(ContractorCashDestinationKind.OWNER, firstOwner.accountingDestinationKind());
        assertEquals(ContractorRecipientType.OWNER, firstOwner.accountingRecipientType());
        ManualPaymentRecipientMonthlySummaryItem secondOwner = item(result.items(), "OWNER:PAPER_INVOICE:9");
        assertEquals("Владелец · Мия Ригз (счёт и акт Точки)", secondOwner.accountingRecipientLabel());
        assertEquals(600_000L, secondOwner.amountKopecks());
        ManualPaymentRecipientMonthlySummaryItem unnamedOwner = item(result.items(), "OWNER:PAPER_INVOICE");
        assertEquals("Владелец (счёт и акт Точки)", unnamedOwner.accountingRecipientLabel());
        assertTrue(unnamedOwner.attributionKnown(), "a bank-document payment never lands in the unknown recipient");
        ManualPaymentRecipientMonthlySummaryItem unknown = item(
                result.items(), ManualPaymentRecipientMonthlySummaryService.LEGACY_UNKNOWN_KEY);
        assertEquals(50_000L, unknown.amountKopecks());
        assertEquals(1L, unknown.paymentCount());
        assertFalse(unknown.attributionKnown());
    }

    private static ManualPaymentRecipientMonthlySummaryItem item(
            List<ManualPaymentRecipientMonthlySummaryItem> items,
            String key
    ) {
        return items.stream().filter(item -> key.equals(item.accountingRecipientKey())).findFirst().orElseThrow();
    }

    private ManualPaymentPaperInvoiceReceiptProjection paperReceipt(
            long linkId,
            long amountKopecks,
            LocalDateTime effectiveAt,
            Long ownerUserId,
            String ownerName
    ) {
        ManualPaymentPaperInvoiceReceiptProjection row = mock(ManualPaymentPaperInvoiceReceiptProjection.class);
        when(row.getSourceId()).thenReturn(linkId);
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        when(row.getEffectiveAt()).thenReturn(effectiveAt);
        when(row.getOwnerUserId()).thenReturn(ownerUserId);
        when(row.getOwnerName()).thenReturn(ownerName);
        return row;
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
