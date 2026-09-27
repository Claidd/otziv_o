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
        var s=new Settings("Offer","Body",10,10,"10:00","21:00",true,false,false,mode,false);
        var c=new Campaign("campaign",s,"RUNNING",null,null,null,null,0,"offer.txt","text/plain","file-token");
        return new Claim(c,new Recipient(1,"campaign",1L,"Company","ACTIVE",1,platform+":123","https://t.me/fixture","manager","123456789@g.us",123L,123L,"PENDING","offer:1",null,null,null));
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
    Claim testClaim(String mode,String fileName) {
        var s=new Settings("Test","Body",10,10,"10:00","21:00",true,true,true,mode,true);
        var c=new Campaign("test",s,"RUNNING",null,null,null,null,0,fileName,"text/plain","file-token");
        return new Claim(c,new Recipient(2,"test",null,"Owner","TEST_STAFF",1,"TELEGRAM:123",null,null,null,
                123L,null,"PENDING","offer:test:user:1",null,null,1L));
    }
    @Test void testTextAndLinkUseOnlyPersonalTelegramAndNeverCompanyDelivery() {
        when(store.testRecipientAllowed(any())).thenReturn(true);
        when(telegram.canSendDocuments()).thenReturn(true);
        when(telegram.sendMessageOnceWithInlineKeyboardMessageId(eq(123L),anyString(),isNull(),isNull())).thenReturn(Optional.of(7));
        assertThat(sender.send(testClaim("ATTACHMENT",null)).sent()).isTrue();
        assertThat(sender.send(testClaim("LINK","offer.txt")).sent()).isTrue();
        verify(telegram).sendMessageOnceWithInlineKeyboardMessageId(123L,"Body",null,null);
        verify(telegram).sendMessageOnceWithInlineKeyboardMessageId(123L,"Body\n\noffer.txt\nhttps://fixture.example/api/public/client-offer-files/file-token",null,null);
        verifyNoInteractions(delivery,max,whatsapp);
    }
    @Test void testAttachmentUsesPersonalTelegramWithUnchangedCaption() {
        var file=new Attachment("offer.txt","text/plain",new byte[]{1,2});
        when(store.testRecipientAllowed(any())).thenReturn(true);
        when(store.attachment("test")).thenReturn(file);
        when(telegram.canSendDocuments()).thenReturn(true);
        when(telegram.sendDocumentOnceMessageId(123L,file.bytes(),file.name(),"Body")).thenReturn(Optional.of(8));
        assertThat(sender.send(testClaim("ATTACHMENT","offer.txt")).sent()).isTrue();
        verify(telegram).sendDocumentOnceMessageId(123L,file.bytes(),file.name(),"Body");
        verifyNoInteractions(delivery,max,whatsapp);
    }
    @Test void ineligibleTestRecipientStopsBeforeAnyTransportOrFileRead() {
        assertThat(ClientMessageDelivery.isKnownUnsent(sender.send(testClaim("LINK","offer.txt")))).isTrue();
        assertThat(ClientMessageDelivery.isKnownUnsent(sender.send(testClaim("ATTACHMENT","offer.txt")))).isTrue();
        verify(store,never()).attachment(any());
        verifyNoInteractions(telegram,delivery,max,whatsapp);
    }

    Claim leadClaim(String mode,String fileName) {
        var s=new Settings("Leads","Body",30,10,"10:00","21:00",false,false,false,mode,false,true,true,"fallback");
        var c=new Campaign("leads",s,"RUNNING",null,null,null,null,0,fileName,"text/plain","file-token");
        return new Claim(c,new Recipient(3,"leads",null,"Lead","LEAD_IN_WORK",4,"WHATSAPP_PHONE:79991111111",
                null,"manager",null,null,null,"PENDING","offer:leads:lead:1",null,null,null,1L,"79991111111"));
    }
    @Test void leadTextLinkAndAttachmentUsePersonalWhatsAppOnlyWithFrozenManager() {
        when(store.leadRecipientAllowed(any(),any())).thenReturn(true);
        when(whatsapp.sendMessageOnce(any(),any(),any(),any())).thenReturn(ClientMessageSendResult.sent("WhatsApp","text"));
        assertThat(sender.send(leadClaim("ATTACHMENT",null)).sent()).isTrue();
        assertThat(sender.send(leadClaim("LINK","offer.txt")).sent()).isTrue();
        verify(whatsapp).sendMessageOnce("manager","79991111111","Body","offer:leads:lead:1");
        verify(whatsapp).sendMessageOnce("manager","79991111111","Body\n\noffer.txt\nhttps://fixture.example/api/public/client-offer-files/file-token","offer:leads:lead:1");
        var file=new Attachment("offer.txt","text/plain",new byte[]{1,2}); when(store.attachment("leads")).thenReturn(file);
        when(whatsapp.sendDocumentOnce(any(),any(),any(),any(),any(),any(),any())).thenReturn(ClientMessageSendResult.sent("WhatsApp","file"));
        assertThat(sender.send(leadClaim("ATTACHMENT","offer.txt")).sent()).isTrue();
        verify(whatsapp).sendDocumentOnce("manager","79991111111","Body",file.bytes(),file.name(),file.contentType(),"offer:leads:lead:1");
        verifyNoInteractions(delivery,telegram,max);
    }
    @Test void bannedOrChangedLeadNeverReachesAnyTransport() {
        for (String mode : java.util.List.of("LINK","ATTACHMENT"))
            assertThat(ClientMessageDelivery.isKnownUnsent(sender.send(leadClaim(mode,"offer.txt")))).isTrue();
        verifyNoInteractions(delivery,telegram,max,whatsapp); verify(store,never()).attachment(any());
    }
}
