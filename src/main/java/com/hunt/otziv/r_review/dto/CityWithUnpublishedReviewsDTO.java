package com.hunt.otziv.r_review.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CityWithUnpublishedReviewsDTO {
    private Long cityId;
    private String cityTitle;
    private Long unpublishedCount;
    private Long unpublishedNotArchiveCount;
    private Long activeBotsCount;
    private Long botBalance; // Разница: боты - неархивные отзывы
    private String botStatus; // Статус соотношения
    private Double botPercentage; // Процент соотношения

    // Конструктор для репозитория
    public CityWithUnpublishedReviewsDTO(Long cityId, String cityTitle,
                                         Long unpublishedCount, Long unpublishedNotArchiveCount,
                                         Integer activeBotsCount) {
        this.cityId = cityId;
        this.cityTitle = cityTitle;
        this.unpublishedCount = unpublishedCount;
        this.unpublishedNotArchiveCount = unpublishedNotArchiveCount;
        this.activeBotsCount = activeBotsCount != null ? activeBotsCount.longValue() : 0L;

        this.botBalance = this.activeBotsCount - this.unpublishedNotArchiveCount;

        if (this.unpublishedNotArchiveCount > 0 && this.activeBotsCount > 0) {
            this.botPercentage = (double) this.activeBotsCount / this.unpublishedNotArchiveCount * 100;
        } else if (this.activeBotsCount > 0 && this.unpublishedNotArchiveCount == 0) {
            this.botPercentage = 1000.0;
        } else {
            this.botPercentage = 0.0;
        }

        this.botStatus = calculateBotStatus();
    }

    private String calculateBotStatus() {
        if (unpublishedNotArchiveCount == 0) {
            if (activeBotsCount == 0) {
                return "Нет данных";
            } else {
                return "Боты есть, отзывов нет";
            }
        }

        if (activeBotsCount == 0) {
            return "Нет ботов";
        }

        double ratio = (double) activeBotsCount / unpublishedNotArchiveCount;
        double percentage = ratio * 100;

        if (percentage >= 400) {
            return "Крайний избыток (+300%)";
        } else if (percentage >= 300) {
            return "Большой избыток (+200%)";
        } else if (percentage >= 200) {
            return "Избыток (+100%)";
        } else if (percentage >= 150) {
            return "Умеренный избыток (+50%)";
        } else if (percentage >= 110) {
            return "Небольшой избыток (+10%)";
        } else if (percentage >= 90) {
            return "Баланс (±10%)";
        } else if (percentage >= 50) {
            return "Небольшой дефицит (-50%)";
        } else if (percentage >= 0) {
            return "Дефицит (-100%)";
        } else {
            return "Критический дефицит";
        }
    }

    public String getBotStatusCssClass() {
        if (botStatus == null) {
            return "bot-status status-no-data";
        }

        if (botStatus.contains("+300%")) {
            return "bot-status status-excess-high";
        } else if (botStatus.contains("+100%")) {
            return "bot-status status-excess-medium";
        } else if (botStatus.contains("+50%")) {
            return "bot-status status-excess-low";
        } else if (botStatus.contains("Баланс")) {
            return "bot-status status-balanced";
        } else if (botStatus.contains("-50%")) {
            return "bot-status status-deficit-low";
        } else if (botStatus.contains("-100%")) {
            return "bot-status status-deficit-medium";
        } else if (botStatus.contains("Критический")) {
            return "bot-status status-critical";
        } else {
            return "bot-status status-no-data";
        }
    }
}