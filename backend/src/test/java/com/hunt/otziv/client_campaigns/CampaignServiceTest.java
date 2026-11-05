package com.hunt.otziv.client_campaigns;
import static org.mockito.Mockito.*;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import org.junit.jupiter.api.Test;

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
        var budget=mock(com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.class);
        var service=new CampaignService(store,sender,settings);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dispatchBudget",budget);
        when(settings.clientMessagesEnabled()).thenReturn(true);
        when(store.due(any())).thenReturn(java.util.List.of("c1"));
        var claim=mock(CampaignModels.Claim.class);
        when(store.claim(eq("c1"),any())).thenReturn(claim);
        var campaign=mock(CampaignModels.Campaign.class);
        when(campaign.state()).thenReturn("RUNNING");
        when(store.get("c1")).thenReturn(campaign);
        when(budget.reserveOnPlatform(any(),anyString(),anyLong(),any()))
                .thenReturn(new com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Admission(false,"automation_paused","paused",null));

        service.tick();

        verify(store).releaseWithoutSend(claim);
        verifyNoInteractions(sender);
    }
}
