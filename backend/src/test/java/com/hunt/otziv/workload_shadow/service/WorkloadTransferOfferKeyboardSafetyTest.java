package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.DeliveryProjection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkloadTransferOfferKeyboardSafetyTest {

    @Test
    void telegramKeyboardFailureClosesPersistedOfferBeforeItCanBeAccepted() {
        WorkloadTransferOfferService offerService =
                mock(WorkloadTransferOfferService.class);
        WorkloadTransferOfferScopeService scopeService =
                mock(WorkloadTransferOfferScopeService.class);
        WorkloadLiveSettingsService liveSettingsService =
                mock(WorkloadLiveSettingsService.class);
        TelegramService telegramService = mock(TelegramService.class);
        DeliveryProjection offer = mock(DeliveryProjection.class);

        when(offer.getOfferId()).thenReturn(51L);
        when(offer.getManagerId()).thenReturn(71L);
        when(offer.getOfferToken()).thenReturn(UUID.randomUUID().toString());
        when(offer.getTargetGroupChatId()).thenReturn(-10051L);
        when(offer.getCandidateWorkerName()).thenReturn("Елена");
        when(offer.getCompanyTitle()).thenReturn("Компания");
        when(offer.getSourceWorkerName()).thenReturn("Альфия");
        when(offerService.claimDueOffers()).thenReturn(
                new WorkloadTransferOfferService.ClaimedOffers(
                        "lease",
                        List.of(offer),
                        15
                )
        );
        WorkloadLiveSettingsResponse settings = settings();
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.managerAllowed(settings, 71L)).thenReturn(true);
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(-10051L),
                anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(Optional.of(701));
        when(telegramService.editMessageText(
                eq(-10051L),
                eq(701),
                anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(false);

        int delivered = new WorkloadTransferOfferDeliveryService(
                offerService,
                scopeService,
                liveSettingsService,
                telegramService
        ).deliverDueOffers();

        assertThat(delivered).isZero();
        verify(offerService).markDelivered(51L, "lease", 701, 15);
        verify(offerService).markKeyboardActivationFailure(51L, 701);
        verify(offerService, never()).markKeyboardActivated(51L, 701);
    }

    private WorkloadLiveSettingsResponse settings() {
        return new WorkloadLiveSettingsResponse(
                "LIVE",
                true,
                "2026-08-01",
                14,
                168,
                1,
                List.of(),
                15,
                "00:00",
                "23:59",
                1,
                3,
                30,
                5,
                false,
                1
        );
    }
}
