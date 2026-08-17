package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BadReviewPaymentInstructionOrchestratorTest {

    @Mock
    private ObjectProvider<PaymentLinkService> paymentLinkServiceProvider;

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private BadReviewPaymentInstructionOrchestrator orchestrator;

    @Test
    void authorizedEntryPointUsesLockedAuthorizedPaymentLinkApi() {
        ManagerPaymentLinkResponse response = new ManagerPaymentLinkResponse(
                "token",
                "/pay/token",
                42L,
                BigDecimal.TEN,
                1_000L,
                "NEW",
                "MANAGER_TEXT",
                null,
                "Оплатите по свободному тексту менеджера",
                "Свободный текст для отправки"
        );
        when(authentication.isAuthenticated()).thenReturn(true);
        when(paymentLinkServiceProvider.getIfAvailable()).thenReturn(paymentLinkService);
        when(paymentLinkService.prepareForOrderAuthorized(42L, authentication))
                .thenReturn(new PaymentLinkService.PaymentInstructionPreparation(response, true));

        BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction prepared =
                orchestrator.prepareAuthorized(42L, authentication);

        assertEquals("Свободный текст для отправки", prepared.copyText());
        assertNull(prepared.telegramCopyTransferNumber());

        verify(paymentLinkService).prepareForOrderAuthorized(42L, authentication);
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void authorizedEntryPointRejectsMissingAuthenticationBeforeAnyMutation() {
        assertThrows(
                ResponseStatusException.class,
                () -> orchestrator.prepareCopyTextAuthorized(42L, null)
        );

        verify(paymentLinkServiceProvider, never()).getIfAvailable();
    }

    @Test
    void knownUnsentReleaseNeverCancelsReusedPreparation() {
        BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction reused =
                new BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction(
                        "copy", "existing-token", 42L, false
                );

        assertFalse(orchestrator.releaseKnownUnsent(reused, authentication));

        verify(paymentLinkServiceProvider, never()).getIfAvailable();
        verifyNoInteractions(paymentLinkService);
    }
}
