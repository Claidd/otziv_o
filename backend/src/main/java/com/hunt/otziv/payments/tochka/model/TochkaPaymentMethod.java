package com.hunt.otziv.payments.tochka.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TochkaPaymentMethod implements TochkaCodeEnum {
    FULL_PAYMENT("full_payment"),
    FULL_PREPAYMENT("full_prepayment");

    private final String code;

    TochkaPaymentMethod(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    public static TochkaPaymentMethod fromCode(String value) {
        return TochkaEnumParser.required(TochkaPaymentMethod.class, value, "признак способа расчета");
    }
}
