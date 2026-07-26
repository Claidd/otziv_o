package com.hunt.otziv.client_chat_control.service;

public final class ClientChatContentClassifier {

    private ClientChatContentClassifier() {
    }

    public static boolean attachmentOnly(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("(?iu)^\\[вложение:\\s*(image|file|video|audio|ptt|sticker)]$")
                || text.matches("(?iu)^\\[(image|file|video|audio|ptt|sticker)]$")
                || text.matches(
                        "(?iu)^[^\\r\\n?]{1,240}\\.(pdf|docx?|xlsx?|csv|txt|rtf|png|jpe?g|webp|heic|zip|rar)$"
                );
    }
}
