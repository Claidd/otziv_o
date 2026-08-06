package com.hunt.otziv.manager.dto.api;

public record CompanyChatBindingRepairResponse(
        Long companyId,
        String companyTitle,
        String platform,
        String urlChat,
        String groupId,
        Long telegramGroupChatId,
        Long maxGroupChatId,
        String telegramBotInviteUrl,
        String maxBotInviteUrl,
        boolean repaired,
        String launchUrl,
        String message
) {
}
