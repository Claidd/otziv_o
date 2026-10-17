package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ManualPaymentRecipientMonthlyArchiveContractTest {

    @Test
    void legacyMonthlyFactsRemainCanonicalAcrossArchiveAndTypedCommonBatches() throws Exception {
        String paymentLinks = Files.readString(Path.of(
                "src/main/java/com/hunt/otziv/payments/repository/PaymentLinkRepository.java"));
        String commonInvoices = Files.readString(Path.of(
                "src/main/java/com/hunt/otziv/common_billing/repository/CommonInvoiceRepository.java"));
        String commonItem = Files.readString(Path.of(
                "src/main/java/com/hunt/otziv/common_billing/model/CommonInvoiceOrder.java"));

        assertTrue(paymentLinks.contains("FROM payment_links link"));
        assertTrue(paymentLinks.contains("FROM archive_payment_links archived"));
        assertTrue(paymentLinks.contains("FROM payment_links live WHERE live.id = archived.id"));
        assertTrue(paymentLinks.contains("contractor_actual_payment_attributions attribution"));
        assertTrue(paymentLinks.contains("attribution.source_kind = 'PAYMENT_LINK'"));
        assertTrue(paymentLinks.contains("source.manual_confirmed_at IS NOT NULL"));
        assertTrue(paymentLinks.contains("source.status = 'CONFIRMED' AND source.paid_at IS NOT NULL"));
        assertTrue(paymentLinks.contains("JOIN contractor_payment_allocation_events event"));
        assertTrue(paymentLinks.contains("event.effective_at AS effectiveAt"));
        assertTrue(paymentLinks.contains("allocation.mode = 'LIVE' AND event.event_type = 'CONFIRMED'"));
        assertTrue(paymentLinks.contains("allocation.mode = 'SHADOW' AND event.event_type = 'SIMULATED_CONFIRMED'"));
        assertTrue(paymentLinks.contains("HAVING SUM(amountKopecks) = MAX(expectedKopecks)"));

        assertTrue(commonInvoices.contains("FROM archive_common_invoice_orders archived_item"));
        assertTrue(commonInvoices.contains("archived_invoice.restored_at IS NULL"));
        assertTrue(commonInvoices.contains("live_item.invoice_order_id = archived_item.invoice_order_id"));
        assertTrue(commonInvoices.contains("item.actual_payment_evidence_reference IS NULL"));
        assertTrue(commonInvoices.contains("archived_item.actual_payment_evidence_reference IS NULL"));
        assertTrue(commonInvoices.contains("item.source_payment_link_id IS NULL"));
        assertTrue(commonInvoices.contains("archived_item.source_payment_link_id IS NULL"));

        assertTrue(commonItem.contains("actual_payment_evidence_reference"));
    }
}
