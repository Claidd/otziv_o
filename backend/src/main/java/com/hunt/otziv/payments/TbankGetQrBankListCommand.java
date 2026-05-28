package com.hunt.otziv.payments;

public record TbankGetQrBankListCommand(
        String scenarioType,
        String deviceType,
        String os
) {
}
