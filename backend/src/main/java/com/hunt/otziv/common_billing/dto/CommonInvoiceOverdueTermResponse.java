package com.hunt.otziv.common_billing.dto;

import java.time.LocalDateTime;

/**
 * The invoice's payment term before the automatic move to "Не оплачено": when it started (sending,
 * or delivery of a bank paper invoice), the manager's extension, the earliest automatic move and a
 * short explanation for the card.
 */
public record CommonInvoiceOverdueTermResponse(
        long invoiceId,
        int overdueDays,
        int extensionDays,
        String extensionBy,
        LocalDateTime extensionAt,
        LocalDateTime termStart,
        boolean paperDelivery,
        LocalDateTime automaticMoveNotBefore,
        boolean editable,
        String note
) {
}
