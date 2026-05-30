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
    private final WhatsAppGroupCompanyLinker groupCompanyLinker = new WhatsAppGroupCompanyLinker(companyRepository);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final WhatsAppGroupLinkSyncService service = new WhatsAppGroupLinkSyncService(
            properties,
            whatsAppService,
            groupCompanyLinker,
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
        when(companyRepository.findByUrlChatContainingIgnoreCase("abcdefghijklmnopqrstuv"))
                .thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363123@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void refreshesGroupIdWhenCompanyInviteLinkWasChanged() {
        Company company = new Company();
        company.setId(3005L);
        company.setTitle("Маэстро Снов");
        company.setUrlChat("https://chat.whatsapp.com/D3OwhSlSLyQEolQIb1Guaz");
        company.setGroupId("120363405037491708@g.us");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363409041774389@g.us", "Маэстро снов. Отзывы", "https://chat.whatsapp.com/D3OwhSlSLyQEolQIb1Guaz")
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("d3owhslslyqeolqib1guaz"))
                .thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363409041774389@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksAllCompaniesWithSameWhatsAppInviteLink() {
        Company first = new Company();
        first.setId(1L);
        first.setTitle("Филиал 1");
        first.setUrlChat("https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");

        Company second = new Company();
        second.setId(2L);
        second.setTitle("Филиал 2");
        second.setUrlChat("https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv?mode=wwt");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363123@g.us", "Общий чат", "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv")
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("abcdefghijklmnopqrstuv"))
                .thenReturn(List.of(first, second));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363123@g.us", first.getGroupId());
        assertEquals("120363123@g.us", second.getGroupId());
        verify(companyRepository).save(first);
        verify(companyRepository).save(second);
    }

    @Test
    void ignoresGatewayGroupWithoutInviteLinkAndWithoutNameMatch() {
        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363123@g.us", "Св-Моторс. Отзывы", "")
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of());

        service.syncClientGroups("whatsapp_lika");

        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void linksCompaniesWithSameWhatsAppChatByCompositeGroupNameWhenInviteIsMissing() {
        Company first = new Company();
        first.setId(10L);
        first.setTitle("Амбар");
        first.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        Company second = new Company();
        second.setId(11L);
        second.setTitle("Вокруг света");
        second.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363412424775524@g.us", "Амбар, Вокруг света. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363412424775524@g.us", first.getGroupId());
        assertEquals("120363412424775524@g.us", second.getGroupId());
        verify(companyRepository).save(first);
        verify(companyRepository).save(second);
    }

    @Test
    void linksGroupByNameAfterKnownServicePrefixAndPlatformSuffixAreRemoved() {
        Company company = new Company();
        company.setId(12L);
        company.setTitle("Алла");
        company.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363422400063031@g.us", "КУ Алла 2гис", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363422400063031@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksCompositeGroupByNameAfterPrefixCleanupForEachPart() {
        Company first = new Company();
        first.setId(13L);
        first.setTitle("Мир дверей");
        first.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        Company second = new Company();
        second.setId(14L);
        second.setTitle("Дверной маркет");
        second.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        Company third = new Company();
        third.setId(15L);
        third.setTitle("Автосервис первый");
        third.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363424329251621@g.us", "ВИ Мир дверей, Дверной маркет, Автосервис первый 2 гис", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second, third));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363424329251621@g.us", first.getGroupId());
        assertEquals("120363424329251621@g.us", second.getGroupId());
        assertEquals("120363424329251621@g.us", third.getGroupId());
        verify(companyRepository).save(first);
        verify(companyRepository).save(second);
        verify(companyRepository).save(third);
    }

    @Test
    void keepsExactCompanyTitleBeforeTryingPrefixCleanup() {
        Company prefixedTitle = new Company();
        prefixedTitle.setId(16L);
        prefixedTitle.setTitle("КУ Алла");
        prefixedTitle.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        Company plainTitle = new Company();
        plainTitle.setId(17L);
        plainTitle.setTitle("Алла");
        plainTitle.setUrlChat("https://chat.whatsapp.com/OtherSharedInvite123");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363422400063031@g.us", "КУ Алла 2гис", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(prefixedTitle, plainTitle));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363422400063031@g.us", prefixedTitle.getGroupId());
        assertEquals(null, plainTitle.getGroupId());
        verify(companyRepository).save(prefixedTitle);
        verify(companyRepository, never()).save(plainTitle);
    }

    @Test
    void linksGroupByNameAfterTwoKnownServicePrefixesAreRemoved() {
        Company company = new Company();
        company.setId(18L);
        company.setTitle("Юридический каб");
        company.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363123456789@g.us", "М Н Юридический каб Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363123456789@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void skipsCompositeGroupNameWhenMatchedCompaniesHaveDifferentChatLinks() {
        Company first = new Company();
        first.setId(10L);
        first.setTitle("Амбар");
        first.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        Company second = new Company();
        second.setId(11L);
        second.setTitle("Вокруг света");
        second.setUrlChat("https://chat.whatsapp.com/OtherSharedInvite123");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363412424775524@g.us", "Амбар, Вокруг света. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second));

        service.syncClientGroups("whatsapp_lika");

        assertEquals(null, first.getGroupId());
        assertEquals(null, second.getGroupId());
        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsCompositeGroupNameWhenExistingGroupIdConflicts() {
        Company first = new Company();
        first.setId(10L);
        first.setTitle("Амбар");
        first.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");
        first.setGroupId("120363999@g.us");

        Company second = new Company();
        second.setId(11L);
        second.setTitle("Вокруг света");
        second.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363412424775524@g.us", "Амбар, Вокруг света. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363999@g.us", first.getGroupId());
        assertEquals(null, second.getGroupId());
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
