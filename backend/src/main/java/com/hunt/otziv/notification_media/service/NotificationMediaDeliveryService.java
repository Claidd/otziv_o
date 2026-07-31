package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import com.hunt.otziv.notification_media.repository.NotificationMediaDeliveryRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationMediaDeliveryService {

    private final NotificationMediaSelector selector;
    private final NotificationMediaDeliveryRepository deliveryRepository;
    private final TelegramService telegramService;
    private final NotificationMediaStorageService storageService;

    public boolean send(
            String eventCode,
            long chatId,
            Long recipientUserId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        NotificationMediaSelector.Selection selection =
                selector.select(eventCode, chatId, LocalDateTime.now()).orElse(null);
        if (selection != null) {
            boolean photoSent = sendPhoto(selection, chatId, text, parseMode, keyboard);
            record(selection, chatId, recipientUserId, photoSent,
                    photoSent ? "PHOTO_SENT" : "PHOTO_FAILED_TEXT_FALLBACK");
            if (photoSent) {
                return true;
            }
        }
        return telegramService.sendMessageWithInlineKeyboard(chatId, text, parseMode, keyboard);
    }

    private boolean sendPhoto(
            NotificationMediaSelector.Selection selection,
            long chatId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        try {
            byte[] image = storageService.load(selection.storageKey());
            return telegramService.sendPhotoBytesWithInlineKeyboard(
                    chatId,
                    image,
                    telegramFileName(selection),
                    text,
                    parseMode,
                    keyboard
            );
        } catch (RuntimeException exception) {
            log.warn("Не удалось загрузить картинку уведомления из S3 eventCode={}, assetId={}: {}",
                    selection.eventCode(), selection.assetId(), exception.getMessage());
            return telegramService.sendPhotoWithInlineKeyboard(
                    chatId,
                    selection.imageUrl(),
                    text,
                    parseMode,
                    keyboard
            );
        }
    }

    private String telegramFileName(NotificationMediaSelector.Selection selection) {
        if (selection.originalFilename() != null && !selection.originalFilename().isBlank()) {
            return selection.originalFilename();
        }
        String extension = switch (selection.contentType() == null ? "" : selection.contentType()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        return "notification-" + selection.assetId() + extension;
    }

    private void record(
            NotificationMediaSelector.Selection selection,
            long chatId,
            Long recipientUserId,
            boolean photoSent,
            String note
    ) {
        try {
            NotificationMediaDelivery delivery = new NotificationMediaDelivery();
            delivery.setRuleId(selection.ruleId());
            delivery.setAssetId(selection.assetId());
            delivery.setEventCode(selection.eventCode());
            delivery.setRecipientUserId(recipientUserId);
            delivery.setChatId(chatId);
            delivery.setPhotoSent(photoSent);
            delivery.setDeliveryNote(note);
            delivery.setSentAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
        } catch (RuntimeException exception) {
            log.warn("Не удалось записать историю картинки уведомления eventCode={}, chatId={}: {}",
                    selection.eventCode(), chatId, exception.getMessage());
        }
    }
}
