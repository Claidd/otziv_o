package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.api.ClientChatReplyEvidence;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClientChatContentClassifier {

    // Gateways (WhatsApp, Telegram, MAX) store media as «[Вложение: <type>]» or «[Вложение]»; a caption
    // follows the marker after a space.
    private static final Pattern ATTACHMENT_MARKER = Pattern.compile(
            "^\\[вложение(?::\\s*([^\\]\\r\\n]*))?]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern BARE_MEDIA_MARKER = Pattern.compile(
            "^\\[(image|file|video|audio|ptt|sticker)]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern FILE_NAME = Pattern.compile(
            "^[^\\r\\n?]{1,240}\\.(pdf|docx?|xlsx?|csv|txt|rtf|png|jpe?g|webp|heic|zip|rar)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private ClientChatContentClassifier() {
    }

    /**
     * The client sent only a file, with no text to assess. A captioned file is not «only» a file: its caption is
     * still assessed (reply quality, daily report) — use {@link #hasAttachmentMarker} to learn that a file came at all.
     */
    public static boolean attachmentOnly(String value) {
        String text = value == null ? "" : value.trim();
        Matcher marker = ATTACHMENT_MARKER.matcher(text);
        if (marker.find()) {
            String type = marker.group(1);
            return marker.end() == text.length() && (type == null || !"reaction".equalsIgnoreCase(type.trim()));
        }
        return BARE_MEDIA_MARKER.matcher(text).matches()
                || FILE_NAME.matcher(text).matches();
    }

    /** Starts with the gateway attachment marker; a reaction is not an attachment. */
    public static boolean hasAttachmentMarker(String value) {
        String text = value == null ? "" : value.trim();
        Matcher marker = ATTACHMENT_MARKER.matcher(text);
        if (!marker.find()) {
            return false;
        }
        String type = marker.group(1);
        return type == null || !"reaction".equalsIgnoreCase(type.trim());
    }

    public static boolean reactionOnly(String value) {
        return ClientChatReplyEvidence.reactionOnly(value);
    }
}
