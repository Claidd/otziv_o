package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReturnRecoveryStateTest {

    @Test
    void mutableTestProfileAloneIsNotAcceptedAsHistoricalProof() {
        PaymentProfile profile = new PaymentProfile();
        profile.setTestMode(true);
        PaymentLink link = new PaymentLink();
        link.setPaymentProfile(profile);

        assertFalse(PaymentReturnRecoveryState.isTestPayment(link));
    }

    @Test
    void detectsLegacyDemoTerminalWithoutProfile() {
        PaymentLink link = new PaymentLink();
        link.setTbankTerminalKey("1779443245436demo ");

        assertTrue(PaymentReturnRecoveryState.isTestPayment(link));
    }

    @Test
    void detectsCanceledLinkThatOriginatedFromTestConfirmation() {
        PaymentLink link = new PaymentLink();
        link.setTbankTerminalKey("LIVE-TERMINAL");
        link.setBankCancelOriginStatus(PaymentLinkStatus.TEST_CONFIRMED);

        assertTrue(PaymentReturnRecoveryState.isTestPayment(link));
    }

    @Test
    void liveProfileAndTerminalAreNotTestPayment() {
        PaymentProfile profile = new PaymentProfile();
        profile.setTestMode(false);
        profile.setTerminalKey("LIVE-TERMINAL");
        PaymentLink link = new PaymentLink();
        link.setPaymentProfile(profile);
        link.setTbankTerminalKey("LIVE-TERMINAL");

        assertFalse(PaymentReturnRecoveryState.isTestPayment(link));
    }

    @Test
    void realManualTransferIsNeverClassifiedByBankProfileTestMode() {
        PaymentProfile profile = new PaymentProfile();
        profile.setTestMode(true);
        profile.setTerminalKey("1779443245436DEMO");
        PaymentLink link = new PaymentLink();
        link.setPaymentProfile(profile);
        link.setTbankTerminalKey("1779443245436DEMO");
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);

        assertFalse(PaymentReturnRecoveryState.isTestPayment(link));
    }
}
