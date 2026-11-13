package com.hunt.otziv.common_billing.api;

import java.security.Principal;

/** Redeems the button delivered for one manager report in its intended Telegram conversation. */
public interface CommonOwnerReceiptConfirmation {
    String PREFIX = "common-owner:";

    record Result(long invoiceId, boolean alreadyConfirmed) {}

    Result confirm(String token, long chatId, Principal actor);
}
