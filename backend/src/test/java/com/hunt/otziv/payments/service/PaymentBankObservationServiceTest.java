package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentBankObservationServiceTest {

    @Mock private TbankRuntimeSettingsService runtimeSettingsService;
    @Mock private PaymentProfileService paymentProfileService;
    @Mock private TbankClient tbankClient;
    @Mock private TochkaPaymentProfileResolver tochkaPaymentProfileResolver;
    @Mock private TochkaClient tochkaClient;
    @Mock private TochkaPaymentOperationMapper tochkaPaymentOperationMapper;
    @InjectMocks private PaymentBankObservationService service;

    @Test
    void paidLinkIsProbedByScheduledReconciliationButNeverByPublicPageReload() {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(3L);
        PaymentLink link = new PaymentLink();
        link.setId(9L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setPaymentProfile(profile);
        link.setTbankOrderId("order-7");
        link.setTbankPaymentId("payment-9");
        link.setTbankTerminalKey("terminal-3");
        link.setAmountKopecks(140_000L);
        TbankPaymentProfile runtime = new TbankPaymentProfile(3L, "primary", "Bank", true,
                "terminal-3", "password", false);
        TbankGetStateResponse returned = new TbankGetStateResponse(true, "0", null, null,
                "terminal-3", "REFUNDED", "payment-9", "order-7", 140_000L);
        when(runtimeSettingsService.isTbankEnabled()).thenReturn(true);
        when(paymentProfileService.toRuntimeForTerminal(profile, "terminal-3")).thenReturn(runtime);
        when(tbankClient.getState(runtime, "payment-9")).thenReturn(returned);

        assertFalse(service.observePublicBankState(link).attempted());
        assertInstanceOf(PaymentBankObservationService.BankStateObservation.class,
                service.observeBankStateForReconciliation(link));
        verify(tbankClient).getState(runtime, "payment-9");
        verify(tochkaClient, never()).getPaymentInfo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void paidTochkaLinkIsProbedByScheduledReconciliationForLatePartialRefund() {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(3L);
        profile.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        PaymentLink link = new PaymentLink();
        link.setId(10L);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setPaymentProfile(profile);
        link.setTbankOrderId("order-7");
        link.setTbankPaymentId("payment-10");
        link.setTbankTerminalKey("merchant-3");
        link.setAmountKopecks(140_000L);
        TochkaPaymentProfile runtime = new TochkaPaymentProfile(
                3L, "tochka", "Tochka", true, "customer", "merchant-3", "jwt", "", false,
                null, null, null, null, "", "", List.of(TochkaPaymentMode.SBP), Duration.ofMinutes(30));
        PaymentOperation operation = new PaymentOperation("customer", null, "sbp", null, null, null,
                new BigDecimal("1400.00"), "REFUNDED_PARTIALLY", "payment-10", null,
                "merchant-3", null, "order-7", List.of());
        MappedPayment returned = new MappedPayment(PaymentLinkStatus.PARTIAL_REFUNDED,
                PaymentMethod.SBP_QR, "REFUNDED_PARTIALLY", "sbp");
        when(paymentProfileService.isTochkaProvider(profile)).thenReturn(true);
        when(tochkaPaymentProfileResolver.resolveForExistingPayment(profile)).thenReturn(runtime);
        when(tochkaClient.getPaymentInfo(runtime, "payment-10"))
                .thenReturn(new PaymentInfoResponse(new PaymentInfoData(List.of(operation)), null));
        when(tochkaPaymentOperationMapper.map(any(PaymentOperation.class), any(), eq(false))).thenReturn(returned);

        assertFalse(service.observePublicBankState(link).attempted());
        PaymentBankObservationService.TochkaStateObservation observed = assertInstanceOf(
                PaymentBankObservationService.TochkaStateObservation.class,
                service.observeBankStateForReconciliation(link));
        assertEquals(PaymentLinkStatus.PARTIAL_REFUNDED, observed.mapped().status());
        verify(tochkaClient).getPaymentInfo(runtime, "payment-10");
    }
}
