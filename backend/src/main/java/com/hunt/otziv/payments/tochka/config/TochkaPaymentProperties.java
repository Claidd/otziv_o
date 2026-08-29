package com.hunt.otziv.payments.tochka.config;

import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMethod;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentObject;
import com.hunt.otziv.payments.tochka.model.TochkaTaxSystemCode;
import com.hunt.otziv.payments.tochka.model.TochkaVatType;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "otziv.payments.tochka")
public class TochkaPaymentProperties {

    public static final String PRODUCTION_BASE_URL = "https://enter.tochka.com/uapi";
    public static final String SANDBOX_BASE_URL = "https://enter.tochka.com/sandbox/v2";
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(2);

    private boolean enabled = false;
    private boolean testMode = true;
    private String baseUrl = PRODUCTION_BASE_URL;
    private String sandboxBaseUrl = SANDBOX_BASE_URL;
    private String profileCode = "tochka-primary";
    private String profileName = "Точка Банк";
    private String customerCode = "";
    private String merchantId = "";
    private String clientId = "";
    private String jwtToken = "";
    private String requiredCashbox = "";
    private String taxSystemCode = "";
    private String vatType = "none";
    private String paymentMethod = "full_payment";
    private String paymentObject = "service";
    private String measure = "шт.";
    private String receiptItemName = "Репутационное сопровождение компании в сети Интернет";
    private List<String> paymentModes = List.of("card", "sbp");
    private Duration linkTtl = Duration.ofDays(7);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);

    public String baseUrlFor(boolean sandbox) {
        return trimTrailingSlash(sandbox ? sandboxBaseUrl : baseUrl);
    }

    public TochkaPaymentProfile defaultProfile() {
        return new TochkaPaymentProfile(
                null,
                profileCode,
                profileName,
                enabled,
                customerCode,
                merchantId,
                jwtToken,
                requiredCashbox,
                testMode,
                TochkaTaxSystemCode.fromCodeOrNull(taxSystemCode),
                TochkaVatType.fromCode(vatType),
                TochkaPaymentMethod.fromCode(paymentMethod),
                TochkaPaymentObject.fromCode(paymentObject),
                measure,
                receiptItemName,
                parsePaymentModes(paymentModes),
                linkTtl
        );
    }

    @AssertTrue(message = "Таймауты Точка API должны быть положительными и ограниченными")
    public boolean isTimeoutConfigurationValid() {
        return within(connectTimeout, MIN_TIMEOUT, MAX_CONNECT_TIMEOUT)
                && within(readTimeout, MIN_TIMEOUT, MAX_READ_TIMEOUT);
    }

    public void requireValidTimeouts() {
        if (!isTimeoutConfigurationValid()) {
            throw new IllegalStateException(
                    "Таймауты Точка API: connect должен быть 100ms..30s, read — 100ms..2m"
            );
        }
    }

    private List<TochkaPaymentMode> parsePaymentModes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(TochkaPaymentMode::fromCode)
                .distinct()
                .toList();
    }

    private String trimTrailingSlash(String value) {
        String clean = value == null ? "" : value.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private boolean within(Duration value, Duration minimum, Duration maximum) {
        return value != null
                && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0;
    }
}
