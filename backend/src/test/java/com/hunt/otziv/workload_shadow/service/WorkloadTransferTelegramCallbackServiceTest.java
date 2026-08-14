package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository.LiveControlProjection;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository.CallbackProjection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferTelegramCallbackServiceTest {

    private static final long GROUP_CHAT_ID = -100500L;
    private static final long ACTOR_TELEGRAM_ID = 9001L;
    private static final long MANAGER_ID = 71L;
    private static final int MESSAGE_ID = 17;
    private static final String OFFER_TOKEN =
            UUID.fromString("1e1f5af4-8150-4d6a-962b-0a58fd470f39").toString();

    @Mock private WorkloadTransferOfferRepository repository;
    @Mock private WorkloadLiveSettingsService liveSettingsService;
    @Mock private WorkloadShadowSettingsService shadowSettingsService;
    @Mock private WorkloadLiveControlRepository liveControlRepository;
    @Mock private LiveControlProjection liveControl;
    @Mock private TelegramService telegramService;
    @Mock private CallbackProjection projection;

    private WorkloadTransferTelegramCallbackService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadTransferTelegramCallbackService(
                repository,
                liveSettingsService,
                shadowSettingsService,
                telegramService,
                liveControlRepository
        );
        lenient().when(liveControlRepository.lockState()).thenReturn(Optional.of(liveControl));
        lenient().when(liveControl.getSettingsRevision()).thenReturn(1L);
        lenient().when(liveControl.getMode()).thenReturn("LIVE");
        lenient().when(liveControl.getApplyEnabled()).thenReturn("true");
    }

    @Test
    void ignoresCallbacksThatDoNotBelongToTheWorkloadContour() {
        assertThat(service.handle(callback("other:action"))).isEmpty();
        verifyNoInteractions(
                repository,
                liveSettingsService,
                shadowSettingsService,
                telegramService
        );
    }

    @Test
    void stoppedContourRejectsTheAnswerWithoutChangingAnything() {
        WorkloadLiveSettingsResponse settings = settings("SHADOW", false);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(false);

        Optional<String> result = service.handle(callback("wlt:a:" + OFFER_TOKEN));

        assertThat(result).contains("Боевой контур остановлен. Назначения не изменены");
        verifyNoInteractions(repository, shadowSettingsService, telegramService);
    }

    @Test
    void declineIsBoundToTheExpectedGroupMessageAndTelegramActor() {
        enableLive();
        offeredProjection();
        when(repository.findCallbackOffer(OFFER_TOKEN))
                .thenReturn(Optional.of(projection));
        when(repository.decline(
                eq(OFFER_TOKEN),
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                eq(ACTOR_TELEGRAM_ID),
                eq(MANAGER_ID),
                eq(1L),
                any(LocalDateTime.class)
        // MySQL reports offer + workflow + candidate for this atomic transition.
        )).thenReturn(3);

        Optional<String> result = service.handle(callback("wlt:d:" + OFFER_TOKEN));

        assertThat(result).contains("Отказ принят. Предложение перейдёт следующему кандидату");
        verify(repository).decline(
                eq(OFFER_TOKEN),
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                eq(ACTOR_TELEGRAM_ID),
                eq(MANAGER_ID),
                eq(1L),
                any(LocalDateTime.class)
        );
        verify(repository, never()).accept(
                any(),
                anyLong(),
                anyInt(),
                anyLong(),
                any(),
                anyLong(),
                any(LocalDateTime.class)
        );
        verify(telegramService).editMessageText(
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                org.mockito.ArgumentMatchers.contains("Предложение отклонено"),
                eq("HTML"),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void acceptTreatsThreeChangedMysqlRowsAsOneLogicalTransition() {
        enableLive();
        offeredProjection();
        when(repository.findCallbackOffer(OFFER_TOKEN))
                .thenReturn(Optional.of(projection));
        when(repository.accept(
                eq(OFFER_TOKEN),
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                eq(ACTOR_TELEGRAM_ID),
                eq(MANAGER_ID),
                eq(1L),
                any(LocalDateTime.class)
        )).thenReturn(3);

        Optional<String> result = service.handle(callback("wlt:a:" + OFFER_TOKEN));

        assertThat(result).contains(
                "Согласие принято. Передача будет выполнена только после повторной проверки"
        );
        verify(repository).accept(
                eq(OFFER_TOKEN),
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                eq(ACTOR_TELEGRAM_ID),
                eq(MANAGER_ID),
                eq(1L),
                any(LocalDateTime.class)
        );
        verify(telegramService).editMessageText(
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                org.mockito.ArgumentMatchers.contains("Предложение принято"),
                eq("HTML"),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void acceptIsRejectedWhenActorOrMessageDoesNotMatchRepositoryGuards() {
        enableLive();
        offeredProjection();
        when(repository.findCallbackOffer(OFFER_TOKEN))
                .thenReturn(Optional.of(projection));
        when(repository.accept(
                eq(OFFER_TOKEN),
                eq(GROUP_CHAT_ID),
                eq(MESSAGE_ID),
                eq(ACTOR_TELEGRAM_ID),
                eq(MANAGER_ID),
                eq(1L),
                any(LocalDateTime.class)
        )).thenReturn(0);

        Optional<String> result = service.handle(callback("wlt:a:" + OFFER_TOKEN));

        assertThat(result).contains(
                "Ответ не принят: предложение изменилось или предназначено другому сотруднику"
        );
        verifyNoInteractions(telegramService);
    }

    @Test
    void canaryRemovalRejectsBothAcceptAndDeclineBeforeAnyMutation() {
        WorkloadLiveSettingsResponse settings = settings(
                "CANARY",
                true,
                List.of(999L)
        );
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(projection.getManagerId()).thenReturn(MANAGER_ID);
        when(repository.findCallbackOffer(OFFER_TOKEN))
                .thenReturn(Optional.of(projection));
        when(liveSettingsService.managerAllowed(settings, MANAGER_ID))
                .thenReturn(false);

        Optional<String> accept =
                service.handle(callback("wlt:a:" + OFFER_TOKEN));
        Optional<String> decline =
                service.handle(callback("wlt:d:" + OFFER_TOKEN));

        assertThat(accept).contains(
                "Менеджер больше не входит в боевой контур. "
                        + "Ответ не принят, назначения не изменены"
        );
        assertThat(decline).contains(
                "Менеджер больше не входит в боевой контур. "
                        + "Ответ не принят, назначения не изменены"
        );
        verify(liveSettingsService, times(2))
                .managerAllowed(settings, MANAGER_ID);
        verify(repository, never()).accept(
                any(),
                anyLong(),
                anyInt(),
                anyLong(),
                any(),
                anyLong(),
                any(LocalDateTime.class)
        );
        verify(repository, never()).decline(
                any(),
                anyLong(),
                anyInt(),
                anyLong(),
                any(),
                anyLong(),
                any(LocalDateTime.class)
        );
        verifyNoInteractions(shadowSettingsService, telegramService);
    }

    private void enableLive() {
        WorkloadLiveSettingsResponse settings = settings("LIVE", true);
        when(liveSettingsService.current()).thenReturn(settings);
        when(liveSettingsService.applicationAllowed(settings)).thenReturn(true);
        when(liveSettingsService.managerAllowed(settings, MANAGER_ID))
                .thenReturn(true);
        when(shadowSettingsService.current()).thenReturn(null);
        when(shadowSettingsService.zone(null)).thenReturn(ZoneId.of("Asia/Irkutsk"));
    }

    private void offeredProjection() {
        when(projection.getOfferStatus()).thenReturn("OFFERED");
        when(projection.getWorkflowStatus()).thenReturn("OFFERED");
        when(projection.getManagerId()).thenReturn(MANAGER_ID);
        when(projection.getExpiresAt()).thenReturn(LocalDateTime.now().plusHours(1));
        lenient().when(projection.getCompanyTitle()).thenReturn("Тестовая компания");
    }

    private CallbackQuery callback(String data) {
        Chat chat = new Chat();
        chat.setId(GROUP_CHAT_ID);
        chat.setType("supergroup");

        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(MESSAGE_ID);

        org.telegram.telegrambots.meta.api.objects.User from =
                new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(ACTOR_TELEGRAM_ID);

        CallbackQuery callback = new CallbackQuery();
        callback.setMessage(message);
        callback.setFrom(from);
        callback.setData(data);
        return callback;
    }

    private WorkloadLiveSettingsResponse settings(String mode, boolean applyEnabled) {
        return settings(mode, applyEnabled, List.of());
    }

    private WorkloadLiveSettingsResponse settings(
            String mode,
            boolean applyEnabled,
            List<Long> canaryManagerIds
    ) {
        return new WorkloadLiveSettingsResponse(
                mode,
                applyEnabled,
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
