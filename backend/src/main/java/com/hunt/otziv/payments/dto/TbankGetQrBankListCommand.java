package com.hunt.otziv.payments.dto;

public record TbankGetQrBankListCommand(
        String scenarioType,
        String deviceType,
        String os
) {
}
