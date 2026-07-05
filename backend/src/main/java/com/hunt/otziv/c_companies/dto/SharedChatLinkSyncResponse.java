package com.hunt.otziv.c_companies.dto;

public record SharedChatLinkSyncResponse(
        int scannedCompanies,
        int sharedChatGroups,
        int updatedCompanies,
        int whatsappLinked,
        int telegramLinked,
        int maxLinked,
        int conflictGroups
) {
}
