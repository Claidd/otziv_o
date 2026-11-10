package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.payments.dto.*;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.*;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.*;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.service.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import com.hunt.otziv.payments.api.UnpaidBankObservation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipientBankSessionTest {
    final PaymentProfileService profiles = mock();
    final TbankRuntimeSettingsService settings = mock();
    final TbankClient tbank = mock();
    final TochkaClient tochka = mock();
    final TochkaPaymentProfileResolver resolver = mock();
    final PaymentProfile entity = new PaymentProfile();
    final TbankPaymentProfile terminal = new TbankPaymentProfile(1L,"test","test",true,"merchant","test",true);
    final RecipientBankObservationService service = new RecipientBankObservationService(profiles,settings,tbank,tochka,resolver,new TochkaPaymentOperationMapper());
    Source source(String provider) {
        when(profiles.findById(1L)).thenReturn(Optional.of(entity));
        when(settings.isTbankEnabled()).thenReturn(true);
        when(profiles.toRuntimeForTerminal(entity,"merchant")).thenReturn(terminal);
        return new Source("ORDER",2,3,1L,provider,"operation","order","merchant",100,"card",true,"1");
    }
    void state(String status,String order) {
        when(tbank.getState(terminal,"operation")).thenReturn(new TbankGetStateResponse(true,"0",null,null,"merchant",status,"operation",order,100L));
    }
    @ParameterizedTest @ValueSource(strings={"NEW","FORM_SHOWED","AUTHORIZED","AUTHORIZING"})
    void waitsForTheBankWithoutSendingCancellation(String status) {
        var source = source("T_BANK");
        state(status,"order");
        assertThat(service.observe(source).outcome()).isEqualTo(Outcome.WAITING);
        verify(tbank,never()).cancel(any(),any());
    }

    @ParameterizedTest @ValueSource(strings={"CANCELED","REJECTED","DEADLINE_EXPIRED"})
    void closesOnlyAfterBankReportsUnpaidTerminalState(String status) {
        var source = source("T_BANK");
        when(tbank.getState(terminal,"operation")).thenReturn(new TbankGetStateResponse(true,"0",null,null,
                "merchant",status,"operation","order",0L));
        assertThat(service.observe(source).outcome()).isEqualTo(Outcome.CLOSED);
        verify(tbank,never()).cancel(any(),any());
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRMED","REFUNDED","REVERSED"})
    void financialResultsStopReplacement(String status) {
        var source = source("T_BANK");
        state(status,"order");
        assertThat(service.observe(source).outcome()).isEqualTo(Outcome.FINANCIAL);
        verify(tbank,never()).cancel(any(),any());
    }

    TbankCancelResponse cancelResponse(boolean success, String status, String paymentId) {
        return new TbankCancelResponse(success,"0",null,null,"merchant",status,paymentId,"order",100L,100L,0L);
    }

    @ParameterizedTest @ValueSource(strings={"NEW","FORM_SHOWED"})
    void closeUnpaidCancelsAnOpenTbankFormAndReportsItClosed(String status) {
        var source = source("T_BANK");
        state(status,"order");
        when(tbank.cancel(eq(terminal),any())).thenReturn(cancelResponse(true,"CANCELED","operation"));
        var result = service.closeUnpaid(source);
        assertThat(result.outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(result.status()).isEqualTo("CANCELED");
        verify(tbank).cancel(eq(terminal),argThat(command -> "operation".equals(command.paymentId()) && command.amountKopecks()==100L));
    }

    @ParameterizedTest @ValueSource(strings={"AUTHORIZING","AUTHORIZED","CONFIRMED","CANCELED"})
    void closeUnpaidDoesNotCancelStartedOrFinishedPayments(String status) {
        var source = source("T_BANK");
        when(tbank.getState(terminal,"operation")).thenReturn(new TbankGetStateResponse(true,"0",null,null,"merchant",status,
                "operation","order","CANCELED".equals(status) ? 0L : 100L));
        service.closeUnpaid(source);
        verify(tbank,never()).cancel(any(),any());
    }

    @Test void paymentCompletedBeforeCancelIsReportedAsMoneyMovement() {
        var source = source("T_BANK");
        state("FORM_SHOWED","order");
        when(tbank.cancel(eq(terminal),any())).thenReturn(cancelResponse(true,"REFUNDED","operation"));
        assertThat(service.closeUnpaid(source).outcome()).isEqualTo(Outcome.FINANCIAL);
    }

    @Test void cancelReportingTheRemainingAmountIsAccepted() {
        var source = source("T_BANK");
        state("NEW","order");
        when(tbank.cancel(eq(terminal),any())).thenReturn(new TbankCancelResponse(true,"0",null,null,"merchant","CANCELED",
                "operation","order",0L,100L,0L));
        assertThat(service.closeUnpaid(source).status()).isEqualTo("CANCELED");
    }

    @Test void cancelOfAnotherOriginalAmountIsRejected() {
        var source = source("T_BANK");
        state("NEW","order");
        when(tbank.cancel(eq(terminal),any())).thenReturn(new TbankCancelResponse(true,"0",null,null,"merchant","CANCELED",
                "operation","order",null,50L,0L));
        assertThatThrownBy(() -> service.closeUnpaid(source)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unconfirmedCancelKeepsTheRouteForAnotherObservation() {
        var source = source("T_BANK");
        state("NEW","order");
        when(tbank.cancel(eq(terminal),any())).thenReturn(cancelResponse(false,"NEW","operation"),
                cancelResponse(true,"CANCELED","another"), cancelResponse(true,"NEW","operation"));
        assertThatThrownBy(() -> service.closeUnpaid(source)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.closeUnpaid(source)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.closeUnpaid(source)).isInstanceOf(IllegalStateException.class);
    }

    @Test void mismatchedAmountDoesNotReleaseTheRoute() {
        var source = source("T_BANK");
        when(tbank.getState(terminal,"operation")).thenReturn(new TbankGetStateResponse(true,"0",null,null,
                "merchant","CANCELED","operation","order",50L));
        assertThatThrownBy(() -> service.observe(source)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsAnotherOrder() {
        var source = source("T_BANK"); state("NEW","different");
        assertThatThrownBy(() -> service.observe(source)).isInstanceOf(IllegalStateException.class);
        verify(tbank,never()).cancel(any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"CREATED","EXPIRED","APPROVED"})
    void tochkaAllowsReplacementOnlyForVerifiedUnpaidFormOrClosure(String status) {
        entity.setProvider("TOCHKA");
        var source = source("TOCHKA");
        var profile = mock(TochkaPaymentProfile.class);
        when(profiles.isTochkaProvider(entity)).thenReturn(true);
        when(resolver.resolveForExistingPayment(entity)).thenReturn(profile);
        when(profile.testMode()).thenReturn(true);
        when(profile.merchantId()).thenReturn("merchant");
        when(profile.customerCode()).thenReturn("customer");
        String type = "APPROVED".equals(status) ? "card" : null;
        var payment = new PaymentOperation("customer",null,type,null,null,null,BigDecimal.ONE,status,"operation",null,"merchant",null,"order",List.of());
        when(tochka.getPaymentInfo(profile,"operation")).thenReturn(new PaymentInfoResponse(new PaymentInfoData(List.of(payment)),null));
        var result = service.observe(source);
        assertThat(result.openUnpaid(source)).isEqualTo("CREATED".equals(status));
        assertThat(result.outcome()).isEqualTo(switch(status) {
            case "CREATED" -> Outcome.WAITING; case "EXPIRED" -> Outcome.CLOSED; default -> Outcome.FINANCIAL;
        });
        // Tochka has no cancellation API: closing only observes, the replacement keeps watching the link.
        assertThat(service.closeUnpaid(source).outcome()).isEqualTo(result.outcome());
        assertThatThrownBy(() -> service.cancelUnpaid(source)).isInstanceOf(IllegalStateException.class);
        verify(tochka,never()).refund(any(),any());
        verifyNoInteractions(tbank);
    }
}
