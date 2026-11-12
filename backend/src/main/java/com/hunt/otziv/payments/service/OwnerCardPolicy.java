package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;

/**
 * May the owner of a bank profile take a client's transfer to a card at all. A self-employed owner may:
 * the profile is set to «карта до лимита, затем банк» and holds the requisites. An owner who must put
 * every payment through the bank (an individual entrepreneur) has a bank-only profile, and no way of the
 * application records a card transfer to such an owner. The monthly limit is a separate question: it
 * decides whether a new invoice may be issued on the card, not whether a transfer that has already
 * arrived may be recorded.
 */
public final class OwnerCardPolicy {

    private OwnerCardPolicy() {
    }

    public static boolean allows(PaymentProfile profile) {
        if (profile == null || profile.getPaymentPolicy() != PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK) {
            return false;
        }
        ManualPaymentType type = profile.getManualPaymentType() == null
                ? ManualPaymentType.MOBILE_BANK : profile.getManualPaymentType();
        if (type == ManualPaymentType.MOBILE_BANK) {
            return !blank(profile.getManualPhone()) && !blank(profile.getManualRecipientName());
        }
        // The same reading as when an invoice is issued on these requisites (CommonInvoiceRouteSelector).
        return !blank(PaymentUrlPolicy.safeOrDefault(profile.getManualPaymentUrl(),
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL, PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
