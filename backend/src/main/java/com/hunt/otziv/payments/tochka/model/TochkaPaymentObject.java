package com.hunt.otziv.payments.tochka.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TochkaPaymentObject implements TochkaCodeEnum {
    GOODS("goods"),
    SERVICE("service"),
    WORK("work");

    private final String code;

    TochkaPaymentObject(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    public static TochkaPaymentObject fromCode(String value) {
        return TochkaEnumParser.required(TochkaPaymentObject.class, value, "признак предмета расчета");
    }
}
