package com.hunt.otziv.payments.tochka.model;

import java.util.Arrays;
import java.util.Locale;

final class TochkaEnumParser {

    private TochkaEnumParser() {
    }

    static <E extends Enum<E> & TochkaCodeEnum> E required(Class<E> type, String value, String label) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(type.getEnumConstants())
                .filter(candidate -> candidate.code().equals(clean))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Некорректная настройка Точка API (" + label + "): " + clean
                ));
    }
}
