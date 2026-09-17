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
}
