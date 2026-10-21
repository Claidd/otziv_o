package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.service.SettledBankPaymentObservationService;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoData;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonInvoiceSettledBankReturnReconciliationServiceTest {

    @Mock private CommonInvoiceSettlementService settlementService;
    @Mock private CommonInvoicePaymentRefRepository paymentRefRepository;
    @Mock private CommonInvoiceRepository invoiceRepository;
    @Mock private PaymentProfileService paymentProfileService;
    @Mock private TbankClient tbankClient;
    @Mock private TochkaClient tochkaClient;
    @Mock private TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private CommonInvoiceSettledBankReturnReconciliationService service;
    private CommonInvoice invoice;
    private CommonInvoicePaymentRef ref;
    private PaymentProfile profile;

    @BeforeEach
    void setUp() {
        service = new CommonInvoiceSettledBankReturnReconciliationService(
                settlementService, paymentRefRepository, invoiceRepository,
                new SettledBankPaymentObservationService(paymentProfileService, tbankClient,
                        tochkaClient, tochkaPaymentProfileResolver, new TochkaPaymentOperationMapper()));
        invoice = new CommonInvoice();
        invoice.setId(7L);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        invoice.setPaidKopecks(140_000L);
        ref = new CommonInvoicePaymentRef();
        ref.setId(9L);
        ref.setInvoice(invoice);
        ref.setProvider("T_BANK");
        ref.setStatus("APPLIED");
        ref.setProviderOrderId("order-7");
        ref.setProviderPaymentId("pay-9");
        ref.setProviderMerchantId("terminal-3");
        ref.setPaymentProfileId(3L);
        ref.setAmountKopecks(140_000L);
        profile = new PaymentProfile();
        profile.setId(3L);
        when(paymentRefRepository.findSettledBankReturnCheckCandidates(any(), any(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(ref));
        when(settlementService.writeTransaction(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(settlementService.lockedInvoice(7L)).thenReturn(Optional.of(invoice));
        when(paymentRefRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(ref));
        when(settlementService.providerPaymentId(ref)).thenReturn("pay-9");
        when(paymentProfileService.findById(3L)).thenReturn(Optional.of(profile));
    }

    @Test
    void exactTbankRefundQuarantinesInvoiceWithoutReversingItsPositionAmounts() {
        TbankPaymentProfile runtime = tbankRuntime();
        when(tbankClient.getState(runtime, "pay-9")).thenReturn(tbankState("REFUNDED", "pay-9", 140_000L));

        assertThat(service.reconcileDueSettledBankRefs(10)).isEqualTo(1);
        assertThat(ref.getStatus()).isEqualTo("REFUNDED");
        assertThat(ref.getProviderStatus()).isEqualTo("REFUNDED");
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(invoice.getPaidKopecks()).isEqualTo(140_000L);
        assertThat(invoice.getLastError()).contains("bank_payment_returned");

        assertThat(service.reconcileDueSettledBankRefs(10)).isZero();
        verify(tbankClient, times(1)).getState(runtime, "pay-9");
    }

    @Test
    void unchangedTbankConfirmationRotatesTheCheckWithoutReapplyingPayment() {
        TbankPaymentProfile runtime = tbankRuntime();
        when(tbankClient.getState(runtime, "pay-9")).thenReturn(tbankState("CONFIRMED", "pay-9", 140_000L));

        assertThat(service.reconcileDueSettledBankRefs(10)).isEqualTo(1);
        assertThat(ref.getStatus()).isEqualTo("APPLIED");
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.PAID);
        assertThat(invoice.getPaidKopecks()).isEqualTo(140_000L);
        assertThat(ref.getNextProviderCheckAt()).isAfter(LocalDateTime.now().plusHours(23));

        assertThat(service.reconcileDueSettledBankRefs(10)).isZero();
        verify(tbankClient, times(1)).getState(runtime, "pay-9");
    }

    @Test
    void mismatchedTbankIdentityNeverRecordsARefund() {
        TbankPaymentProfile runtime = tbankRuntime();
        when(tbankClient.getState(runtime, "pay-9")).thenReturn(tbankState("REFUNDED", "foreign-pay", 140_000L));

        assertThat(service.reconcileDueSettledBankRefs(10)).isEqualTo(1);
        assertThat(ref.getStatus()).isEqualTo("APPLIED");
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(invoice.getPaidKopecks()).isEqualTo(140_000L);
        assertThat(invoice.getLastError()).contains("tbank_settled_identity_mismatch");
    }

    @Test
    void exactTochkaPartialRefundQuarantinesWithoutReplayingSettlement() {
        ref.setProvider("TOCHKA");
        ref.setProviderPaymentMode("sbp");
        ref.setProviderPaymentType("sbp");
        ref.setProviderTestMode(false);
        profile.setProvider("TOCHKA");
        TochkaPaymentProfile runtime = new TochkaPaymentProfile(
                3L, "tochka", "Tochka", true, "customer", "terminal-3", "jwt", "", false,
                null, null, null, null, "", "", List.of(TochkaPaymentMode.SBP), Duration.ofMinutes(30));
        when(tochkaPaymentProfileResolver.resolveForExistingPayment(profile)).thenReturn(runtime);
        PaymentOperation operation = new PaymentOperation("customer", null, "sbp", null, null, null,
                new BigDecimal("1400.00"), "REFUNDED_PARTIALLY", "pay-9", null, "terminal-3", null,
                "order-7", List.of());
        when(tochkaClient.getPaymentInfo(runtime, "pay-9"))
                .thenReturn(new PaymentInfoResponse(new PaymentInfoData(List.of(operation)), null));

        assertThat(service.reconcileDueSettledBankRefs(10)).isEqualTo(1);
        assertThat(ref.getStatus()).isEqualTo("PARTIAL_REFUNDED");
        assertThat(ref.getProviderStatus()).isEqualTo("REFUNDED_PARTIALLY");
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(invoice.getPaidKopecks()).isEqualTo(140_000L);
    }

    private TbankPaymentProfile tbankRuntime() {
        TbankPaymentProfile runtime = new TbankPaymentProfile(3L, "primary", "Bank", true,
                "terminal-3", "password", false);
        when(paymentProfileService.toRuntimeForTerminal(eq(profile), eq("terminal-3"))).thenReturn(runtime);
        return runtime;
    }

    private TbankGetStateResponse tbankState(String status, String paymentId, Long amount) {
        return new TbankGetStateResponse(true, "0", null, null, "terminal-3", status,
                paymentId, "order-7", amount);
    }
}
