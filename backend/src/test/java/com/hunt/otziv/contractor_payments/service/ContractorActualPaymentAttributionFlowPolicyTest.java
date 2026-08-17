package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ContractorActualPaymentAttributionFlowPolicyTest {

    private final ContractorActualPaymentAttributionService attributionService =
            mock(ContractorActualPaymentAttributionService.class);
    private final ContractorActualPaymentAttributionFlowPolicy policy =
            new ContractorActualPaymentAttributionFlowPolicy(attributionService);

    @Test
    void explicitOffKeepsOldManualRoutesAndRejectsNewAttributionRoute() {
        when(attributionService.actualRecipientAccountingRequired()).thenReturn(false);

        assertFalse(policy.attributionRequired());
        assertDoesNotThrow(policy::requireLegacyFlow);
        assertThrows(ResponseStatusException.class, policy::requireAttributionFlow);
    }

    @Test
    void lockedExplicitOffKeepsLegacyWriteEnabled() {
        when(attributionService.actualRecipientAccountingEnabled()).thenReturn(false);

        assertDoesNotThrow(policy::requireLegacyFlowLocked);
    }

    @Test
    void lockedEnabledAccountingRejectsLegacyWrite() {
        when(attributionService.actualRecipientAccountingEnabled()).thenReturn(true);

        assertThrows(ResponseStatusException.class, policy::requireLegacyFlowLocked);
    }

    @Test
    void shadowEnabledOrLiveRequiresNewAttributionAndRejectsLegacyRoute() {
        when(attributionService.actualRecipientAccountingRequired()).thenReturn(true);

        assertTrue(policy.attributionRequired());
        assertDoesNotThrow(policy::requireAttributionFlow);
        assertThrows(ResponseStatusException.class, policy::requireLegacyFlow);
    }

    @Test
    void configurationReadFailureFailsClosed() {
        when(attributionService.actualRecipientAccountingRequired())
                .thenThrow(new IllegalStateException("drift"));

        assertTrue(policy.attributionRequired());
        assertThrows(ResponseStatusException.class, policy::requireLegacyFlow);
    }
}