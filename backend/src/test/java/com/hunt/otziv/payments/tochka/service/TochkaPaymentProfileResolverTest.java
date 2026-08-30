package com.hunt.otziv.payments.tochka.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class TochkaPaymentProfileResolverTest {

    @Test
    void buildsRuntimeFromEntityIdentityAndGlobalTochkaConfigurationOnly() {
        TochkaPaymentProperties properties = configuredProperties();
        properties.setTestMode(false);
        properties.setPaymentModes(List.of("sbp"));
        RestTemplate transport = mock(RestTemplate.class);
        TochkaPaymentProfileResolver resolver = new TochkaPaymentProfileResolver(
                properties,
                new TochkaClient(transport, properties)
        );
        PaymentProfile entity = tochkaEntity(true);
        entity.setTerminalKey("tbank-terminal-must-not-be-used");
        entity.setPasswordEnvKey("OTZIV_PAYMENTS_TBANK_PASSWORD_MUST_NOT_BE_USED");
        entity.setTestMode(true);

        var runtime = resolver.resolve(entity);

        assertEquals(entity.getId(), runtime.id());
        assertEquals("tochka-primary", runtime.code());
        assertEquals("Профиль владельца", runtime.name());
        assertTrue(runtime.enabled());
        assertEquals("ABC123456", runtime.customerCode());
        assertEquals("123456789012345", runtime.merchantId());
        assertEquals("tochka-jwt", runtime.jwtToken());
        assertFalse(runtime.testMode());
        assertEquals(List.of(TochkaPaymentMode.SBP), runtime.paymentModes());
        assertFalse(runtime.merchantId().contains("tbank-terminal"));
        assertFalse(runtime.jwtToken().contains("TBANK_PASSWORD"));
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsDisabledEntityThroughClientValidation() {
        TochkaPaymentProperties properties = configuredProperties();
        TochkaPaymentProfileResolver resolver = new TochkaPaymentProfileResolver(
                properties,
                new TochkaClient(mock(RestTemplate.class), properties)
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> resolver.resolve(tochkaEntity(false))
        );

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("выключен"));
    }

    @Test
    void resolvesExistingPaymentIdentityAfterGlobalAndProfileDisablement() {
        TochkaPaymentProperties properties = configuredProperties();
        properties.setEnabled(false);
        properties.setTestMode(false);
        RestTemplate transport = mock(RestTemplate.class);
        TochkaPaymentProfileResolver resolver = new TochkaPaymentProfileResolver(
                properties,
                new TochkaClient(transport, properties)
        );
        PaymentProfile entity = tochkaEntity(false);
        entity.setTestMode(false);

        var runtime = resolver.resolveForExistingPayment(entity);

        assertFalse(runtime.enabled());
        assertFalse(runtime.testMode());
        assertEquals("ABC123456", runtime.customerCode());
        assertEquals("123456789012345", runtime.merchantId());
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsTbankAndCodeMismatchBeforeCallingTochkaClient() {
        TochkaPaymentProperties properties = configuredProperties();
        TochkaClient client = mock(TochkaClient.class);
        TochkaPaymentProfileResolver resolver = new TochkaPaymentProfileResolver(properties, client);

        PaymentProfile tbank = tochkaEntity(true);
        tbank.setProvider(PaymentProfile.PROVIDER_TBANK);
        PaymentProfile mismatched = tochkaEntity(true);
        mismatched.setCode("another-tochka-profile");

        ResponseStatusException providerError = assertThrows(
                ResponseStatusException.class,
                () -> resolver.resolve(tbank)
        );
        ResponseStatusException codeError = assertThrows(
                ResponseStatusException.class,
                () -> resolver.resolve(mismatched)
        );

        assertEquals(409, providerError.getStatusCode().value());
        assertEquals(409, codeError.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test
    void requiresGlobalEnablementAndCredentialsThroughClient() {
        TochkaPaymentProperties disabled = configuredProperties();
        disabled.setEnabled(false);
        TochkaPaymentProfileResolver disabledResolver = new TochkaPaymentProfileResolver(
                disabled,
                new TochkaClient(mock(RestTemplate.class), disabled)
        );

        ResponseStatusException disabledError = assertThrows(
                ResponseStatusException.class,
                () -> disabledResolver.resolve(tochkaEntity(true))
        );

        TochkaPaymentProperties incomplete = configuredProperties();
        incomplete.setJwtToken(" ");
        TochkaPaymentProfileResolver incompleteResolver = new TochkaPaymentProfileResolver(
                incomplete,
                new TochkaClient(mock(RestTemplate.class), incomplete)
        );
        ResponseStatusException credentialsError = assertThrows(
                ResponseStatusException.class,
                () -> incompleteResolver.resolve(tochkaEntity(true))
        );

        assertEquals(409, disabledError.getStatusCode().value());
        assertTrue(disabledError.getReason().contains("выключен"));
        assertEquals(409, credentialsError.getStatusCode().value());
        assertTrue(credentialsError.getReason().contains("JWT"));
    }

    private TochkaPaymentProperties configuredProperties() {
        TochkaPaymentProperties properties = new TochkaPaymentProperties();
        properties.setEnabled(true);
        properties.setProfileCode("tochka-primary");
        properties.setCustomerCode("ABC123456");
        properties.setMerchantId("123456789012345");
        properties.setJwtToken("tochka-jwt");
        return properties;
    }

    private PaymentProfile tochkaEntity(boolean enabled) {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(81L);
        profile.setCode(" tochka-primary ");
        profile.setProvider(" tochka ");
        profile.setName(" Профиль владельца ");
        profile.setEnabled(enabled);
        return profile;
    }
}
