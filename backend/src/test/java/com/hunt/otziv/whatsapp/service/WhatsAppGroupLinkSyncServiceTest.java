package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.dto.SharedChatLinkSyncResponse;
import com.hunt.otziv.c_companies.service.SharedChatLinkSyncService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupSyncSettingsRequest;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupSyncSettingsResponse;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppGroupLinkSyncServiceTest {

    private final WhatsAppProperties properties = new WhatsAppProperties();
    private final WhatsAppService whatsAppService = mock(WhatsAppService.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final WhatsAppGroupCompanyLinker groupCompanyLinker = new WhatsAppGroupCompanyLinker(companyRepository);
    private final SharedChatLinkSyncService sharedChatLinkSyncService = mock(SharedChatLinkSyncService.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final WhatsAppGroupLinkSyncService service = new WhatsAppGroupLinkSyncService(
            properties,
            whatsAppService,
            groupCompanyLinker,
            sharedChatLinkSyncService,
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
    void linksGroupByWhatsAppInviteLinkWithoutProtocol() {
        Company company = new Company();
        company.setId(22861L);
        company.setTitle("Новая фирма");
        company.setUrlChat("chat.whatsapp.com/NoProtocolInvite12345");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363124@g.us", "Новая фирма. Отзывы", "https://chat.whatsapp.com/NoProtocolInvite12345")
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("noprotocolinvite12345"))
                .thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363124@g.us", company.getGroupId());
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
    void doesNotLinkWhatsAppInviteCodeWithDifferentCase() {
        Company company = new Company();
        company.setId(3L);
        company.setTitle("Case-sensitive invite");
        company.setUrlChat("https://chat.whatsapp.com/abcdefghijklmnopqrstuv");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo(
                        "120363125@g.us",
                        "Unrelated group",
                        "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv"
                )
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("abcdefghijklmnopqrstuv"))
                .thenReturn(List.of(company));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals(null, company.getGroupId());
        verify(companyRepository, never()).save(company);
    }

    @Test
    void fallsBackToFullCompanyListWhenInviteLookupMissesCompanyWithSameLink() {
        Company armana = new Company();
        armana.setId(958L);
        armana.setTitle("Armana");
        armana.setUrlChat("https://chat.whatsapp.com/JZ4J8FeiIAkFhDjlgzBU8d?s=cl&p=i&mlu=2");

        Company tochnoKuhni = new Company();
        tochnoKuhni.setId(2831L);
        tochnoKuhni.setTitle("Точно Кухни");
        tochnoKuhni.setUrlChat("https://chat.whatsapp.com/JZ4J8FeiIAkFhDjlgzBU8d?mode=hqrt2/");
        tochnoKuhni.setGroupId("120363164752269032@g.us");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo(
                        "120363164752269032@g.us",
                        "НС Armana, Точно Кухни 2 гис",
                        "https://chat.whatsapp.com/JZ4J8FeiIAkFhDjlgzBU8d"
                )
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("jz4j8feiiakfhdjlgzbu8d"))
                .thenReturn(List.of(tochnoKuhni));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(armana, tochnoKuhni));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363164752269032@g.us", armana.getGroupId());
        verify(companyRepository).save(armana);
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
    void fallsBackToGroupNameWhenGatewayInviteLinkDoesNotMatchCompanyInvite() {
        Company company = new Company();
        company.setId(12L);
        company.setTitle("Aromagia");
        company.setUrlChat("https://chat.whatsapp.com/OldInviteCode1234567890");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo(
                        "120363402267253629@g.us",
                        "ВИ Aromagia 2гис",
                        "https://chat.whatsapp.com/FqpjBjRSyrX6R8QhbKomJu"
                )
        ));
        when(companyRepository.findByUrlChatContainingIgnoreCase("fqpjbjrsyrx6r8qhbkomju")).thenReturn(List.of());
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363402267253629@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksKnownPartsOfCompositeGroupNameWhenAnotherPartIsMissing() {
        Company company = new Company();
        company.setId(12L);
        company.setTitle("Шашлык плюс");
        company.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363220903925977@g.us", "НС Шашлык плюс, Шаверма Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363220903925977@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void ignoresEmptyServiceOnlyGroupNameParts() {
        Company company = new Company();
        company.setId(12L);
        company.setTitle("Bali");
        company.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363418661153832@g.us", "Отзывы ,НС Bali 2 гис", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363418661153832@g.us", company.getGroupId());
        verify(companyRepository).save(company);
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
    void linksGroupByNameAfterNonStandardReviewSuffixIsRemoved() {
        Company company = new Company();
        company.setId(12L);
        company.setTitle("Чилиs");
        company.setUrlChat("https://chat.whatsapp.com/G5lfHMxirWT1WfjX65GPtb");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363228731475456@g.us", "КУ Чилиs Отзывы всегда", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363228731475456@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksGroupByNameWhenCompanyUsesLeadingZeroAndGroupUsesNumberSign() {
        Company company = new Company();
        company.setId(236L);
        company.setTitle("Pub 01");
        company.setUrlChat("https://chat.whatsapp.com/DTP2ydpdPLk3ObVaASTV4v");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363236@g.us", "Pub № 1. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363236@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksGroupByNameWhenLettersAndDigitsAreSpacedDifferently() {
        Company company = new Company();
        company.setId(62L);
        company.setTitle("Миг-Сервис54");
        company.setUrlChat("https://chat.whatsapp.com/IHzF22ZdMT67IJ2yKYbA31");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363062@g.us", "Миг сервис 54. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363062@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksGroupByNameWhenThereIsOneLetterTypo() {
        Company company = new Company();
        company.setId(1391L);
        company.setTitle("Study_Я");
        company.setUrlChat("https://chat.whatsapp.com/GlzbSteDbVdAgK19bkqsol");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("1203631391@g.us", "Stydy_Я. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("1203631391@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksGroupByNameWhenCompanyTitleIsContainedInCompositeGroupName() {
        Company company = new Company();
        company.setId(1181L);
        company.setTitle("Элит");
        company.setUrlChat("https://chat.whatsapp.com/GfRcWynyKdYBRFxyLQdUBL");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("1203631181@g.us", "The Best Shop и Элит. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("1203631181@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void linksCurrentManagerControlChatBindingCardsByNormalizedGroupName() {
        List<Company> companies = List.of(
                company(901L, "Гарантия защиты"),
                company(902L, "Эми"),
                company(903L, "Адвокат Климова"),
                company(904L, "Волшебная расческа"),
                company(905L, "Лотос"),
                company(906L, "Zawadi gift box"),
                company(907L, "Антураж ДВ"),
                company(908L, "Серебряный ключ, ТТур"),
                company(909L, "Главбухвл, ЮристВЛ"),
                company(910L, "Правовед+ Ноябрьск"),
                company(911L, "Жемчуг")
        );

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363901@g.us", "Ю Гарантия Защиты Отзывы", null),
                new WhatsAppGroupInfo("120363902@g.us", "Эми. Отзывы", null),
                new WhatsAppGroupInfo("120363903@g.us", "Адвокат Климова. Отзывы", null),
                new WhatsAppGroupInfo("120363904@g.us", "Волшебная расческа. Отзывы", null),
                new WhatsAppGroupInfo("120363905@g.us", "Лотос. Отзывы", null),
                new WhatsAppGroupInfo("120363906@g.us", "Zawadi gift box. Отзывы", null),
                new WhatsAppGroupInfo("120363907@g.us", "Антураж ДВ. Отзывы", null),
                new WhatsAppGroupInfo("120363908@g.us", "Серебряный ключ. Отзывы", null),
                new WhatsAppGroupInfo("120363909@g.us", "Главбухвл. Отзывы", null),
                new WhatsAppGroupInfo("120363910@g.us", "Правовед Ноябрьск. Отзывы", null),
                new WhatsAppGroupInfo("120363911@g.us", "Жемчуг. Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(companies);

        service.syncClientGroups("whatsapp_lika");

        for (Company company : companies) {
            assertEquals("120363" + company.getId() + "@g.us", company.getGroupId(), company.getTitle());
            verify(companyRepository).save(company);
        }
    }

    @Test
    void doesNotLinkClearlyDifferentSharedGroupNameToUnrelatedCompanies() {
        Company bud = new Company();
        bud.setId(892L);
        bud.setTitle("Bud Burgers");
        bud.setUrlChat("https://chat.whatsapp.com/FzUmrTnC2dUB0HZH3VcIpZ");

        Company caffetteria = new Company();
        caffetteria.setId(221L);
        caffetteria.setTitle("Caffetteria Piu");
        caffetteria.setUrlChat("https://chat.whatsapp.com/FzUmrTnC2dUB0HZH3VcIpZ");

        Company spaten = new Company();
        spaten.setId(222L);
        spaten.setTitle("Spaten haus");
        spaten.setUrlChat("https://chat.whatsapp.com/FzUmrTnC2dUB0HZH3VcIpZ");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363222@g.us", "Шереметьево .Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(bud, caffetteria, spaten));

        service.syncClientGroups("whatsapp_lika");

        assertEquals(null, bud.getGroupId());
        assertEquals(null, caffetteria.getGroupId());
        assertEquals(null, spaten.getGroupId());
        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
    void linksGroupByUniqueShortNameWhenCompanyTitleHasLocationTail() {
        Company company = new Company();
        company.setId(501L);
        company.setTitle("Gallery and more, Колодец дракона");
        company.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363501@g.us", "КУ Gallery and more Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363501@g.us", company.getGroupId());
        verify(companyRepository).save(company);
    }

    @Test
    void skipsShortNameMatchWhenItIsAmbiguous() {
        Company first = new Company();
        first.setId(501L);
        first.setTitle("Gallery and more, Колодец дракона");
        first.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");

        Company second = new Company();
        second.setId(502L);
        second.setTitle("Gallery and more, Центр");
        second.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363501@g.us", "КУ Gallery and more Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(first, second));

        service.syncClientGroups("whatsapp_lika");

        assertEquals(null, first.getGroupId());
        assertEquals(null, second.getGroupId());
        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void linksCompositeGroupWithShortUniquePartWhenCompaniesShareChatLink() {
        Company gallery = new Company();
        gallery.setId(501L);
        gallery.setTitle("Gallery and more, Колодец дракона");
        gallery.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");

        Company spaten = new Company();
        spaten.setId(502L);
        spaten.setTitle("Spaten haus");
        spaten.setUrlChat("https://chat.whatsapp.com/GqLRY4e7slyOFKjoLjIBPa");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("120363501@g.us", "КУ Gallery and more, Spaten haus Отзывы", null)
        ));
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(gallery, spaten));

        service.syncClientGroups("whatsapp_lika");

        assertEquals("120363501@g.us", gallery.getGroupId());
        assertEquals("120363501@g.us", spaten.getGroupId());
        verify(companyRepository).save(gallery);
        verify(companyRepository).save(spaten);
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
    void repairCompanyLinkUsesGatewayCacheAndLinksByVisibleGroupName() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        Company company = new Company();
        company.setId(1181L);
        company.setTitle("Элит");
        company.setUrlChat("https://chat.whatsapp.com/GfRcWynyKdYBRFxyLQdUBL");

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of(
                new WhatsAppGroupInfo("1203631181@g.us", "The Best Shop и Элит. Отзывы", null)
        ));

        WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult result = service.repairCompanyLink(company);

        assertTrue(result.linked());
        assertEquals("1203631181@g.us", company.getGroupId());
        verify(whatsAppService).listGroups("whatsapp_lika");
        verify(companyRepository).save(company);
    }

    @Test
    void repairCompanyLinkDoesNotCallGatewayForStoppedCompany() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        Company company = new Company();
        company.setId(1182L);
        company.setTitle("Stopped company");
        company.setUrlChat("https://chat.whatsapp.com/GfRcWynyKdYBRFxyLQdUBL");
        company.setStatus(CompanyStatus.builder().title("На стопе").build());

        WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult result = service.repairCompanyLink(company);

        assertFalse(result.linked());
        assertTrue(result.message().contains("не требуется"));
        verify(whatsAppService, never()).resolveGroupByInvite("whatsapp_lika", company.getUrlChat());
        verify(whatsAppService, never()).listGroups("whatsapp_lika");
    }

    @Test
    void repairCompanyLinkResolvesInviteDirectlyWhenGroupListIsBroken() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_vika");
        client.setUrl("http://whatsapp_vika:3000");
        properties.setClients(List.of(client));

        Company company = new Company();
        company.setId(3063L);
        company.setTitle("Drivevision");
        company.setUrlChat("https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw?s=cl&p=i");

        when(whatsAppService.resolveGroupByInvite("whatsapp_vika", company.getUrlChat()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(
                        "1203633063@g.us",
                        "Drivevision",
                        "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw"
                )));

        WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult result = service.repairCompanyLink(company);

        assertTrue(result.linked());
        assertEquals("1203633063@g.us", company.getGroupId());
        verify(companyRepository).save(company);
        verify(whatsAppService, never()).listGroups("whatsapp_vika", true);
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

    @Test
    void runNowCopiesSharedChatIdsAfterWhatsAppGroupSync() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        when(whatsAppService.listGroups("whatsapp_lika")).thenReturn(List.of());
        when(sharedChatLinkSyncService.syncSharedChatIds()).thenReturn(
                new SharedChatLinkSyncResponse(10, 2, 3, 3, 0, 0, 0)
        );

        service.runNow();

        verify(sharedChatLinkSyncService).syncSharedChatIds();
        verify(appSettingService).setInt(AppSettingService.WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT, 3);
    }

    @Test
    void repairsDifferentInviteLinksThatIncorrectlyShareOneStoredGroupId() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        Company bestShop = company(1180L, "The best shop");
        bestShop.setUrlChat("https://chat.whatsapp.com/GfRcWynyKdYBRFxyLQdUBL");
        bestShop.setGroupId("120363418727005154@g.us");
        Company elitDovatora = company(1181L, "Элит");
        elitDovatora.setUrlChat("https://chat.whatsapp.com/GfRcWynyKdYBRFxyLQdUBL");
        elitDovatora.setGroupId("120363418727005154@g.us");
        Company elitInterPlaza = company(1739L, "Элит Интер Плаза");
        elitInterPlaza.setUrlChat("https://chat.whatsapp.com/KO22O6sHRyuJyOyD9JrU8P");
        elitInterPlaza.setGroupId("120363418727005154@g.us");

        when(companyRepository.findAllWithChatUrl())
                .thenReturn(List.of(bestShop, elitDovatora, elitInterPlaza));
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", bestShop.getUrlChat()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(
                        "120363381641202026@g.us",
                        "The Best Shop и Элит. Отзывы",
                        bestShop.getUrlChat()
                )));
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", elitInterPlaza.getUrlChat()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(
                        "120363418727005154@g.us",
                        "Элит. Отзывы",
                        elitInterPlaza.getUrlChat()
                )));

        int repaired = service.repairConflictingInviteGroupIds(properties.getClients(), "test");

        assertEquals(2, repaired);
        assertEquals("120363381641202026@g.us", bestShop.getGroupId());
        assertEquals("120363381641202026@g.us", elitDovatora.getGroupId());
        assertEquals("120363418727005154@g.us", elitInterPlaza.getGroupId());
        verify(companyRepository).save(bestShop);
        verify(companyRepository).save(elitDovatora);
        verify(companyRepository, never()).save(elitInterPlaza);
    }

    @Test
    void replacesExpiredRotatedInviteForStoppedCompanyWhenCurrentGroupNameConfirmsCompany() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        Company oldLink = company(1672L, "Шашлычная у Севы");
        oldLink.setUrlChat("https://chat.whatsapp.com/KDB9CKpunB6GInM2Z2Xt90");
        oldLink.setGroupId("120363395760954659@g.us");
        CompanyStatus stopped = new CompanyStatus();
        stopped.setTitle("На стопе");
        oldLink.setStatus(stopped);
        Company currentLink = company(3056L, "Вкус Огня");
        currentLink.setUrlChat("https://chat.whatsapp.com/Eujk9KHGZtZHOvWBkVCSOv?mode=gi_t");
        currentLink.setGroupId("120363395760954659@g.us");

        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(oldLink, currentLink));
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", oldLink.getUrlChat()))
                .thenReturn(Optional.empty());
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", currentLink.getUrlChat()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(
                        "120363395760954659@g.us",
                        "АБ Вкус Огня, Шашлычная у Севы 2 гис",
                        "https://chat.whatsapp.com/Eujk9KHGZtZHOvWBkVCSOv"
                )));

        int repaired = service.repairConflictingInviteGroupIds(properties.getClients(), "test");

        assertEquals(1, repaired);
        assertEquals(
                "https://chat.whatsapp.com/Eujk9KHGZtZHOvWBkVCSOv",
                oldLink.getUrlChat()
        );
        assertEquals("120363395760954659@g.us", oldLink.getGroupId());
        verify(companyRepository).save(oldLink);
        verify(companyRepository, never()).save(currentLink);
    }

    @Test
    void keepsExpiredInviteWhenCurrentGroupNameDoesNotConfirmCompany() {
        WhatsAppProperties.ClientConfig client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));

        Company oldLink = company(1L, "Unrelated company");
        oldLink.setUrlChat("https://chat.whatsapp.com/OldInviteCode123456789");
        oldLink.setGroupId("120363123@g.us");
        Company currentLink = company(2L, "Current company");
        currentLink.setUrlChat("https://chat.whatsapp.com/CurrentInviteCode12345");
        currentLink.setGroupId("120363123@g.us");

        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(oldLink, currentLink));
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", oldLink.getUrlChat()))
                .thenReturn(Optional.empty());
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", currentLink.getUrlChat()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(
                        "120363123@g.us",
                        "Current company. Отзывы",
                        currentLink.getUrlChat()
                )));

        int repaired = service.repairConflictingInviteGroupIds(properties.getClients(), "test");

        assertEquals(0, repaired);
        assertEquals("https://chat.whatsapp.com/OldInviteCode123456789", oldLink.getUrlChat());
        verify(companyRepository, never()).save(oldLink);
    }

    private static Company company(Long id, String title) {
        Company company = new Company();
        company.setId(id);
        company.setTitle(title);
        company.setUrlChat("https://chat.whatsapp.com/invitecode" + id);
        return company;
    }
}
