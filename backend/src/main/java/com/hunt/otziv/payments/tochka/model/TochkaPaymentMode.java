package com.hunt.otziv.payments.tochka.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TochkaPaymentMode implements TochkaCodeEnum {
    SBP("sbp"),
    CARD("card"),
    TINKOFF("tinkoff"),
    DOLYAME("dolyame");

    private final String code;

    TochkaPaymentMode(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    public static TochkaPaymentMode fromCode(String value) {
        return TochkaEnumParser.required(TochkaPaymentMode.class, value, "способ оплаты");
    }

    /** Digital rubles use an SBP-created link, but remain a distinct provider payment type. */
    public static boolean matchesOperationType(String actualType, String expectedModeCode, String status) {
        return (expectedModeCode != null && expectedModeCode.equals(actualType))
                || (SBP.code.equals(expectedModeCode)
                && "digitalRuble".equals(actualType)
                && ("APPROVED".equals(status)
                    || "ON-REFUND".equals(status)
                    || "REFUNDED".equals(status)
                    || "REFUNDED_PARTIALLY".equals(status)));
    }
}
