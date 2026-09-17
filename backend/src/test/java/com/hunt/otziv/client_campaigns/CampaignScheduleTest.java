package com.hunt.otziv.client_campaigns;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class CampaignScheduleTest {
    CampaignModels.Settings settings() { return new CampaignModels.Settings("Offer","Body",10,10,"10:00","21:00",true,false,false,"ATTACHMENT"); }
    @Test void windowsAndCalendarUseIrkutskRegardlessOfServerZone() {
        assertThat(CampaignSchedule.allowed(LocalDateTime.parse("2026-09-17T01:59:00"),settings())).isEqualTo("2026-09-17T02:00:00");
        assertThat(CampaignSchedule.allowed(LocalDateTime.parse("2026-09-17T13:00:00"),settings())).isEqualTo("2026-09-18T02:00:00");
        assertThat(CampaignSchedule.day(LocalDateTime.parse("2026-09-17T16:00:00"))).isEqualTo("2026-09-18");
    }
    @Test void mismatchedAndOversizedFilesAreRejectedBeforeStorage() {
        assertThatThrownBy(() -> CampaignFileGuard.read(new MockMultipartFile("file","offer.pdf","application/pdf","not pdf".getBytes())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> CampaignFileGuard.read(new MockMultipartFile("file","offer.txt","text/plain",new byte[CampaignFileGuard.MAX_BYTES+1])))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(CampaignFileGuard.read(new MockMultipartFile("file","C:\\temp\\offer.txt","application/octet-stream","hello".getBytes())).name()).isEqualTo("offer.txt");
    }
    @Test void whatsappDeduplicatesCanonicalDestinationAndOnlyRecognizesConfiguredChat() {
        assertThat(CampaignAudience.destination("https://chat.whatsapp.com/abc","manager","123456789",null,null))
                .isEqualTo(CampaignAudience.destination("https://chat.whatsapp.com/abc","other","123456789@g.us",null,null));
        assertThat(CampaignAudience.destination("https://t.me/abc",null,null,null,77L)).isNull();
    }
}
