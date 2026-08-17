package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One immutable part of a manually verified common-invoice receipt. */
public record CommonManualPaymentAttributionRowRequest(
        @NotBlank @Size(max = 48) @Pattern(regexp = "[A-Za-z0-9._-]+") String rowKey,
        @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9:._-]+") String recipientKey,
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        @Positive long amountKopecks
) {
    public CommonManualPaymentAttributionRowRequest(
            String rowKey, ContractorRecipientType recipientType, Long recipientProfileId, long amountKopecks
    ) {
        this(rowKey,
                recipientType == ContractorRecipientType.OWNER ? "OWNER" : "PROFILE:" + recipientProfileId,
                recipientType, recipientProfileId, amountKopecks);
    }
}
