package com.hunt.otziv.notification_media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import com.hunt.otziv.notification_media.repository.NotificationMediaDeliveryRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationMediaDeliveryServiceTest {

    @Mock
    private NotificationMediaSelector selector;
    @Mock
    private NotificationMediaDeliveryRepository deliveryRepository;
    @Mock
    private TelegramService telegramService;
    @Mock
    private NotificationMediaStorageService storageService;

    private NotificationMediaDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationMediaDeliveryService(
                selector,
                deliveryRepository,
                telegramService,
                storageService
        );
    }

    @Test
    void successfulPhotoDoesNotSendDuplicateText() {
        String eventCode = NotificationMediaEventCatalog.WORKER_TASK_FIRST.code();
        NotificationMediaSelector.Selection selection =
                selection(eventCode);
        when(selector.select(eq(eventCode), eq(-100L), any())).thenReturn(Optional.of(selection));
        when(storageService.load("notification-media/card.png")).thenReturn(new byte[]{1, 2, 3});
        when(telegramService.sendPhotoBytesWithInlineKeyboard(
                -100L,
                new byte[]{1, 2, 3},
                "card.png",
                "Текст",
                null,
                List.of()
        )).thenReturn(true);

        boolean sent = service.send(eventCode, -100L, 9L, "Текст", null, List.of());

        assertThat(sent).isTrue();
        verify(telegramService, never()).sendMessageWithInlineKeyboard(any(Long.class), any(), any(), any());
        ArgumentCaptor<NotificationMediaDelivery> captor =
                ArgumentCaptor.forClass(NotificationMediaDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertThat(captor.getValue().isPhotoSent()).isTrue();
        assertThat(captor.getValue().getAssetId()).isEqualTo(4L);
    }

    @Test
    void failedPhotoFallsBackToOriginalText() {
        String eventCode = NotificationMediaEventCatalog.MANAGER_REPORT_REMINDER.code();
        NotificationMediaSelector.Selection selection =
                selection(eventCode);
        when(selector.select(eq(eventCode), eq(-200L), any())).thenReturn(Optional.of(selection));
        when(storageService.load("notification-media/card.png")).thenReturn(new byte[]{1, 2, 3});
        when(telegramService.sendPhotoBytesWithInlineKeyboard(
                -200L,
                new byte[]{1, 2, 3},
                "card.png",
                "Текст",
                "HTML",
                List.of()
        )).thenReturn(false);
        when(telegramService.sendMessageWithInlineKeyboard(-200L, "Текст", "HTML", List.of()))
                .thenReturn(true);

        boolean sent = service.send(eventCode, -200L, 8L, "Текст", "HTML", List.of());

        assertThat(sent).isTrue();
        verify(telegramService).sendMessageWithInlineKeyboard(-200L, "Текст", "HTML", List.of());
        ArgumentCaptor<NotificationMediaDelivery> captor =
                ArgumentCaptor.forClass(NotificationMediaDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertThat(captor.getValue().isPhotoSent()).isFalse();
        assertThat(captor.getValue().getDeliveryNote()).isEqualTo("PHOTO_FAILED_TEXT_FALLBACK");
    }

    @Test
    void s3ReadFailureFallsBackToPublicUrl() {
        String eventCode = NotificationMediaEventCatalog.WORKER_TASK_FIRST.code();
        NotificationMediaSelector.Selection selection = selection(eventCode);
        when(selector.select(eq(eventCode), eq(-300L), any())).thenReturn(Optional.of(selection));
        when(storageService.load("notification-media/card.png"))
                .thenThrow(new IllegalStateException("S3 unavailable"));
        when(telegramService.sendPhotoWithInlineKeyboard(
                -300L,
                "https://cdn/card.png",
                "Текст",
                "HTML",
                List.of()
        )).thenReturn(true);

        boolean sent = service.send(eventCode, -300L, 7L, "Текст", "HTML", List.of());

        assertThat(sent).isTrue();
        verify(telegramService, never())
                .sendMessageWithInlineKeyboard(any(Long.class), any(), any(), any());
    }

    @Test
    void mediaOnlyDoesNotSendTextFallbackWhenPhotoFails() {
        String eventCode = NotificationMediaEventCatalog.WORKER_PROGRESS_GROWING.code();
        NotificationMediaSelector.Selection selection = selection(eventCode);
        when(selector.select(eq(eventCode), eq(-400L), any())).thenReturn(Optional.of(selection));
        when(storageService.load("notification-media/card.png")).thenReturn(new byte[]{1, 2, 3});
        when(telegramService.sendPhotoBytesWithInlineKeyboard(
                -400L,
                new byte[]{1, 2, 3},
                "card.png",
                "Личный отчёт готов",
                "HTML",
                List.of()
        )).thenReturn(false);

        boolean sent = service.sendMediaOnly(
                eventCode, -400L, 6L, "Личный отчёт готов", "HTML"
        );

        assertThat(sent).isFalse();
        verify(telegramService, never())
                .sendMessageWithInlineKeyboard(any(Long.class), any(), any(), any());
        ArgumentCaptor<NotificationMediaDelivery> captor =
                ArgumentCaptor.forClass(NotificationMediaDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryNote())
                .isEqualTo("PHOTO_FAILED_NO_TEXT_FALLBACK");
    }

    private NotificationMediaSelector.Selection selection(String eventCode) {
        return new NotificationMediaSelector.Selection(
                3L,
                4L,
                eventCode,
                "https://cdn/card.png",
                "notification-media/card.png",
                "card.png",
                "image/png"
        );
    }
}
