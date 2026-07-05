package com.hunt.otziv.t_telegrambot.dto;

public record TelegramChatMigrationResult(
        Long oldChatId,
        Long newChatId,
        int companiesUpdated,
        int workerGroupsUpdated
) {
    public int totalUpdated() {
        return companiesUpdated + workerGroupsUpdated;
    }

    public boolean updated() {
        return totalUpdated() > 0;
    }
}
