package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.payments.model.InvoicePaymentMode;

/** Pure predicates shared by invoice presentation and payment workflows. */
final class CommonInvoiceRouteState {
    private CommonInvoiceRouteState() {}

    static boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return invoice != null && invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE;
    }

    static boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return invoice != null && invoice.getPaymentRouteSelectedAt() != null
                && invoice.getPaymentRouteType() != null && !invoice.getPaymentRouteType().trim().isBlank();
    }

    static void clearPaymentRoute(CommonInvoice invoice) {
        invoice.setPaymentRouteType(null);
        invoice.setPaymentRouteProfileId(null);
        invoice.setPaymentRouteProfileCode(null);
        invoice.setPaymentRouteProfileName(null);
        invoice.setContractorAllocationId(null);
        invoice.setPaymentRouteTerminalKey(null);
        invoice.setPaymentRouteManualSource(null);
        invoice.setPaymentRouteManualTaskId(null);
        invoice.setPaymentRouteManualTaskSourceGeneration(null);
        invoice.setPaymentRouteManualTaskGeneration(null);
        invoice.setPaymentRouteManualTaskAccountingMode(null);
        invoice.setPaymentRouteManualType(null);
        invoice.setPaymentRouteManualPhone(null);
        invoice.setPaymentRouteManualRecipient(null);
        invoice.setPaymentRouteManualBankName(null);
        invoice.setPaymentRouteManualUrl(null);
        invoice.setPaymentRouteManualButton(null);
        invoice.setPaymentRouteManualComment(null);
        invoice.setPaymentRouteInstructionText(null);
        invoice.setPaymentRouteAmountKopecks(null);
        invoice.setPaymentRouteSelectedAt(null);
    }
}
