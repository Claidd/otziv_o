package com.hunt.otziv.payments.tochka.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.model.TochkaTaxSystemCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TochkaPaymentPropertiesTest {

    @Test
    void buildsExplicitAusnIncomeFiscalSettingsWithoutInventingAusnApiCode() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();
        properties.setTaxSystemCode("usn_income");
        properties.setPaymentModes(List.of("card", "sbp", "card"));

        var profile = properties.defaultProfile();

        assertEquals(TochkaTaxSystemCode.USN_INCOME, profile.taxSystemCode());
        assertEquals(List.of(TochkaPaymentMode.CARD, TochkaPaymentMode.SBP), profile.paymentModes());
    }

    @Test
    void leavesTaxSystemUnsetUntilAusnObjectIsConfirmed() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();

        assertNull(properties.defaultProfile().taxSystemCode());
    }

    @Test
    void rejectsNonexistentAusnApiEnum() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();
        properties.setTaxSystemCode("ausn");

        assertThrows(IllegalArgumentException.class, properties::defaultProfile);
    }

    @Test
    void selectsSandboxAndProductionBaseUrls() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();
        properties.setBaseUrl("https://enter.tochka.com/uapi/");
        properties.setSandboxBaseUrl("https://enter.tochka.com/sandbox/v2///");

        assertEquals("https://enter.tochka.com/uapi", properties.baseUrlFor(false));
        assertEquals("https://enter.tochka.com/sandbox/v2", properties.baseUrlFor(true));
    }

    @Test
    void bindsEnvironmentStyleDurationsModesAndBooleans() {
        Map<String, Object> values = Map.of(
                "otziv.payments.tochka.enabled", "true",
                "otziv.payments.tochka.test-mode", "false",
                "otziv.payments.tochka.client-id", "test-client-id",
                "otziv.payments.tochka.payment-modes", "card,sbp",
                "otziv.payments.tochka.link-ttl", "PT30M",
                "otziv.payments.tochka.connect-timeout", "PT2S",
                "otziv.payments.tochka.read-timeout", "PT20S"
        );
        TochkaPaymentProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("otziv.payments.tochka", Bindable.of(TochkaPaymentProperties.class))
                .orElseThrow(() -> new AssertionError("Tochka properties were not bound"));

        assertEquals(true, properties.isEnabled());
        assertEquals(false, properties.isTestMode());
        assertEquals("test-client-id", properties.getClientId());
        assertEquals(List.of("card", "sbp"), properties.getPaymentModes());
        assertEquals(Duration.ofMinutes(30), properties.getLinkTtl());
        assertEquals(Duration.ofSeconds(2), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(20), properties.getReadTimeout());
    }

    @Test
    void rejectsInfiniteOrUnboundedHttpTimeouts() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();
        properties.setConnectTimeout(Duration.ZERO);
        assertThrows(IllegalStateException.class, properties::requireValidTimeouts);

        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofMinutes(3));
        assertThrows(IllegalStateException.class, properties::requireValidTimeouts);
    }
}
