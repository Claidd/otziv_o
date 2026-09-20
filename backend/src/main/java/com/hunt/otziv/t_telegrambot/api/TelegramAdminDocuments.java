package com.hunt.otziv.t_telegrambot.api;

import java.util.List;
import java.util.Optional;

/** Admin-only import reports: scalar recipient policy and one confirmed transport attempt. */
public interface TelegramAdminDocuments {
    boolean canSendDocuments();
    List<Long> adminDocumentRecipients();
    Optional<Integer> sendDocumentOnceMessageId(long chatId, byte[] bytes, String fileName);
}
