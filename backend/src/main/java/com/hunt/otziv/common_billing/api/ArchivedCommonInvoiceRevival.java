package com.hunt.otziv.common_billing.api;

/**
 * Read-only check used by the manager control board.
 *
 * <p>Owner: common billing. Caller: manager control diagnostics (no authorization of its own; the
 * board already limits which invoices a manager sees). Transaction: joins the caller's transaction or
 * reads without one; it never changes data. The change itself stays inside common billing.
 */
public interface ArchivedCommonInvoiceRevival {

    /**
     * True when a manually archived invoice whose orders were returned to work turned into a
     * "payable changed" attention case without any payment, delivery or payment-link evidence,
     * so it can safely return to collecting.
     */
    boolean revivable(Long invoiceId);
}
