package com.hunt.otziv.payments.api;

/** Stops collection on a paper document when its debt is paid by a different, verified bank operation. */
public interface AlternatePaperReceipt {
    boolean closeUnpaidDocument(String kind, long targetId, String documentId, long amountKopecks);
}
