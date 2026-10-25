package com.hunt.otziv.client_messages.api;

import java.util.List;

/** Server-only seven-reminder archive policy shared by scheduled processing and an authorized repair. */
public interface ReviewReminderClosure {
    List<Long> archiveCandidates(long afterId, int limit);
    record Decision(boolean archived, String reason) { }
    Decision archiveAfterReminders(long stateId);
}
