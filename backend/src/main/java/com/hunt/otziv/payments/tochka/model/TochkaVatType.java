package com.hunt.otziv.payments.tochka.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TochkaVatType implements TochkaCodeEnum {
    NONE("none"),
    VAT_0("vat0"),
    VAT_5("vat5"),
    VAT_7("vat7"),
    VAT_10("vat10"),
    VAT_22("vat22"),
    VAT_105("vat105"),
    VAT_107("vat107"),
    VAT_110("vat110"),
    VAT_122("vat122");

    private final String code;

    TochkaVatType(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    public static TochkaVatType fromCode(String value) {
        return TochkaEnumParser.required(TochkaVatType.class, value, "ставка НДС");
    }
}
