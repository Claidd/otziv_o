package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.api.ClientGroupDocumentDelivery;
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
    private final PaperBillingDelivery delivery=new PaperBillingDelivery(settings,bank,documents,outbound,immediate);
    private final byte[] pdf=new byte[]{1,2,3};
    private Map<String,Object> row() {
        return Map.of("id","doc","number","PB-10-12345678","amount_kopecks",100050L,
                "client_id","sender","group_id","TELEGRAM:-123","invoice_pdf",pdf,"act_pdf",pdf);
    }
    private void enable() {
        settings.setDeliveryEnabled(true);
        when(outbound.clientMessagesEnabled()).thenReturn(true);
        when(immediate.immediatePublicationMessagesEnabled()).thenReturn(true);
    }
    @Test void sendsCompletePaymentInstructionsWithTheInvoiceToTheFrozenRecipient() {
        enable();
        when(documents.send(anyString(),anyString(),anyString(),any(),anyString(),anyString()))
                .thenReturn(ClientMessageSendResult.sent("Telegram","42"));
        assertTrue(delivery.send(row(),false).sent());
        var text=ArgumentCaptor.forClass(String.class);
        verify(documents).send(eq("sender"),eq("TELEGRAM:-123"),text.capture(),same(pdf),
                eq("Invoice-PB-10-12345678.pdf"),eq("paper:doc:invoice"));
        String caption=text.getValue();
        assertAll(() -> assertTrue(caption.contains("заказ выполнен")),
                () -> assertTrue(caption.contains("1 000,50 ₽")),
                () -> assertTrue(caption.contains("одним платежом")),
                () -> assertTrue(caption.contains("в назначении платежа укажите номер счёта: PB-10-12345678")),
                () -> assertTrue(caption.contains("подтвердится автоматически")),
                () -> assertFalse(caption.contains("отправьте чек")),
                () -> assertTrue(caption.length()<=1024));
    }
    @Test void actReferencesTheSameInvoiceWithoutASecondPaymentRequest() {
        enable();
        delivery.send(row(),true);
        var text=ArgumentCaptor.forClass(String.class);
        verify(documents).send(eq("sender"),eq("TELEGRAM:-123"),text.capture(),same(pdf),
                eq("Act-PB-10-12345678.pdf"),eq("paper:doc:act"));
        assertTrue(text.getValue().contains("PB-10-12345678"));
        assertTrue(text.getValue().contains("1 000,50 ₽"));
        assertFalse(text.getValue().contains("Пожалуйста, оплатите"));
    }
    @Test void pausedOutboundMessagesNeverSendEvenWithDeliveryEnabled() {
        settings.setDeliveryEnabled(true);
        assertFalse(delivery.send(row(),false).sent());
        verifyNoInteractions(documents);
    }
}
