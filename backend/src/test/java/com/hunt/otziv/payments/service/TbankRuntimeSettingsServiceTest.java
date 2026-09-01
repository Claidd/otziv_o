package com.hunt.otziv.payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.TbankRuntimeSettingsResponse;
import com.hunt.otziv.payments.dto.UpdateTbankRuntimeSettingsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TbankRuntimeSettingsServiceTest {

    @Mock
    private AppSettingService appSettingService;

    private TbankRuntimeSettingsService service;

    @BeforeEach
    void setUp() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        when(appSettingService.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(appSettingService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new TbankRuntimeSettingsService(appSettingService, properties);
    }

    @Test
    void providerNeutralBankLinksDoNotRequireTbankApiWhenAnotherBankProfileIsUsed() {
        TbankRuntimeSettingsResponse response = service.update(new UpdateTbankRuntimeSettingsRequest(
                "LIVE",
                false,
                true,
                true,
                true,
                BankPaymentInstructionSource.BANK_LINK,
                "SBP_PRIMARY",
                false,
                false,
                false
        ));

        assertFalse(response.tbankEnabled());
        assertTrue(response.clientTbankEnabled());
        assertEquals(BankPaymentInstructionSource.BANK_LINK, response.paymentInstructionSource());
        verify(appSettingService).setString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                BankPaymentInstructionSource.BANK_LINK
        );
    }

    @Test
    void legacyBooleanBankSourceEndpointStoresTheProviderNeutralAlias() {
        when(appSettingService.getBoolean(
                AppSettingService.PAYMENTS_TBANK_MANAGER_UI_ENABLED,
                false
        )).thenReturn(true);

        TbankRuntimeSettingsResponse response = service.updateClientPaymentSource(true);

        assertEquals(BankPaymentInstructionSource.BANK_LINK, response.paymentInstructionSource());
        verify(appSettingService).setString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                BankPaymentInstructionSource.BANK_LINK
        );
    }
}
