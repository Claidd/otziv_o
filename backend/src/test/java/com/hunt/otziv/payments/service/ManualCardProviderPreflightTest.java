package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualCardProviderPreflightTest {
    @Mock OrderRepository orders;
    @Mock ManualCardRoutePolicy routes;
    @Mock ContractorActualPaymentAttributionService attribution;
    @InjectMocks ManualCardPaymentWorkflow workflow;

    @Test void activeTochkaIsRejectedBeforeFreezingRecipientOrProducingABlockingReview() {
        Order order=new Order();order.setId(27145L);
        PaymentLink link=new PaymentLink();link.setId(8644L);link.setOrder(order);link.setStatus(PaymentLinkStatus.INITIATED);
        when(orders.findByIdForCounterUpdate(27145L)).thenReturn(Optional.of(order));
        when(routes.isActiveOrAmbiguousTochkaRouteForManualCardPayment(link)).thenReturn(true);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->workflow.prepareReviewRecipient(
                27145L,link,"MANAGER",33L,"PROFILE:33","По реквизитам","","lika"));
        assertNull(link.getManualActualRecipientFrozenAt());
        verifyNoInteractions(attribution);
    }
}
