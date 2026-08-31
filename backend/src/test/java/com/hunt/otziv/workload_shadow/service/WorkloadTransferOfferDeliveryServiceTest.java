package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferOfferDeliveryServiceTest {

    @Mock private WorkloadTransferOfferService offerService;
    @Mock private WorkloadTransferOfferScopeService offerScopeService;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private TelegramService telegramService;

    @Test
    void successfulTelegramDeliveryStartsConfiguredThreeHourResponseWindow() {
        var projection =
                mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        String offerToken = UUID.randomUUID().toString();
        when(projection.getOfferId()).thenReturn(51L);
        when(projection.getManagerId()).thenReturn(71L);
        when(projection.getOfferToken()).thenReturn(offerToken);
        when(projection.getTargetGroupChatId()).thenReturn(-10051L);
        when(projection.getCandidateWorkerName()).thenReturn("Елена");
        when(projection.getCompanyTitle()).thenReturn("Компания");
        when(projection.getSourceWorkerName()).thenReturn("Альфия");
        when(offerService.claimDueOffers()).thenReturn(
                new WorkloadTransferOfferService.ClaimedOffers(
                        "delivery-lease",
                        List.of(projection),
                        180
                )
        );
        WorkloadLiveSettingsResponse settings = settings("LIVE", List.of());
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.managerAllowed(settings, 71L))
                .thenReturn(true);
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(-10051L),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(Optional.of(701));
        when(telegramService.editMessageText(
                eq(-10051L),
                eq(701),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(true);

        int delivered = new WorkloadTransferOfferDeliveryService(
                offerService,
                offerScopeService,
                liveSettingsService,
                telegramService
        ).deliverDueOffers();

        assertThat(delivered).isEqualTo(1);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessageWithInlineKeyboardMessageId(
                eq(-10051L),
                message.capture(),
                eq("HTML"),
                anyList()
        );
        assertThat(message.getValue())
                .contains("3 часа с момента доставки")
                .doesNotContain("180 мин.")
                .doesNotContain("Ответьте до");
        verify(offerService).markDelivered(
                51L,
                "delivery-lease",
                701,
                180
        );
    }

    @Test
    void cancelsExcludedCanaryOffersInOneBatchBeforeTelegramSend() {
        var allowed =
                mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        var excluded =
                mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        when(allowed.getOfferId()).thenReturn(51L);
        when(allowed.getManagerId()).thenReturn(71L);
        when(allowed.getOfferToken()).thenReturn(UUID.randomUUID().toString());
        when(allowed.getTargetGroupChatId()).thenReturn(-10051L);
        when(allowed.getCandidateWorkerName()).thenReturn("Елена");
        when(allowed.getCompanyTitle()).thenReturn("Компания");
        when(allowed.getSourceWorkerName()).thenReturn("Альфия");
        when(excluded.getOfferId()).thenReturn(52L);
        when(excluded.getManagerId()).thenReturn(72L);
        when(offerService.claimDueOffers()).thenReturn(
                new WorkloadTransferOfferService.ClaimedOffers(
                        "delivery-lease",
                        List.of(allowed, excluded),
                        19
                )
        );
        WorkloadLiveSettingsResponse settings =
                settings("CANARY", List.of(71L));
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.managerAllowed(settings, 71L))
                .thenReturn(true);
        when(liveSettingsService.managerAllowed(settings, 72L))
                .thenReturn(false);
        when(offerScopeService.cancelClaimedOutsideScope(
                "delivery-lease",
                List.of(52L)
        )).thenReturn(3);
        when(telegramService.sendMessageWithInlineKeyboardMessageId(
                eq(-10051L),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(Optional.of(701));
        when(telegramService.editMessageText(
                eq(-10051L),
                eq(701),
                org.mockito.ArgumentMatchers.anyString(),
                eq("HTML"),
                anyList()
        )).thenReturn(true);

        int delivered = new WorkloadTransferOfferDeliveryService(
                offerService,
                offerScopeService,
                liveSettingsService,
                telegramService
        ).deliverDueOffers();

        assertThat(delivered).isEqualTo(1);
        verify(liveSettingsService).current();
        InOrder deliveryOrder = inOrder(offerScopeService, telegramService);
        deliveryOrder.verify(offerScopeService).cancelClaimedOutsideScope(
                "delivery-lease",
                List.of(52L)
        );
        deliveryOrder.verify(telegramService)
                .sendMessageWithInlineKeyboardMessageId(
                        eq(-10051L),
                        org.mockito.ArgumentMatchers.anyString(),
                        eq("HTML"),
                        anyList()
                );
        verify(telegramService, never())
                .sendMessageWithInlineKeyboardMessageId(
                        eq(-10052L),
                        org.mockito.ArgumentMatchers.anyString(),
                        eq("HTML"),
                        anyList()
                );
        verify(offerService, never()).markDeliveryFailure(
                eq(52L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(offerService).markDelivered(
                51L,
                "delivery-lease",
                701,
                19
        );
    }

    @Test
    void stoppedModeCancelsTheClaimedOfferWithoutTelegramSideEffects() {
        var projection =
                mock(WorkloadTransferOfferRepository.DeliveryProjection.class);
        when(projection.getOfferId()).thenReturn(52L);
        when(projection.getManagerId()).thenReturn(72L);
        when(offerService.claimDueOffers()).thenReturn(
                new WorkloadTransferOfferService.ClaimedOffers(
                        "delivery-lease",
                        List.of(projection),
                        19
                )
        );
        WorkloadLiveSettingsResponse settings =
                settings("SHADOW", List.of());
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.managerAllowed(settings, 72L))
                .thenReturn(false);
        when(offerScopeService.cancelClaimedOutsideScope(
                "delivery-lease",
                List.of(52L)
        )).thenReturn(3);

        int delivered = new WorkloadTransferOfferDeliveryService(
                offerService,
                offerScopeService,
                liveSettingsService,
                telegramService
        ).deliverDueOffers();

        assertThat(delivered).isZero();
        verify(offerScopeService).cancelClaimedOutsideScope(
                "delivery-lease",
                List.of(52L)
        );
        verifyNoInteractions(telegramService);
        verify(offerService, never()).markDelivered(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    private WorkloadLiveSettingsResponse settings(
            String mode,
            List<Long> canaryManagerIds
    ) {
        return new WorkloadLiveSettingsResponse(
                mode,
                true,
                "2026-08-01",
                14,
                168,
                1,
                canaryManagerIds,
                30,
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
