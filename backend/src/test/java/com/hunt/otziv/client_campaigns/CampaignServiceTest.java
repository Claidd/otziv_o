package com.hunt.otziv.client_campaigns;
import static org.mockito.Mockito.*;
import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;

class CampaignServiceTest {
    @Test void liveSwitchOffOrUnavailableNeverClaimsOrSends() {
        var store=mock(CampaignStore.class); var sender=mock(CampaignSender.class); var settings=mock(AppSettingService.class);
        var service=new CampaignService(store,sender,settings);
        service.tick(); verifyNoInteractions(store,sender);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED,true)).thenThrow(new IllegalStateException());
        service.tick(); verifyNoInteractions(store,sender);
    }
}
