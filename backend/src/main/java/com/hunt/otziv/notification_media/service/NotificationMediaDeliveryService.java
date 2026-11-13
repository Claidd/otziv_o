package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import com.hunt.otziv.notification_media.repository.NotificationMediaDeliveryRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import com.hunt.otziv.notification_media.api.StaffPictureContext;
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
        return send(eventCode, chatId, recipientUserId, text, parseMode, keyboard, Set.of());
    }

    public boolean send(String eventCode, long chatId, Long recipientUserId, String text, String parseMode,
                        List<List<InlineKeyboardButton>> keyboard, Set<StaffPictureContext> facts) {
        NotificationMediaSelector.Selection selection = select(eventCode, chatId, facts);
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

    /**
     * Sends only the media card for flows where the main Telegram message must remain separate
     * (for example, long reports or messages that are edited later by an inline-keyboard flow).
     * If no card is configured, the caller can continue sending its regular message unchanged.
     */
    public boolean sendMediaOnly(
            String eventCode,
            long chatId,
            Long recipientUserId,
            String caption,
            String parseMode
    ) {
        return sendMediaOnly(eventCode, chatId, recipientUserId, caption, parseMode, Set.of());
    }

    public boolean sendMediaOnly(String eventCode, long chatId, Long recipientUserId, String caption,
                                 String parseMode, Set<StaffPictureContext> facts) {
        NotificationMediaSelector.Selection selection = select(eventCode, chatId, facts);
        if (selection == null) {
            return false;
        }
        boolean photoSent = sendPhoto(selection, chatId, caption, parseMode, List.of());
        record(selection, chatId, recipientUserId, photoSent,
                photoSent ? "PHOTO_SENT" : "PHOTO_FAILED_NO_TEXT_FALLBACK");
        return photoSent;
    }

    private NotificationMediaSelector.Selection select(String eventCode, long chatId, Set<StaffPictureContext> facts) {
        LocalDateTime now = LocalDateTime.now();
        try {
            return (facts.isEmpty() ? selector.select(eventCode, chatId, now)
                    : selector.select(eventCode, chatId, now, facts)).orElse(null);
        } catch (RuntimeException unavailable) {
            log.warn("Image selection unavailable for {}", eventCode, unavailable);
            return null;
        }
    }

    public boolean sendContextHint(String eventCode, long chatId, long userId, String caption, Set<StaffPictureContext> facts) {
        var decision = selector.decide(eventCode, chatId, LocalDateTime.now(), facts);
        if (decision.textTurn()) return telegramService.sendMessage(chatId, caption);
        var picture = decision.picture().orElse(null);
        if (picture == null) return false;
        boolean sent = sendPhoto(picture, chatId, caption, null, List.of());
        record(picture, chatId, userId, sent, sent ? "PHOTO_SENT" : "PHOTO_FAILED_NO_TEXT_FALLBACK");
        return sent;
    }

    public NotificationMediaSelector.Selection selectDisputePicture(long chatId) {
        return select(NotificationMediaEventCatalog.STAFF_PAYMENT_DISPUTE.code(), chatId,
                Set.of(StaffPictureContext.DISPUTABLE_RECEIPT));
    }

    public byte[] loadDisputePicture(NotificationMediaSelector.Selection selection) {
        return storageService.load(selection.storageKey());
    }

    public void recordDisputePicture(NotificationMediaSelector.Selection selection, long chatId) {
        record(selection, chatId, null, true, "PHOTO_SENT");
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
