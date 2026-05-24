package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppGroupLinkSyncServiceTest {

    private final WhatsAppProperties properties = new WhatsAppProperties();
    private final WhatsAppService whatsAppService = mock(WhatsAppService.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final WhatsAppGroupLinkSyncService service = new WhatsAppGroupLinkSyncService(
            properties,
            whatsAppService,
            companyRepository,
            appSettingService
    );

    @Test
    void linksGroupByWhatsAppInviteLinkFromGatewayGroupList() {
        Company company = new Company();
        company.setId(22860L);
        company.setTitle("Св-Моторс");
        company.setUrlChat("https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363123@g.us", "Св-Моторс. Отзывы", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv")
        ));
        when(companyRepository.findTop3ByGroupIdIsNullAndUrlChatContainingIgnoreCase("abcdefghijklmnopqrstuv"))
                .thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363123@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void ignoresGatewayGroupWithoutInviteLink() {
        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363123@g.us", "Св-Моторс. Отзывы", "")
        ));

        service.syncClientGroups("whatsapp_lika");

        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateSettingsPersistsIntervalAndEnabledFlag() {
        when(appSettingService.getBoolean(AppSettingService.WHATSAPP_GROUP_SYNC_ENABLED, true)).thenReturn(false);
        when(appSettingService.getInt(AppSettingService.WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES, 30)).thenReturn(45);
        when(appSettingService.getString(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_RUN_AT, "")).thenReturn("2026-05-22T00:00:00Z");
        when(appSettingService.getInt(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT, 0)).thenReturn(2);

        WhatsAppGroupSyncSettingsResponse response = service.updateSettings(
                new WhatsAppGroupSyncSettingsRequest(false, 45)
        );

        assertFalse(response.enabled());
        assertEquals(45, response.intervalMinutes());
        assertEquals(2, response.lastLinkedCount());
        verify(appSettingService).setBoolean(AppSettingService.WHATSAPP_GROUP_SYNC_ENABLED, false);
        verify(appSettingService).setInt(AppSettingService.WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES, 45);
    }

    @Test
    void updateSettingsRejectsTooSmallInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateSettings(new WhatsAppGroupSyncSettingsRequest(true, 4))
        );

        verify(appSettingService, never()).setInt(
                org.mockito.ArgumentMatchers.eq(AppSettingService.WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }
}
