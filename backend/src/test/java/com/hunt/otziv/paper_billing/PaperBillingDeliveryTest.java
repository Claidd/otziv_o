package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.api.ClientGroupDocumentDelivery;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperBillingDeliveryTest {
    private final PaperBillingSettings settings=new PaperBillingSettings();
    private final PaperBillingBankClient bank=mock(PaperBillingBankClient.class);
    private final ClientGroupDocumentDelivery documents=mock(ClientGroupDocumentDelivery.class);
    private final OutboundMessagePolicy outbound=mock(OutboundMessagePolicy.class);
    private final PublicationProgressSettings immediate=mock(PublicationProgressSettings.class);
    private final ClientMessageDelivery messages=mock(ClientMessageDelivery.class);
    private final PaperBillingDelivery delivery=new PaperBillingDelivery(settings,bank,documents,outbound,immediate,messages);
    private final byte[] pdf=new byte[]{1,2,3};
    private Map<String,Object> row() {
        return Map.of("id","doc","company_id",1L,"number","PB-10-12345678","amount_kopecks",100050L,
                "client_id","sender","group_id","TELEGRAM:-123","invoice_pdf",pdf,"act_pdf",pdf);
    }
    private void enable() {
        settings.setDeliveryEnabled(true);
        when(outbound.clientMessagesEnabled()).thenReturn(true);
        when(immediate.immediatePublicationMessagesEnabled()).thenReturn(true);
    }
    @Test void sendsSeparatePaymentInstructionsToTheFrozenRecipient() {
        enable();
        when(messages.deliverToPlatformWithOperationId(anyString(),any(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(ClientMessageSendResult.sent("Telegram","42"));
        assertTrue(delivery.sendIntro(row()).sent());
        var text=ArgumentCaptor.forClass(String.class);
        verify(messages).deliverToPlatformWithOperationId(eq("TELEGRAM"),any(),eq("sender"),eq("-123"),text.capture(),eq("paper:doc:message"));
        verifyNoInteractions(documents);
        String caption=text.getValue();
        assertAll(() -> assertTrue(caption.contains("заказ выполнен")),
                () -> assertTrue(caption.contains("1 000,50 ₽")),
                () -> assertTrue(caption.contains("одним платежом")),
                () -> assertTrue(caption.contains("в назначении платежа укажите номер счёта: PB-10-12345678")),
                () -> assertTrue(caption.contains("подтвердится автоматически")),
                () -> assertFalse(caption.contains("отправьте чек")),
                () -> assertTrue(caption.length()<=1024));
    }
    @Test void documentsHaveReadableNamesAndNoTextBetweenTheFiles() {
        enable();
        delivery.send(row(),false);
        delivery.send(row(),true);
        verify(documents).send(eq("sender"),eq("TELEGRAM:-123"),eq(""),same(pdf),
                eq("Счёт на оплату — PB-10-12345678.pdf"),eq("paper:doc:invoice"));
        verify(documents).send(eq("sender"),eq("TELEGRAM:-123"),eq(""),same(pdf),
                eq("Акт выполненных работ — PB-10-12345678.pdf"),eq("paper:doc:act"));
        verifyNoInteractions(messages);
    }
    @Test void paymentBeforePdfDeliveryDoesNotAskTheCustomerToPayAgain() {
        var paid=new java.util.HashMap<String,Object>(row());paid.put("paid_at",java.time.LocalDateTime.now());
        String caption=PaperBillingDelivery.instructions(paid);
        assertTrue(caption.contains("получена"));assertTrue(caption.contains("1 000,50 ₽"));assertTrue(caption.contains("акт выполненных работ"));
        assertFalse(caption.contains("Пожалуйста, оплатите"));assertFalse(caption.contains("К оплате"));
    }
    @Test void pausedOutboundMessagesNeverSendEvenWithDeliveryEnabled() {
        settings.setDeliveryEnabled(true);
        assertFalse(delivery.send(row(),false).sent());
        assertFalse(delivery.sendIntro(row()).sent());
        verifyNoInteractions(documents,messages);
    }
}
