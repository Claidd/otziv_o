package com.hunt.otziv.payments.tochka.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TochkaTaxSystemCode implements TochkaCodeEnum {
    OSN("osn"),
    USN_INCOME("usn_income"),
    USN_INCOME_OUTCOME("usn_income_outcome"),
    ESN("esn"),
    PATENT("patent");

    private final String code;

    TochkaTaxSystemCode(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    public static TochkaTaxSystemCode fromCodeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return TochkaEnumParser.required(TochkaTaxSystemCode.class, value, "система налогообложения");
    }
}
