package com.hunt.otziv.whatsapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.c_companies.api.MissingWhatsAppRoute;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WhatsAppDeliveryRouteRecoveryTest {
    private final MissingWhatsAppRoute companies = mock(MissingWhatsAppRoute.class);
    private final WhatsAppService whatsapp = mock(WhatsAppService.class);
    private final WhatsAppDeliveryRouteRecovery recovery = new WhatsAppDeliveryRouteRecovery(companies, whatsapp);
    private final MissingWhatsAppRoute.Snapshot company = company("https://chat.whatsapp.com/VerifiedInvite1234");

    @Test void restoresAnExactInviteThroughTheSelectedManagerClientAndPersistsBeforeReturning() {
        when(companies.find(1L)).thenReturn(Optional.of(company));
        when(whatsapp.resolveGroupByInvite("client", company.invite())).thenReturn(Optional.of(new WhatsAppGroupInfo("123456789@g.us", "same name", company.invite())));
        when(companies.restore(company, "123456789@g.us")).thenReturn(true);
        assertThat(recover()).isEqualTo("123456789@g.us");
        var ordered = inOrder(whatsapp, companies);
        ordered.verify(whatsapp).resolveGroupByInvite("client", company.invite());
        ordered.verify(companies).restore(company, "123456789@g.us");
        verifyNoMoreInteractions(whatsapp);
    }

    @Test void concurrentLinkEditDiscardsThePreviouslyResolvedGroup() {
        when(companies.find(1L)).thenReturn(Optional.of(company));
        when(whatsapp.resolveGroupByInvite("client", company.invite())).thenReturn(Optional.of(new WhatsAppGroupInfo("123456789@g.us", "name", company.invite())));
        assertThat(recover()).isNull();
    }

    @Test void neverMatchesGroupsByNameWhenInviteLookupFails() {
        when(companies.find(1L)).thenReturn(Optional.of(company));
        when(whatsapp.resolveGroupByInvite("client", company.invite())).thenReturn(Optional.empty());
        assertThat(recover()).isNull();
        verify(whatsapp).resolveGroupByInvite("client", company.invite());
        verifyNoMoreInteractions(whatsapp);
    }

    @Test void staleInputCannotRecoverAfterTheManagerChangesTheInvite() {
        when(companies.find(1L)).thenReturn(Optional.of(company("https://chat.whatsapp.com/OtherInvite123456")));
        assertThat(recover()).isNull();
        verifyNoInteractions(whatsapp);
    }

    @Test void bannedCompanyCannotBeReactivatedByDeliveryRepair() {
        when(companies.find(1L)).thenReturn(Optional.of(new MissingWhatsAppRoute.Snapshot(1L, company.invite(), null, false)));
        assertThat(recover()).isNull();
        verifyNoInteractions(whatsapp);
    }

    @Test void aDatabaseTransactionNeverWaitsForTheGateway() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(recover()).isNull(); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(companies, whatsapp);
    }

    @Test void gatewayOutageKeepsTheSendKnownUnsentAndDoesNotOverwriteAnything() {
        when(companies.find(1L)).thenReturn(Optional.of(company));
        when(whatsapp.resolveGroupByInvite("client", company.invite())).thenThrow(new IllegalStateException("offline"));
        assertThat(recover()).isNull();
        verify(companies, never()).restore(any(), any());
    }

    private String recover() { return recovery.recoverMissingGroup(company.companyId(), company.invite(), "client"); }

    private static MissingWhatsAppRoute.Snapshot company(String invite) {
        return new MissingWhatsAppRoute.Snapshot(1L, invite, null, true);
    }
}
