package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@RequiredArgsConstructor
public class PerformerTelegramNotificationService {

    private final TelegramService telegramService;

    @Value("${app.public-base-url:https://o-ogo.ru}")
    private String publicBaseUrl;

    public Optional<Integer> sendOffer(ReviewPerformerOffer offer) {
        Long chatId = offer.getPerformer().getUser().getTelegramChatId();
        if (chatId == null) {
            return Optional.empty();
        }

        String text = offerText(offer);
        List<List<InlineKeyboardButton>> keyboard = List.of(List.of(
                button("Принять", "perf:accept:" + offer.getId()),
                button("Отказаться", "perf:decline:" + offer.getId())
        ));
        return telegramService.sendMessageWithInlineKeyboardMessageId(chatId, text, "HTML", keyboard);
    }

    public void sendAccepted(ReviewPerformerAssignment assignment) {
        Long chatId = assignment.getPerformer() == null || assignment.getPerformer().getUser() == null
                ? null
                : assignment.getPerformer().getUser().getTelegramChatId();
        if (chatId == null) {
            return;
        }
        telegramService.sendMessage(chatId, acceptedText(assignment), "HTML");
    }

    public void sendReadyToPublish(ReviewPerformerAssignment assignment) {
        Long chatId = assignment.getPerformer() == null || assignment.getPerformer().getUser() == null
                ? null
                : assignment.getPerformer().getUser().getTelegramChatId();
        if (chatId == null) {
            return;
        }
        telegramService.sendMessage(chatId, "Можно публиковать отзыв по заданию #" + assignment.getId()
                + ". Откройте кабинет: " + performerUrl(), "HTML");
    }

    private String offerText(ReviewPerformerOffer offer) {
        ReviewPerformerAssignment assignment = offer.getAssignment();
        Company company = assignment.getOrder() == null ? null : assignment.getOrder().getCompany();
        String title = company == null ? "Компания" : safe(company.getTitle());
        String city = assignment.getCity() == null ? "" : safe(assignment.getCity().getTitle());
        String platform = assignment.getPlatform() == null ? "площадка" : assignment.getPlatform().name();
        return "<b>Новое задание #" + assignment.getId() + "</b>\n"
                + title + "\n"
                + (city.isBlank() ? "" : "Город: " + city + "\n")
                + "Площадка: " + platform + "\n"
                + "Ответьте до: " + offer.getExpiresAt() + "\n"
                + "Кабинет: " + performerUrl();
    }

    private String acceptedText(ReviewPerformerAssignment assignment) {
        return "<b>Задание закреплено #" + assignment.getId() + "</b>\n"
                + safe(assignment.getInstruction()) + "\n\n"
                + "Черновик отзыва будет доступен в кабинете: " + performerUrl();
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private String performerUrl() {
        return publicBaseUrl.replaceAll("/+$", "") + "/performer";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
