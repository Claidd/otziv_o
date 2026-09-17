package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;
import java.util.Optional;

class CampaignSenderTest {
    CampaignStore store=mock(CampaignStore.class); ClientMessageDelivery delivery=mock(ClientMessageDelivery.class);
    TelegramService telegram=mock(TelegramService.class); MaxBotClient max=mock(MaxBotClient.class); WhatsAppService whatsapp=mock(WhatsAppService.class);
    CampaignSender sender=new CampaignSender(store,delivery,telegram,max,whatsapp,"https://fixture.example");
    Claim claim(String mode,String platform) {
        var s=new Settings("Offer","Body",10,10,"10:00","21:00",true,false,false,mode);
        var c=new Campaign("campaign",s,"RUNNING",null,null,null,null,0,"offer.txt","text/plain","file-token");
        return new Claim(c,new Recipient(1,"campaign",1,"Company","ACTIVE",1,platform+":123","https://t.me/fixture","manager","123456789@g.us",123L,123L,"PENDING","offer:1",null,null));
    }
    @Test void linkUsesOneExistingTextDeliveryAndNeverCallsNativeFileTransport() {
        var claim=claim("LINK","TELEGRAM");
        when(delivery.deliverWithOperationId(any(),any(),any(),any(),isNull(),any())).thenReturn(ClientMessageSendResult.sent("Telegram","7"));
        assertThat(sender.send(claim).sent()).isTrue();
        verify(delivery).deliverWithOperationId(eq(claim.recipient().target()),eq("manager"),any(),
                eq("Body\n\noffer.txt\nhttps://fixture.example/api/public/client-offer-files/file-token"),isNull(),eq("offer:1"));
        verifyNoInteractions(telegram,max,whatsapp,store);
    }
    @Test void telegramReceivesFileWithCaptionAndUnknownReceiptIsNotRetried() {
        var file=new Attachment("offer.txt","text/plain",new byte[]{1,2});
        when(store.attachment("campaign")).thenReturn(file); when(telegram.canSendDocuments()).thenReturn(true);
        when(telegram.sendDocumentOnceMessageId(123L,file.bytes(),file.name(),"Body")).thenReturn(Optional.empty());
        assertThat(sender.send(claim("ATTACHMENT","TELEGRAM")).errorCode()).isEqualTo("operation_unknown");
        verify(telegram,times(1)).sendDocumentOnceMessageId(123L,file.bytes(),file.name(),"Body");
        verifyNoInteractions(delivery);
    }
    @Test void maxUploadFailureDoesNotSendTextOrFileAndIsKnownUnsent() {
        when(store.attachment("campaign")).thenReturn(new Attachment("offer.txt","text/plain",new byte[]{1}));
        when(max.isConfigured()).thenReturn(true); when(max.uploadDocument(any(),any())).thenThrow(new IllegalStateException());
        assertThat(ClientMessageDelivery.isKnownUnsent(sender.send(claim("ATTACHMENT","MAX")))).isTrue();
        verify(max,never()).sendDocumentToChatOnce(any(),any(),any()); verifyNoInteractions(delivery);
    }
}
