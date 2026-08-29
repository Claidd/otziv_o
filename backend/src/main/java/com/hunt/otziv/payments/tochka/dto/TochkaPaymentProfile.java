package com.hunt.otziv.payments.tochka.dto;

import com.hunt.otziv.payments.tochka.model.TochkaPaymentMethod;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentObject;
import com.hunt.otziv.payments.tochka.model.TochkaTaxSystemCode;
import com.hunt.otziv.payments.tochka.model.TochkaVatType;
import java.time.Duration;
import java.util.List;

public record TochkaPaymentProfile(
        Long id,
        String code,
        String name,
        boolean enabled,
        String customerCode,
        String merchantId,
        String jwtToken,
        String requiredCashbox,
        boolean testMode,
        TochkaTaxSystemCode taxSystemCode,
        TochkaVatType vatType,
        TochkaPaymentMethod paymentMethod,
        TochkaPaymentObject paymentObject,
        String measure,
        String receiptItemName,
        List<TochkaPaymentMode> paymentModes,
        Duration linkTtl
) {
    public TochkaPaymentProfile {
        code = safe(code);
        name = safe(name);
        customerCode = safe(customerCode);
        merchantId = safe(merchantId);
        jwtToken = safe(jwtToken);
        requiredCashbox = safe(requiredCashbox);
        measure = safe(measure);
        receiptItemName = safe(receiptItemName);
        paymentModes = paymentModes == null ? List.of() : List.copyOf(paymentModes);
    }

    public boolean hasCredentials() {
        return !customerCode.isBlank() && !merchantId.isBlank() && !jwtToken.isBlank();
    }

    public String displayName() {
        if (!name.isBlank()) {
            return name;
        }
        return code.isBlank() ? "Точка Банк" : code;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
