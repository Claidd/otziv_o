package com.hunt.otziv.client_chat_control.api;

/** Shared read-only classification of reply evidence; no chat state is changed. */
public final class ClientChatReplyEvidence {
    private ClientChatReplyEvidence() {
    }

    public static boolean reactionOnly(String value) {
        String text = value == null ? "" : value.trim();
        return !text.isEmpty() && (text.matches("(?iu)^\\[вложение:\\s*reaction]$")
                || text.codePoints().noneMatch(Character::isLetterOrDigit));
    }
}
