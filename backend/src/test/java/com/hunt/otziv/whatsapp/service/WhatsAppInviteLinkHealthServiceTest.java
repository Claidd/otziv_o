package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.whatsapp.api.WhatsAppInviteLinkHealth;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppInviteLinkHealthServiceTest {

    private static final String GROUP = "120363160089865886@g.us";
    private static final String STORED = "Bxdmj71FH80B0BxZksCLAj";
    private static final String CURRENT = "NewInviteCode12345";

    private final WhatsAppProperties properties = new WhatsAppProperties();
    private final WhatsAppService whatsAppService = mock(WhatsAppService.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final WhatsAppGroupCompanyLinker linker = new WhatsAppGroupCompanyLinker(companyRepository);
    private final WhatsAppInviteLinkCheckStore store = mock(WhatsAppInviteLinkCheckStore.class);
    private final Map<Long, WhatsAppInviteLinkCheckStore.Row> rows = new LinkedHashMap<>();
    private WhatsAppInviteLinkHealthService service;
    private Company company;

    @BeforeEach
    void setUp() {
        var client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        properties.setClients(List.of(client));
        service = new WhatsAppInviteLinkHealthService(properties, whatsAppService, linker, store, true, 20);
        company = company(191L, "https://chat.whatsapp.com/" + STORED + "?s=cl&p=a&iam=2", GROUP, "В работе");
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company));
        when(companyRepository.findById(191L)).thenReturn(Optional.of(company));
        when(store.all()).thenReturn(rows);
    }

    @Test
    void aMismatchIsSuspectedFirstAndConfirmedOnlyByALaterDifferentSnapshot() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        snapshot(CURRENT, "2026-11-05T11:58:00Z", false);

        assertEquals(0, service.runCheck(now));
        verify(store).suspect(191L, GROUP, STORED, "https://chat.whatsapp.com/" + CURRENT, "whatsapp_lika@2026-11-05T11:58:00Z", now);

        rows.put(191L, new WhatsAppInviteLinkCheckStore.Row(191L, GROUP, STORED, null, WhatsAppInviteLinkCheckStore.SUSPECTED,
                now, "whatsapp_lika@2026-11-05T11:58:00Z", null));
        // The gateway cache has not moved: a stuck snapshot never confirms anything.
        assertEquals(0, service.runCheck(now.plusMinutes(30)));
        verify(store, never()).confirm(any(), any(), any(), any(), any());

        snapshot(CURRENT, "2026-11-05T12:29:00Z", true);
        assertEquals(1, service.runCheck(now.plusMinutes(31)));
        verify(store).confirm(191L, GROUP, STORED, "https://chat.whatsapp.com/" + CURRENT, now.plusMinutes(31));
        neverChangesBindingsOrRefreshesTheGateway();
    }

    @Test
    void aMatchingInviteOrANewlySavedLinkClearsTheRecord() {
        LocalDateTime now = LocalDateTime.now();
        rows.put(191L, new WhatsAppInviteLinkCheckStore.Row(191L, GROUP, STORED, null, WhatsAppInviteLinkCheckStore.STALE,
                now.minusHours(2), "x", now.minusHours(1)));
        snapshot(STORED, "2026-11-05T12:00:00Z", false);

        assertEquals(0, service.runCheck(now));
        verify(store).delete(191L);

        // The manager replaced the link: the old record goes, the new link starts its own check.
        company.setUrlChat("https://chat.whatsapp.com/AnotherCode98765");
        snapshot(CURRENT, "2026-11-05T12:40:00Z", false);
        service.runCheck(now.plusMinutes(40));
        verify(store, org.mockito.Mockito.times(2)).delete(191L);
        verify(store).suspect(191L, GROUP, "AnotherCode98765", "https://chat.whatsapp.com/" + CURRENT,
                "whatsapp_lika@2026-11-05T12:40:00Z", now.plusMinutes(40));
    }

    @Test
    void unavailableGatewayUnreadableInviteAndIneligibleCompaniesAreNeverFlagged() {
        LocalDateTime now = LocalDateTime.now();
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(WhatsAppService.GroupsSnapshot.unavailable());
        service.runCheck(now);

        // The account is not an administrator of the group: WhatsApp shows no invite.
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null)), true, false, "2026-11-05T12:00:00Z"));
        service.runCheck(now);
        verify(store, never()).suspect(any(), any(), any(), any(), any(), any());

        Company banned = company(5L, "https://chat.whatsapp.com/BannedCode123456", "1@g.us", "Бан");
        Company inactive = company(6L, "https://chat.whatsapp.com/InactiveCode1234", "2@g.us", "В работе");
        inactive.setActive(false);
        Company pending = company(7L, "https://chat.whatsapp.com/PendingCode12345", "3@g.us", "В работе");
        pending.setWhatsAppInvitePending(true);
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(banned, inactive, pending));
        rows.put(5L, new WhatsAppInviteLinkCheckStore.Row(5L, "1@g.us", "BannedCode123456", null,
                WhatsAppInviteLinkCheckStore.STALE, now, "x", now));
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(List.of(
                new WhatsAppGroupInfo("1@g.us", "B", "https://chat.whatsapp.com/Other1234567890"),
                new WhatsAppGroupInfo("2@g.us", "I", "https://chat.whatsapp.com/Other1234567891"),
                new WhatsAppGroupInfo("3@g.us", "P", "https://chat.whatsapp.com/Other1234567892")), true, false, "t2"));
        service.runCheck(now);
        verify(store, never()).suspect(any(), any(), any(), any(), any(), any());
        verify(store).delete(5L);
        neverChangesBindingsOrRefreshesTheGateway();
    }

    @Test
    void managersRecheckUsesAFreshListOrTheInviteLookupAndOtherwiseStaysUnknown() {
        snapshot(STORED, "2026-11-05T12:00:00Z", false);
        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.VALID, service.recheck(191L).result());

        snapshot(CURRENT, "2026-11-05T12:01:00Z", false);
        var stale = service.recheck(191L);
        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.STALE, stale.result());
        assertEquals("https://chat.whatsapp.com/" + CURRENT, stale.currentInviteLink());

        // An outdated cache is not trusted for an immediate verdict; the stored link is looked up instead.
        snapshot(CURRENT, "2026-11-05T11:00:00Z", true);
        when(whatsAppService.resolveGroupByInvite("whatsapp_lika", company.getUrlChat().trim()))
                .thenReturn(Optional.of(new WhatsAppGroupInfo(GROUP, "Toyama", null)));
        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.VALID, service.recheck(191L).result());

        when(whatsAppService.resolveGroupByInvite(anyString(), anyString())).thenReturn(Optional.empty());
        var unknown = service.recheck(191L);
        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.UNKNOWN, unknown.result());
        assertNull(unknown.currentInviteLink());

        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.NOT_APPLICABLE, service.recheck(999L).result());
        neverChangesBindingsOrRefreshesTheGateway();
    }

    @Test
    void unreadableInvitesAreCountedByTheGatewaysReasonInsteadOfLookingClean() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);
        Company member = company(192L, "https://chat.whatsapp.com/MemberCode12345", "2@g.us", "В работе");
        Company left = company(193L, "https://chat.whatsapp.com/LeftCode1234567", "3@g.us", "В работе");
        Company older = company(194L, "https://chat.whatsapp.com/OlderCode123456", "4@g.us", "В работе");
        Company unlisted = company(195L, "https://chat.whatsapp.com/MissingCode1234", "5@g.us", "В работе");
        when(companyRepository.findAllWithChatUrl()).thenReturn(List.of(company, member, left, older, unlisted));
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(List.of(
                new WhatsAppGroupInfo(GROUP, "Toyama", "https://chat.whatsapp.com/" + STORED, "ok"),
                new WhatsAppGroupInfo("2@g.us", "Member", "", "not_admin"),
                new WhatsAppGroupInfo("3@g.us", "Left", "", "not_member"),
                new WhatsAppGroupInfo("4@g.us", "Older gateway", "", null)), true, false, "2026-11-06T11:59:00Z"));

        var summary = service.check(now);

        assertEquals(true, summary.observed());
        assertEquals(5, summary.targets());
        assertEquals(1, summary.readable());
        assertEquals(Map.of("not_admin", 1, "not_member", 1, "unreported", 1, "not_listed", 1), summary.unreadable());
        assertEquals(0, summary.stale());
        verify(store, never()).suspect(any(), any(), any(), any(), any(), any());
        neverChangesBindingsOrRefreshesTheGateway();
    }

    @Test
    void aListWithoutAnyReadableInviteIsReportedWithItsReasons() {
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null, "timeout")), true, false, "2026-11-06T12:00:00Z"));

        var summary = service.check(LocalDateTime.now());
        assertEquals(1, summary.targets());
        assertEquals(0, summary.readable());
        assertEquals(Map.of("timeout", 1), summary.unreadable());

        // An unavailable gateway is not an observation: nothing is counted as unreadable.
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(WhatsAppService.GroupsSnapshot.unavailable());
        var unavailable = service.check(LocalDateTime.now());
        assertEquals(false, unavailable.observed());
        assertEquals(Map.of(), unavailable.unreadable());
    }

    @Test
    void theAccountStillInTheGroupExplainsAnUnreadableInviteBetterThanTheOneThatLeft() {
        var vika = new WhatsAppProperties.ClientConfig();
        vika.setId("whatsapp_vika");
        vika.setUrl("http://whatsapp_vika:3000");
        properties.setClients(List.of(properties.getClients().getFirst(), vika));
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null, "not_member")), true, false, "t1"));
        when(whatsAppService.groupsSnapshot("whatsapp_vika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null, "not_admin")), true, false, "t2"));
        assertEquals(Map.of("not_admin", 1), service.check(LocalDateTime.now()).unreadable());

        // Different invites from two accounts: one cache is outdated, so neither is trusted.
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", "https://chat.whatsapp.com/" + STORED, "ok")), true, false, "t3"));
        when(whatsAppService.groupsSnapshot("whatsapp_vika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", "https://chat.whatsapp.com/" + CURRENT, "ok")), true, false, "t4"));
        assertEquals(Map.of("conflicting", 1), service.check(LocalDateTime.now()).unreadable());
        verify(store, never()).suspect(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownRecheckCarriesWhyTheAccountCannotReadTheInvite() {
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null, "not_member")), true, false, "2026-11-06T12:00:00Z"));
        when(whatsAppService.resolveGroupByInvite(anyString(), anyString())).thenReturn(Optional.empty());

        var recheck = service.recheck(191L);
        assertEquals(WhatsAppInviteLinkHealth.RecheckResult.UNKNOWN, recheck.result());
        assertEquals(WhatsAppInviteLinkHealth.UNREADABLE_NOT_MEMBER, recheck.unreadableReason());

        // Without a fresh list there is no reason to report.
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama", null, "not_member")), true, true, "2026-11-06T11:00:00Z"));
        assertNull(service.recheck(191L).unreadableReason());
        neverChangesBindingsOrRefreshesTheGateway();
    }

    private void snapshot(String code, String cachedAt, boolean stale) {
        when(whatsAppService.groupsSnapshot("whatsapp_lika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo(GROUP, "Toyama motors", "https://chat.whatsapp.com/" + code)),
                true, stale, cachedAt));
    }

    private void neverChangesBindingsOrRefreshesTheGateway() {
        verify(companyRepository, never()).save(any());
        verify(whatsAppService, never()).listGroups(anyString(), anyBoolean());
    }

    private static Company company(Long id, String url, String groupId, String status) {
        Company company = new Company();
        company.setId(id);
        company.setActive(true);
        company.setUrlChat(url);
        company.setGroupId(groupId);
        CompanyStatus companyStatus = new CompanyStatus();
        companyStatus.setTitle(status);
        company.setStatus(companyStatus);
        return company;
    }
}
