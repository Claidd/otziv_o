package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.api.WhatsAppInviteLinkHealth;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A bound WhatsApp group whose stored invite no longer opens it becomes the company's chat-link card. */
class ManagerControlStaleWhatsAppInviteTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final WhatsAppInviteLinkHealth health = mock(WhatsAppInviteLinkHealth.class);
    private final ManagerControlConcretePresenter presenter = mock(ManagerControlConcretePresenter.class);
    private ManagerControlProblemExamples examples;
    private Manager manager;

    @BeforeEach
    void setUp() {
        examples = new ManagerControlProblemExamples(mock(ManagerControlClientMessageText.class),
                presenter, mock(ManagerControlOrderAutomationDiagnostics.class),
                mock(com.hunt.otziv.p_products.service.OrderService.class),
                mock(com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService.class),
                mock(com.hunt.otziv.config.settings.service.AppSettingService.class),
                mock(com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService.class),
                mock(com.hunt.otziv.bad_reviews.service.BadReviewTaskService.class),
                mock(com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService.class),
                mock(com.hunt.otziv.r_review.repository.ReviewRepository.class),
                mock(com.hunt.otziv.p_products.repository.OrderRepository.class), companyRepository,
                mock(com.hunt.otziv.payments.repository.PaymentLinkRepository.class), mock(ManagerControlInvoiceDiagnostics.class),
                mock(ManagerAutomationFailureService.class),
                mock(com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository.class),
                mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository.class),
                mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository.class), health);
        manager = new Manager();
        manager.setId(3L);
        when(presenter.companySpecialistName(any())).thenReturn("");
    }

    @Test
    void onlyTheManagersEligibleCompaniesWithTheSameStoredLinkBecomeCards() {
        Company stale = company(191L, manager, "https://chat.whatsapp.com/Bxdmj71FH80B0BxZksCLAj?s=cl", "В работе");
        Manager other = new Manager();
        other.setId(2L);
        Company othersCompany = company(192L, other, "https://chat.whatsapp.com/OtherStoredCode1", "В работе");
        Company banned = company(193L, manager, "https://chat.whatsapp.com/BannedStoredCode", "Бан");
        Company replaced = company(194L, manager, "https://chat.whatsapp.com/ManagerSavedNew1", "В работе");
        when(health.staleInvites()).thenReturn(List.of(
                invite(191L, "Bxdmj71FH80B0BxZksCLAj", "https://chat.whatsapp.com/NewInviteCode12345"),
                invite(192L, "OtherStoredCode1", null),
                invite(193L, "BannedStoredCode", null),
                invite(194L, "OldStoredCode123", null)));
        when(companyRepository.findAllById(anyCollection())).thenReturn(List.of(stale, othersCompany, banned, replaced));
        when(companyRepository.findChatBindingIssuesByManager(manager)).thenReturn(List.of());

        assertEquals(1, examples.staleWhatsAppInviteCount(manager));
        var cards = examples.chatBindingIssueExamples(manager, LocalDate.now(), 10);

        assertEquals(1, cards.size());
        assertEquals("COMPANY_CHAT_BINDING", cards.getFirst().type());
        assertEquals(191L, cards.getFirst().entityId());
        assertTrue(cards.getFirst().reason().contains(ManagerControlProblemExamples.STALE_WHATSAPP_INVITE_REASON));
        assertTrue(cards.getFirst().reason().contains("https://chat.whatsapp.com/NewInviteCode12345"));
        assertTrue(cards.getFirst().reason().length() <= 500);
        assertEquals("https://chat.whatsapp.com/NewInviteCode12345", cards.getFirst().chatUrl());
    }

    @Test
    void healthUnavailableNeverBreaksTheControl() {
        when(health.staleInvites()).thenThrow(new IllegalStateException("database is busy"));
        assertEquals(0, examples.staleWhatsAppInviteCount(manager));
    }

    @Test
    void repairKeepsAStaleCardUntilTheManagerSavesAWorkingLink() {
        var repair = new ManagerControlChatRepairWorkflow(mock(ManagerControlRepairOutcome.class), examples,
                mock(ManagerControlOrderAutomationDiagnostics.class), mock(com.hunt.otziv.t_telegrambot.service.TelegramService.class),
                mock(com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository.class),
                mock(com.hunt.otziv.p_products.repository.OrderRepository.class), companyRepository,
                mock(com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService.class),
                mock(com.hunt.otziv.c_companies.service.SharedChatLinkSyncService.class),
                mock(com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService.class),
                mock(com.hunt.otziv.maxbot.service.MaxGroupLinkService.class));
        Company company = company(191L, manager, "https://chat.whatsapp.com/Bxdmj71FH80B0BxZksCLAj", "В работе");
        ManagerDailyControlConcreteItem staleCard = new ManagerDailyControlConcreteItem();
        staleCard.setEntityType("COMPANY_CHAT_BINDING");
        staleCard.setReason("Почему в контроле: " + ManagerControlProblemExamples.STALE_WHATSAPP_INVITE_REASON + ".");

        when(health.recheck(191L)).thenReturn(new WhatsAppInviteLinkHealth.Recheck(
                WhatsAppInviteLinkHealth.RecheckResult.STALE, "https://chat.whatsapp.com/NewInviteCode12345"));
        ResponseStatusException stale = assertThrows(ResponseStatusException.class,
                () -> repair.requireWorkingWhatsAppInvite(staleCard, company));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertTrue(stale.getReason().contains("https://chat.whatsapp.com/NewInviteCode12345"));
        assertTrue(stale.getReason().contains("Привязка группы автоматически не меняется"));

        when(health.recheck(191L)).thenReturn(new WhatsAppInviteLinkHealth.Recheck(WhatsAppInviteLinkHealth.RecheckResult.UNKNOWN, null));
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> repair.requireWorkingWhatsAppInvite(staleCard, company));
        assertTrue(unknown.getReason().contains("подключённый аккаунт сейчас не видит ссылку группы. Сохраните"));

        // The gateway's reason tells the manager what to do about it.
        when(health.recheck(191L)).thenReturn(new WhatsAppInviteLinkHealth.Recheck(WhatsAppInviteLinkHealth.RecheckResult.UNKNOWN,
                null, WhatsAppInviteLinkHealth.UNREADABLE_NOT_MEMBER));
        ResponseStatusException removed = assertThrows(ResponseStatusException.class,
                () -> repair.requireWorkingWhatsAppInvite(staleCard, company));
        assertTrue(removed.getReason().contains("больше не состоит в этой группе"));
        when(health.recheck(191L)).thenReturn(new WhatsAppInviteLinkHealth.Recheck(WhatsAppInviteLinkHealth.RecheckResult.UNKNOWN,
                null, WhatsAppInviteLinkHealth.UNREADABLE_NOT_ADMIN));
        ResponseStatusException notAdmin = assertThrows(ResponseStatusException.class,
                () -> repair.requireWorkingWhatsAppInvite(staleCard, company));
        assertTrue(notAdmin.getReason().contains("не администратор группы"));
        ManagerDailyControlConcreteItem unboundCard = new ManagerDailyControlConcreteItem();
        unboundCard.setEntityType("COMPANY_CHAT_BINDING");
        unboundCard.setReason("Почему в контроле: WhatsApp-группа не привязана к компании.");
        assertDoesNotThrow(() -> repair.requireWorkingWhatsAppInvite(unboundCard, company));

        when(health.recheck(191L)).thenReturn(new WhatsAppInviteLinkHealth.Recheck(WhatsAppInviteLinkHealth.RecheckResult.VALID, null));
        assertDoesNotThrow(() -> repair.requireWorkingWhatsAppInvite(staleCard, company));
    }

    private static WhatsAppInviteLinkHealth.StaleInvite invite(Long companyId, String code, String current) {
        return new WhatsAppInviteLinkHealth.StaleInvite(companyId, code, current, LocalDateTime.now());
    }

    private static Company company(Long id, Manager manager, String url, String status) {
        Company company = new Company();
        company.setId(id);
        company.setTitle("Компания " + id);
        company.setManager(manager);
        company.setActive(true);
        company.setUrlChat(url);
        company.setGroupId(id + "@g.us");
        CompanyStatus companyStatus = new CompanyStatus();
        companyStatus.setTitle(status);
        company.setStatus(companyStatus);
        return company;
    }
}
