package com.hunt.otziv.client_messages.api;

/** Stops future payment reminders after an authorized manual payment report. */
public interface ManualPaymentAutomationControl {
    int closePaymentAutomationForManualSettlement(Long orderId, String reason);
}
