package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.*;
import com.hunt.otziv.payments.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BankCanceledZeroAmountTest {
    private final PaymentLinkPresenter presenter = mock();
    private final BankObservationApplicationService service = new BankObservationApplicationService(
            mock(), mock(), mock(), presenter, mock(), mock(), mock(), mock(), mock());
    private final TbankPaymentProfile profile = new TbankPaymentProfile(1L,"fixture","Bank",true,"terminal","secret",false);
    private PaymentLink link;
    @BeforeEach void setup() {
        when(presenter.normalize(any())).thenAnswer(i -> i.getArgument(0) == null ? "" : i.<String>getArgument(0).trim());
        link = new PaymentLink(); link.setId(9068L); link.setAmountKopecks(150000);
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION); link.setTbankPaymentId("payment"); link.setTbankOrderId("order");
    }
    private TbankGetStateResponse response(String status, String terminal, String payment, String order, long amount) {
        return new TbankGetStateResponse(true,"0",null,null,terminal,status,payment,order,amount);
    }
    @Test void acceptsOnlyBoundSuccessfulCancellationWithoutMoney() {
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","order",0),profile)).isTrue();
        assertThat(service.isStateConsistent(link,response("CANCELED","wrong","payment","order",0),profile)).isFalse();
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","wrong","order",0),profile)).isFalse();
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","wrong",0),profile)).isFalse();
        assertThat(service.isStateConsistent(link,response("CANCELED","","payment","order",0),profile)).isFalse();
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","order",100),profile)).isFalse();
        assertThat(service.isStateConsistent(link,response("CONFIRMED","terminal","payment","order",0),profile)).isFalse();
        assertThat(service.isStateConsistent(link,new TbankGetStateResponse(false,"99",null,null,"terminal","CANCELED","payment","order",0L),profile)).isFalse();
    }
    @ParameterizedTest @EnumSource(value=PaymentLinkStatus.class,names={"CONFIRMED","TEST_CONFIRMED","AUTHORIZED","PARTIAL_REFUNDED","REFUNDED","AMOUNT_MISMATCH"})
    void neverClearsPaidOrAuthorizedSources(PaymentLinkStatus status) {
        link.setStatus(status);
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","order",0),profile)).isFalse();
    }
    @Test void quarantinedPreviouslyPaidSourceStillRequiresReconciliation() {
        link.setConfirmedAmountKopecks(150000L);
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","order",0),profile)).isFalse();
        link.setConfirmedAmountKopecks(null); link.setBankCancelOriginStatus(PaymentLinkStatus.CONFIRMED);
        assertThat(service.isStateConsistent(link,response("CANCELED","terminal","payment","order",0),profile)).isFalse();
    }
}
