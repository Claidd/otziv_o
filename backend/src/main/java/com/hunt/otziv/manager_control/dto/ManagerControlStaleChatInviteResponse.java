package com.hunt.otziv.manager_control.dto;

/**
 * Result of a manager's report that the company chat invite no longer opens the group. The card stays
 * open; {@code inviteUrl} is the current invite the connected account sees, or null when it is unknown.
 * Nothing is sent and the company's bound group and link are not changed.
 */
public record ManagerControlStaleChatInviteResponse(
        ManagerControlConcreteItemResponse item,
        String message,
        String inviteUrl
) {
}
