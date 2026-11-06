package com.hunt.otziv.whatsapp.dto;

/**
 * One group of a connected WhatsApp account. {@code inviteStatus} is the gateway's reason when
 * {@code inviteLink} is empty ({@code not_member}, {@code not_admin}, {@code refused}, {@code timeout},
 * {@code unsupported}, {@code empty}, {@code error}); {@code ok} with a link, and null from a gateway
 * that does not report it.
 */
public record WhatsAppGroupInfo(
        String groupId,
        String name,
        String inviteLink,
        String inviteStatus
) {

    public WhatsAppGroupInfo(String groupId, String name, String inviteLink) {
        this(groupId, name, inviteLink, null);
    }
}
