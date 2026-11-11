package com.hunt.otziv.common_billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Final statement evidence for money received outside the frozen payment
 * route. The rows deliberately describe the people who actually received the
 * money, not the requisites that were originally shown to the client.
 *
 * <p>{@code effectiveAt} is kept in the contract for installed clients, but its value is not trusted:
 * the server replaces it with its own clock (see {@code CommonInvoiceManualPaymentWorkflow.withServerReceiptTime}).</p>
 */
public record CommonManualPaymentAttributionRequest(
        @NotBlank @Size(max = 48) @Pattern(regexp = "[A-Za-z0-9._-]+") String idempotencyKey,
        @AssertTrue Boolean finalAccountingAcknowledged,
        @AssertTrue Boolean paymentReceived,
        @NotNull LocalDateTime effectiveAt,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 1024) String receiptUrl,
        @NotEmpty List<@Valid CommonManualPaymentAttributionRowRequest> attributions
) {
    /** The same request with the receipt time decided by the server. */
    public CommonManualPaymentAttributionRequest withEffectiveAt(LocalDateTime receiptTime) {
        return new CommonManualPaymentAttributionRequest(idempotencyKey, finalAccountingAcknowledged, paymentReceived,
                receiptTime, reason, receiptUrl, attributions);
    }
}
