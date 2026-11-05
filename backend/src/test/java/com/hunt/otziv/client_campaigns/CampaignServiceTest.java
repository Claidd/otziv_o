package com.hunt.otziv.client_campaigns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import com.hunt.otziv.client_messages.api.BackgroundDispatchBudget;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CampaignServiceTest {
    @Test void liveSwitchOffOrUnavailableNeverClaimsOrSends() {
        var store=mock(CampaignStore.class); var sender=mock(CampaignSender.class); var settings=mock(OutboundMessagePolicy.class);
        var service=new CampaignService(store,sender,settings);
        service.tick(); verifyNoInteractions(store,sender);
        when(settings.clientMessagesEnabled()).thenThrow(new IllegalStateException());
        service.tick(); verifyNoInteractions(store,sender);
    }

    @Test void autoresponderPauseReleasesTheClaimWithoutSending() {
        var store=mock(CampaignStore.class); var sender=mock(CampaignSender.class); var settings=mock(OutboundMessagePolicy.class);
        var budget=mock(BackgroundDispatchBudget.class);
        var service=new CampaignService(store,sender,settings);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dispatchBudget",budget);
        when(settings.clientMessagesEnabled()).thenReturn(true);
        when(store.due(any())).thenReturn(java.util.List.of("c1"));
        var claim=mock(CampaignModels.Claim.class);
        when(store.claim(eq("c1"),any())).thenReturn(claim);
        var campaign=mock(CampaignModels.Campaign.class);
        when(campaign.state()).thenReturn("RUNNING");
        when(store.get("c1")).thenReturn(campaign);
        when(budget.reserveForSender(any(),anyString(),anyLong(),nullable(String.class),nullable(String.class)))
                .thenReturn(new BackgroundDispatchBudget.Admission(false,"automation_paused","paused",null));

        service.tick();

        verify(store).releaseWithoutSend(eq(claim),any(LocalDateTime.class));
        verifyNoInteractions(sender);
    }

    @Test void heldSendingAccountReturnsTheRecipientAndRetriesAtTheFreeSlot() {
        var store=mock(CampaignStore.class); var sender=mock(CampaignSender.class); var settings=mock(OutboundMessagePolicy.class);
        var budget=mock(BackgroundDispatchBudget.class);
        LocalDateTime nowUtc=LocalDateTime.of(2026,11,4,3,0);
        var service=new CampaignService(store,sender,settings,Clock.fixed(nowUtc.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dispatchBudget",budget);
        when(settings.clientMessagesEnabled()).thenReturn(true);
        when(store.due(any())).thenReturn(java.util.List.of("c1"));
        var recipient=new CampaignModels.Recipient(41,"c1",7L,"Компания","ACTIVE",1,"WHATSAPP:120363@g.us",
                "https://chat.whatsapp.com/x","lika","120363@g.us",null,null,"SENDING","op",null,null,null);
        var campaign=mock(CampaignModels.Campaign.class);
        when(campaign.state()).thenReturn("RUNNING");
        var claim=new CampaignModels.Claim(campaign,recipient);
        when(store.claim(eq("c1"),any())).thenReturn(claim);
        when(store.get("c1")).thenReturn(campaign);
        // Admission times are storage time in the JVM zone.
        LocalDateTime freeSlotUtc=nowUtc.plusSeconds(150);
        LocalDateTime freeSlotStorage=LocalDateTime.ofInstant(freeSlotUtc.toInstant(ZoneOffset.UTC),ZoneId.systemDefault());
        when(budget.reserveForSender(any(),eq("OFFER_CAMPAIGN"),eq(41L),eq("WHATSAPP"),eq("lika")))
                .thenReturn(new BackgroundDispatchBudget.Admission(false,"rate_limited","busy",freeSlotStorage));

        service.tick();

        var retry=ArgumentCaptor.forClass(LocalDateTime.class);
        verify(store).releaseWithoutSend(eq(claim),retry.capture());
        assertThat(retry.getValue()).isEqualTo(freeSlotUtc);
        verify(budget).reserveForSender(eq(BackgroundDispatchBudget.Purpose.OFFER_CAMPAIGN),eq("OFFER_CAMPAIGN"),eq(41L),eq("WHATSAPP"),eq("lika"));
        verifyNoInteractions(sender);
    }

    @Test void admittedSlotSendsOnceAndFinishes() {
        var store=mock(CampaignStore.class); var sender=mock(CampaignSender.class); var settings=mock(OutboundMessagePolicy.class);
        var budget=mock(BackgroundDispatchBudget.class);
        var service=new CampaignService(store,sender,settings,Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"),ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dispatchBudget",budget);
        when(settings.clientMessagesEnabled()).thenReturn(true);
        when(store.due(any())).thenReturn(java.util.List.of("c1"));
        var recipient=new CampaignModels.Recipient(42,"c1",8L,"Компания","ACTIVE",1,"TELEGRAM:-100",
                "https://t.me/x",null,null,-100L,null,"SENDING","op",null,null,null);
        var campaign=mock(CampaignModels.Campaign.class);
        when(campaign.state()).thenReturn("RUNNING");
        var claim=new CampaignModels.Claim(campaign,recipient);
        when(store.claim(eq("c1"),any())).thenReturn(claim);
        when(store.get("c1")).thenReturn(campaign);
        when(budget.reserveForSender(any(),anyString(),anyLong(),eq("TELEGRAM"),nullable(String.class)))
                .thenReturn(BackgroundDispatchBudget.Admission.allow());
        var result=com.hunt.otziv.client_messages.dto.ClientMessageSendResult.sent("Telegram","5");
        when(sender.send(claim)).thenReturn(result);

        service.tick();

        verify(sender,times(1)).send(claim);
        verify(store).finish(eq(claim),eq(result),any());
        verify(store,never()).releaseWithoutSend(any(),any());
    }

    @Test void platformFollowsTheFrozenDestination() {
        assertThat(CampaignService.platform(recipient("WHATSAPP_PHONE:79990000000"))).isEqualTo("WHATSAPP");
        assertThat(CampaignService.platform(recipient("WHATSAPP:1@g.us"))).isEqualTo("WHATSAPP");
        assertThat(CampaignService.platform(recipient("TELEGRAM:5"))).isEqualTo("TELEGRAM");
        assertThat(CampaignService.platform(recipient("MAX:9"))).isEqualTo("MAX");
        assertThat(CampaignService.platform(recipient("MISSING:1"))).isNull();
        assertThat(CampaignService.platform(null)).isNull();
    }

    private static CampaignModels.Recipient recipient(String destination) {
        return new CampaignModels.Recipient(1,"c",1L,"t","ACTIVE",1,destination,null,"lika",null,null,null,"PENDING",null,null,null,null);
    }
}
